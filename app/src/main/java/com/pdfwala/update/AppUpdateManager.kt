package com.pdfwala.update

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager as PlayAppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.pdfwala.PdfWalaApplication
import org.json.JSONObject

class AppUpdateManager(
    context: Context
) {

    private val context = context.applicationContext

    /*
     * Use the ONE Remote Config instance created
     * by PdfWalaApplication.
     *
     * This class only reads already activated values.
     * It does NOT create another RemoteConfigManager
     * and does NOT fetch Firebase.
     */
    private val remoteConfigManager =
        (context.applicationContext as PdfWalaApplication)
            .remoteConfigManager

    /*
     * Google Play In-App Update manager.
     */
    private val playUpdateManager: PlayAppUpdateManager =
        AppUpdateManagerFactory.create(context)

    /*
     * Flexible update download listener.
     */
    private var flexibleUpdateListener:
            InstallStateUpdatedListener? = null

    /*
     * Stores which update flow was started.
     *
     * Used by handleUpdateResult() to determine whether
     * a cancelled Activity Result belongs to an Immediate
     * update.
     */
    private var lastStartedUpdateType: Int? = null

    /*
     * Local storage for Flexible update cooldown.
     */
    private val updatePreferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    companion object {

        private const val PREFS_NAME =
            "pdf_wala_update_preferences"

        private const val KEY_LATER_TIME =
            "optional_update_later_time"

        private const val KEY_LATER_TARGET_VERSION =
            "optional_update_later_target_version"
    }

    /**
     * Check whether a configured update is available.
     *
     * IMPORTANT:
     *
     * Remote Config is NOT fetched here.
     *
     * PdfWalaApplication performs the central
     * fetchAndActivate() during application startup.
     *
     * This method only reads the already activated
     * Remote Config values.
     */
    fun checkForUpdate(
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpdateCheckFinished: (() -> Unit)? = null
    ) {

        checkActivatedRemoteConfig(
            onUpdateAvailable = onUpdateAvailable,
            onUpdateCheckFinished = onUpdateCheckFinished
        )
    }

    /**
     * Read the already activated Remote Config.
     */
    private fun checkActivatedRemoteConfig(
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpdateCheckFinished: (() -> Unit)?
    ) {

        val jsonString =
            remoteConfigManager.getUpdateConfig()

        if (jsonString.isBlank()) {
            onUpdateCheckFinished?.invoke()
            return
        }

        val updateInfo =
            parseUpdateConfig(jsonString)

        if (updateInfo == null) {
            onUpdateCheckFinished?.invoke()
            return
        }

        /*
         * Firebase must explicitly enable updates.
         */
        if (!updateInfo.enabled) {
            onUpdateCheckFinished?.invoke()
            return
        }

        /*
         * Compare installed versionCode with
         * the Firebase target version.
         */
        val currentVersion =
            getCurrentVersionCode()

        if (currentVersion >= updateInfo.targetVersion) {
            onUpdateCheckFinished?.invoke()
            return
        }

        /*
         * Now ask Google Play whether an actual
         * update is available.
         */
        checkGooglePlayAvailability(
            updateInfo = updateInfo,
            onUpdateAvailable = onUpdateAvailable,
            onUpdateCheckFinished = onUpdateCheckFinished
        )
    }

    /**
     * Ask Google Play for the current update status.
     */
    private fun checkGooglePlayAvailability(
        updateInfo: UpdateInfo,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpdateCheckFinished: (() -> Unit)?
    ) {

        playUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->

                /*
                 * We only show our custom dialog when
                 * Google Play actually reports an update.
                 */
                val updateAvailable =
                    appUpdateInfo.updateAvailability() ==
                            UpdateAvailability.UPDATE_AVAILABLE

                if (!updateAvailable) {
                    onUpdateCheckFinished?.invoke()
                    return@addOnSuccessListener
                }

                /*
                 * IMMEDIATE
                 *
                 * Do NOT start Google's UI automatically.
                 *
                 * MainActivity first displays:
                 *
                 *     Update Required
                 *
                 *          Exit | Update
                 */
                if (updateInfo.isImmediate) {

                    if (
                        !appUpdateInfo.isUpdateTypeAllowed(
                            AppUpdateType.IMMEDIATE
                        )
                    ) {
                        onUpdateCheckFinished?.invoke()
                        return@addOnSuccessListener
                    }

                    onUpdateAvailable(updateInfo)

                    onUpdateCheckFinished?.invoke()
                    return@addOnSuccessListener
                }

                /*
                 * FLEXIBLE
                 *
                 * MainActivity displays:
                 *
                 *     Update Available
                 *
                 *          Later | Update
                 */
                if (updateInfo.isFlexible) {

                    if (
                        !appUpdateInfo.isUpdateTypeAllowed(
                            AppUpdateType.FLEXIBLE
                        )
                    ) {
                        onUpdateCheckFinished?.invoke()
                        return@addOnSuccessListener
                    }

                    /*
                     * Check the user's Later cooldown.
                     */
                    if (
                        shouldShowFlexibleUpdate(
                            updateInfo
                        )
                    ) {
                        onUpdateAvailable(updateInfo)
                    }

                    onUpdateCheckFinished?.invoke()
                    return@addOnSuccessListener
                }

                /*
                 * Unknown update type.
                 */
                onUpdateCheckFinished?.invoke()
            }
            .addOnFailureListener {

                /*
                 * If Google Play cannot be queried,
                 * do not block the user from using PDF Wala.
                 */
                onUpdateCheckFinished?.invoke()
            }
    }

    /**
     * Start an Immediate In-App Update.
     *
     * This is called only after the user presses
     * "Update" in the PDF Wala custom dialog.
     *
     * If starting the mandatory update fails,
     * onStartFailed() is called so MainActivity
     * can exit the application.
     */
    fun startImmediateUpdate(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onStartFailed: () -> Unit
    ) {

        playUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->

                val updateAvailable =
                    appUpdateInfo.updateAvailability() ==
                            UpdateAvailability.UPDATE_AVAILABLE

                if (!updateAvailable) {
                    onStartFailed()
                    return@addOnSuccessListener
                }

                if (
                    !appUpdateInfo.isUpdateTypeAllowed(
                        AppUpdateType.IMMEDIATE
                    )
                ) {
                    onStartFailed()
                    return@addOnSuccessListener
                }

                try {

                    lastStartedUpdateType =
                        AppUpdateType.IMMEDIATE

                    playUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        launcher,
                        AppUpdateOptions.newBuilder(
                            AppUpdateType.IMMEDIATE
                        ).build()
                    )

                } catch (_: Exception) {

                    /*
                     * Immediate update is mandatory.
                     *
                     * If Google Play cannot start the flow,
                     * MainActivity will close the application.
                     */
                    lastStartedUpdateType = null
                    onStartFailed()
                }
            }
            .addOnFailureListener {

                /*
                 * Could not obtain fresh AppUpdateInfo.
                 *
                 * Immediate update is mandatory.
                 */
                onStartFailed()
            }
    }

    /**
     * Start a Flexible In-App Update.
     *
     * Called only after the user presses
     * "Update" in the optional update dialog.
     */
    fun startFlexibleUpdate(
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {

        playUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->

                val updateAvailable =
                    appUpdateInfo.updateAvailability() ==
                            UpdateAvailability.UPDATE_AVAILABLE

                if (!updateAvailable) {
                    return@addOnSuccessListener
                }

                if (
                    !appUpdateInfo.isUpdateTypeAllowed(
                        AppUpdateType.FLEXIBLE
                    )
                ) {
                    return@addOnSuccessListener
                }

                try {

                    lastStartedUpdateType =
                        AppUpdateType.FLEXIBLE

                    playUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        launcher,
                        AppUpdateOptions.newBuilder(
                            AppUpdateType.FLEXIBLE
                        ).build()
                    )

                } catch (_: Exception) {

                    /*
                     * Flexible update failure is non-fatal.
                     * User can continue using the app.
                    */
                    lastStartedUpdateType = null
                }
            }
    }

    /**
     * Resume an interrupted Immediate update.
     *
     * MainActivity should call this from onResume().
     *
     * If Google Play reports that an Immediate update
     * is already in progress, we resume Google's UI.
     */
    fun resumeImmediateUpdateIfNeeded(
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {

        playUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->

                if (
                    appUpdateInfo.updateAvailability() ==
                    UpdateAvailability
                        .DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {

                    if (
                        appUpdateInfo.isUpdateTypeAllowed(
                            AppUpdateType.IMMEDIATE
                        )
                    ) {

                        try {

                            lastStartedUpdateType =
                                AppUpdateType.IMMEDIATE

                            playUpdateManager.startUpdateFlowForResult(
                                appUpdateInfo,
                                launcher,
                                AppUpdateOptions.newBuilder(
                                    AppUpdateType.IMMEDIATE
                                ).build()
                            )

                        } catch (_: Exception) {

                            /*
                             * MainActivity can handle the
                             * mandatory update failure if needed.
                             */
                        }
                    }
                }
            }
    }

    /**
     * Check whether a Flexible update has already
     * finished downloading.
     *
     * This is useful from onResume(), because the
     * download could have completed while the Activity
     * was not visible.
     */
    fun checkDownloadedFlexibleUpdate(
        onUpdateDownloaded: () -> Unit
    ) {

        playUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->

                if (
                    appUpdateInfo.installStatus() ==
                    InstallStatus.DOWNLOADED
                ) {
                    onUpdateDownloaded()
                }
            }
    }

    /**
     * Register listener for Flexible update download.
     */
    fun registerFlexibleUpdateListener(
        onUpdateDownloaded: () -> Unit
    ) {

        unregisterFlexibleUpdateListener()

        flexibleUpdateListener =
            InstallStateUpdatedListener { state ->

                if (
                    state.installStatus() ==
                    InstallStatus.DOWNLOADED
                ) {
                    onUpdateDownloaded()
                }
            }

        playUpdateManager.registerListener(
            flexibleUpdateListener!!
        )
    }

    /**
     * Unregister Flexible update listener.
     */
    fun unregisterFlexibleUpdateListener() {

        flexibleUpdateListener?.let { listener ->
            playUpdateManager.unregisterListener(
                listener
            )
        }

        flexibleUpdateListener = null
    }

    /**
     * Complete a downloaded Flexible update.
     *
     * Google Play handles the actual installation.
     */
    fun completeFlexibleUpdate() {
        playUpdateManager.completeUpdate()
    }

    /**
     * Save the user's "Later" action.
     *
     * We store both:
     * - time
     * - target version
     *
     * This allows a newer target version to bypass
     * an old cooldown.
     */
    fun postponeFlexibleUpdate(
        updateInfo: UpdateInfo
    ) {

        updatePreferences
            .edit()
            .putLong(
                KEY_LATER_TIME,
                System.currentTimeMillis()
            )
            .putLong(
                KEY_LATER_TARGET_VERSION,
                updateInfo.targetVersion
            )
            .apply()
    }

    /**
     * Determine whether the Flexible update dialog
     * should currently be shown.
     */
    private fun shouldShowFlexibleUpdate(
        updateInfo: UpdateInfo
    ): Boolean {

        val lastLaterTime =
            updatePreferences.getLong(
                KEY_LATER_TIME,
                0L
            )

        val lastLaterTargetVersion =
            updatePreferences.getLong(
                KEY_LATER_TARGET_VERSION,
                0L
            )

        /*
         * User has never pressed Later.
         */
        if (lastLaterTime <= 0L) {
            return true
        }

        /*
         * Firebase target changed.
         *
         * Show the new update immediately.
         */
        if (
            lastLaterTargetVersion !=
            updateInfo.targetVersion
        ) {
            return true
        }

        /*
         * cooldown_hours = 0 means
         * show the update every time.
         */
        val cooldownHours =
            updateInfo.cooldownHours
                .coerceAtLeast(0L)

        if (cooldownHours == 0L) {
            return true
        }

        val cooldownMillis =
            cooldownHours * 60L * 60L * 1000L

        val elapsed =
            System.currentTimeMillis() -
                    lastLaterTime

        return elapsed >= cooldownMillis
    }

    /**
     * Handle Activity Result from Google Play.
     *
     * Immediate:
     *   RESULT_OK       → accepted/completed flow
     *   RESULT_CANCELED → user cancelled
     *   anything else   → failure
     *
     * For Immediate, every non-success result is
     * considered a mandatory-update cancellation/failure.
     *
     * Flexible:
     *   cancellation/failure does not close the app.
     */
    fun handleUpdateResult(
        resultCode: Int,
        onImmediateUpdateCancelled: () -> Unit
    ) {

        if (
            lastStartedUpdateType ==
            AppUpdateType.IMMEDIATE
        ) {

            if (
                resultCode != Activity.RESULT_OK
            ) {
                onImmediateUpdateCancelled()
            }
        }

        /*
         * Clear the state after receiving the result.
         */
        lastStartedUpdateType = null
    }

    /**
     * Get installed versionCode.
     */
    private fun getCurrentVersionCode(): Long {

        return PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
        )
    }

    /**
     * Parse Firebase update_config JSON.
     */
    private fun parseUpdateConfig(
        jsonString: String
    ): UpdateInfo? {

        return try {

            val json =
                JSONObject(jsonString)

            val enabled =
                json.optBoolean(
                    "enable",
                    false
                )

            val targetVersion =
                json.optLong(
                    "version_target",
                    0L
                )

            if (targetVersion <= 0L) {
                return null
            }

            val updateType =
                json.optString(
                    "update_type",
                    "immediate"
                )
                    .trim()
                    .lowercase()

            /*
             * Only these two update modes
             * are supported.
             */
            if (
                updateType != "immediate" &&
                updateType != "flexible"
            ) {
                return null
            }

            UpdateInfo(
                enabled = enabled,
                targetVersion = targetVersion,
                updateType = updateType,
                message = json.optString(
                    "message",
                    "A new version of PDF Wala is available."
                ),
                cooldownHours = json.optLong(
                    "cooldown_hours",
                    24L
                )
            )

        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Parsed Firebase update configuration.
 */
data class UpdateInfo(
    val enabled: Boolean,
    val targetVersion: Long,
    val updateType: String,
    val message: String,
    val cooldownHours: Long
) {

    val isImmediate: Boolean
        get() = updateType == "immediate"

    val isFlexible: Boolean
        get() = updateType == "flexible"
}