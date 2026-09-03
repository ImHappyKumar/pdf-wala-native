package com.pdfwala.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

class AppUpdateManager(
    private val context: Context
) {

    private val remoteConfig = FirebaseRemoteConfig.getInstance()

    fun checkForUpdate(
        onUpdateAvailable: (UpdateInfo) -> Unit
    ) {
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->

                if (!task.isSuccessful) {
                    // Firebase unavailable/offline.
                    // Do not block the app.
                    return@addOnCompleteListener
                }

                val jsonString =
                    remoteConfig.getString("update_config")

                if (jsonString.isBlank()) return@addOnCompleteListener

                try {
                    val json = JSONObject(jsonString)

                    val enabled = json.optBoolean("enable", false)
                    val targetVersion =
                        json.optInt("version_target", 0)

                    if (!enabled || targetVersion <= 0) {
                        return@addOnCompleteListener
                    }

                    val currentVersion = PackageInfoCompat.getLongVersionCode(
                        context.packageManager.getPackageInfo(
                            context.packageName,
                            0
                        )
                    )

                    if (currentVersion < targetVersion) {

                        val updateType =
                            json.optString(
                                "update_type",
                                "immediate"
                            )

                        val message =
                            json.optString(
                                "message",
                                "A new version is available."
                            )

                        val cooldownHours =
                            json.optLong(
                                "cooldown_hours",
                                24
                            )

                        onUpdateAvailable(
                            UpdateInfo(
                                targetVersion = targetVersion,
                                updateType = updateType,
                                message = message,
                                cooldownHours = cooldownHours
                            )
                        )
                    }

                } catch (_: Exception) {
                    // Invalid Remote Config JSON.
                    // Do not block the app.
                }
            }
    }

    fun openPlayStore() {
        val packageName = context.packageName

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "https://play.google.com/store/apps/details?id=$packageName"
            )
        )

        context.startActivity(intent)
    }
}

data class UpdateInfo(
    val targetVersion: Int,
    val updateType: String,
    val message: String,
    val cooldownHours: Long
)