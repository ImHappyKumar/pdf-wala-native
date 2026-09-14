package com.pdfwala.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.pdfwala.data.SafUriManager
import com.pdfwala.ui.navigation.AppNavigation
import com.pdfwala.ui.theme.PDFWalaTheme
import com.pdfwala.update.AppUpdateManager
import com.pdfwala.update.UpdateInfo
import com.pdfwala.util.CacheManager
import com.pdfwala.util.RatingManager
import com.pdfwala.util.ReviewHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Main entry point for the PDF Wala app.
 *
 * Handles:
 * - Incoming PDF intents
 * - SAF URI access
 * - PDF cache handling
 * - Navigation
 * - Google Play In-App Updates
 * - Optional/Flexible update dialog
 * - Immediate/Mandatory update dialog
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pendingPdfUri: Uri? = null
    private var pendingPdfName: String? = null
    private var pendingIsLoading: Boolean = false

    /*
     * Compose state holders for handling incoming PDF
     * intents while the Activity is already running.
     */
    private var pdfUriState:
            androidx.compose.runtime.MutableState<Uri?>? = null

    private var pdfNameState:
            androidx.compose.runtime.MutableState<String?>? = null

    private var isLoadingState:
            androidx.compose.runtime.MutableState<Boolean>? = null

    /*
     * Compose state reference used by onResume() to show
     * the restart dialog when a Flexible update finished
     * downloading while the Activity was not visible.
     */
    private var restartDialogState:
            androidx.compose.runtime.MutableState<Boolean>? = null

    /*
     * Google Play In-App Update manager.
     */
    private lateinit var appUpdateManager: AppUpdateManager

    /**
     * Activity Result launcher used by Google Play's
     * In-App Update API.
     *
     * We intentionally use Activity Result API instead
     * of the older request-code based API.
     */
    private val updateLauncher:
            ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            /*
             * AppUpdateManager knows whether the currently
             * running flow was Immediate or Flexible.
             *
             * If Immediate was cancelled/failed, the app
             * must exit because the update is mandatory.
             */
            appUpdateManager.handleUpdateResult(
                resultCode = result.resultCode,
                onImmediateUpdateCancelled = {
                    finishAffinity()
                }
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        /*
         * Language is already initialized by the Application
         * class before Activity creation.
         *
         * Do not initialize language again here.
         */
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        appUpdateManager =
            AppUpdateManager(this)

        /*
         * Handle PDF intent BEFORE setContent.
         *
         * This is important because ACTION_VIEW/ACTION_SEND
         * permissions may be temporary.
         */
        try {
            handleIntent(intent)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error handling intent in onCreate",
                e
            )
        }

        Log.d(
            TAG,
            "onCreate: After handleIntent, " +
                    "pendingPdfUri=$pendingPdfUri, " +
                    "pendingPdfName=$pendingPdfName, " +
                    "pendingIsLoading=$pendingIsLoading"
        )

        setContent {

            PDFWalaTheme {

                /*
                 * Optional/Flexible update waiting to be
                 * displayed in our custom dialog.
                 *
                 * Immediate update also comes here first,
                 * so Google UI does NOT suddenly appear as
                 * soon as the app opens.
                 */
                var updateInfo by remember {
                    mutableStateOf<UpdateInfo?>(null)
                }

                /*
                 * True after a Flexible update has finished
                 * downloading and is ready to install.
                 */
                var showRestartDialog by remember {
                    mutableStateOf(false)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    val navController =
                        rememberNavController()

                    /*
                     * Initialize PDF state using values
                     * prepared by handleIntent().
                     */
                    val pdfUri =
                        remember {
                            mutableStateOf(pendingPdfUri)
                        }

                    val pdfName =
                        remember {
                            mutableStateOf(pendingPdfName)
                        }

                    val isLoading =
                        remember {
                            mutableStateOf(pendingIsLoading)
                        }

                    /*
                     * Store references so onNewIntent()
                     * can update Compose state.
                     */
                    pdfUriState = pdfUri
                    pdfNameState = pdfName
                    isLoadingState = isLoading

                    /**
                     * Update initialization/check.
                     *
                     * AppUpdateManager:
                     *
                     * Remote Config
                     *       ↓
                     * enable
                     *       ↓
                     * version_target
                     *       ↓
                     * Google Play availability
                     *       ↓
                     * Immediate/Flexible
                     */
                    LaunchedEffect(Unit) {

                        /*
                         * Listen for Flexible update download.
                         *
                         * Once Google Play finishes downloading
                         * the update, show our Restart dialog.
                         */
                        appUpdateManager
                            .registerFlexibleUpdateListener {

                                showRestartDialog = true
                            }

                        /*
                         * Check for update.
                         *
                         * IMPORTANT:
                         *
                         * Immediate:
                         *     custom dialog first
                         *     Exit | Update
                         *
                         * Flexible:
                         *     custom dialog
                         *     Later | Update
                         */
                        appUpdateManager.checkForUpdate(
                            onUpdateAvailable = { info ->

                                updateInfo = info
                            }
                        )
                    }

                    /*
                     * Observe rating request.
                     */
                    LaunchedEffect(Unit) {

                        RatingManager
                            .showRatingRequest
                            .collect { shouldShow ->

                                if (shouldShow) {

                                    ReviewHelper.showReview(
                                        this@MainActivity
                                    )
                                }
                            }
                    }

                    Log.d(
                        TAG,
                        "Composing AppNavigation with " +
                                "initialPdfUri=${pdfUri.value}, " +
                                "initialPdfName=${pdfName.value}, " +
                                "isLoading=${isLoading.value}"
                    )

                    if (isLoading.value) {

                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {

                            CircularProgressIndicator()
                        }

                    } else {

                        AppNavigation(
                            navController = navController,
                            modifier =
                                Modifier.fillMaxSize(),
                            initialPdfUri =
                                pdfUri.value,
                            initialPdfName =
                                pdfName.value
                        )
                    }
                }

                /*
                 * =====================================================
                 * CUSTOM UPDATE DIALOG
                 * =====================================================
                 *
                 * Immediate:
                 *     Exit | Update
                 *
                 * Flexible:
                 *     Later | Update
                 */
                updateInfo?.let { info ->

                    val isImmediate =
                        info.isImmediate

                    AlertDialog(

                        /*
                         * Immediate update MUST NOT be
                         * dismissible by touching outside.
                         *
                         * Flexible can only be dismissed
                         * through the Later button.
                         */
                        onDismissRequest = {
                            // Intentionally disabled.
                            // Flexible updates are dismissed only
                            // through the explicit Later button.
                        },

                        properties =
                            DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            ),

                        shape =
                            RoundedCornerShape(24.dp),

                        title = {

                            Text(
                                text =
                                    if (isImmediate) {
                                        "Update Required"
                                    } else {
                                        "Update Available"
                                    },
                                style =
                                    MaterialTheme
                                        .typography
                                        .headlineSmall
                            )
                        },

                        text = {

                            Text(
                                text =
                                    info.message
                                        .ifBlank {

                                            if (isImmediate) {

                                                "A new version of PDF Wala is available. Please update to continue using the app."

                                            } else {

                                                "A new version of PDF Wala is available. Update now to get the latest improvements."
                                            }
                                        },

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyLarge
                            )
                        },

                        confirmButton = {

                            Button(
                                onClick = {

                                    /*
                                     * Remove our custom dialog before
                                     * launching Google's update UI.
                                     */
                                    updateInfo = null

                                    if (isImmediate) {

                                        /*
                                         * Mandatory update.
                                         *
                                         * Google Play Immediate
                                         * In-App Update starts here.
                                         */
                                        appUpdateManager
                                            .startImmediateUpdate(
                                                launcher =
                                                    updateLauncher,
                                                onStartFailed = {
                                                    finishAffinity()
                                                }
                                            )

                                    } else {

                                        /*
                                         * Optional/Flexible update.
                                         *
                                         * Google Play Flexible
                                         * In-App Update starts here.
                                         */
                                        appUpdateManager
                                            .startFlexibleUpdate(
                                                launcher =
                                                    updateLauncher
                                            )
                                    }
                                }
                            ) {

                                Text("Update")
                            }
                        },

                        dismissButton = {

                            OutlinedButton(
                                onClick = {

                                    if (isImmediate) {

                                        /*
                                         * Immediate update is mandatory.
                                         *
                                         * User chose Exit instead
                                         * of updating.
                                         */
                                        finishAffinity()

                                    } else {

                                        /*
                                         * Optional update:
                                         *
                                         * Save current time and target
                                         * version so this dialog does
                                         * not appear again until the
                                         * configured cooldown expires.
                                         */
                                        appUpdateManager
                                            .postponeFlexibleUpdate(
                                                info
                                            )

                                        updateInfo = null
                                    }
                                }
                            ) {

                                Text(
                                    text =
                                        if (isImmediate) {
                                            "Exit"
                                        } else {
                                            "Later"
                                        }
                                )
                            }
                        }
                    )
                }

                /*
                 * =====================================================
                 * FLEXIBLE UPDATE READY / RESTART DIALOG
                 * =====================================================
                 */
                if (showRestartDialog) {

                    AlertDialog(

                        /*
                         * Do not allow accidental dismissal.
                         *
                         * The update is already downloaded and
                         * waiting to be installed.
                         */
                        onDismissRequest = {
                            // Intentionally disabled.
                        },

                        properties =
                            DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            ),

                        shape =
                            RoundedCornerShape(24.dp),

                        title = {

                            Text(
                                text = "Update Ready",
                                style =
                                    MaterialTheme
                                        .typography
                                        .headlineSmall
                            )
                        },

                        text = {

                            Text(
                                text =
                                    "The update has been downloaded and is ready to install. Restart PDF Wala to finish the update.",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyLarge
                            )
                        },

                        confirmButton = {

                            Button(
                                onClick = {

                                    showRestartDialog = false

                                    /*
                                     * Tell Google Play to install
                                     * the downloaded Flexible update.
                                     */
                                    appUpdateManager
                                        .completeFlexibleUpdate()
                                }
                            ) {

                                Text("Restart")
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Resume interrupted Immediate updates.
     *
     * Google Play can report:
     *
     * DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
     *
     * when the user returns to the app after the
     * Immediate update flow was interrupted.
     */
    override fun onResume() {

        super.onResume()

        /*
         * Only resumes an Immediate update that was
         * already started by Google Play.
         */
        appUpdateManager
            .resumeImmediateUpdateIfNeeded(
                launcher = updateLauncher
            )

        /*
         * The Flexible update may have finished downloading
         * while the Activity was in the background.
         *
         * Check Google Play again when returning to the app.
         */
        appUpdateManager
            .checkDownloadedFlexibleUpdate {
                restartDialogState?.value = true
            }
    }

    override fun onDestroy() {

        /*
         * Stop listening for Flexible download updates
         * when this Activity is destroyed.
         */
        if (::appUpdateManager.isInitialized) {

            appUpdateManager
                .unregisterFlexibleUpdateListener()
        }

        super.onDestroy()

        if (isFinishing) {

            /*
             * Clean temporary files in background.
             */
            try {

                Thread {

                    val cleared =
                        CacheManager
                            .clearAllTempFiles(
                                applicationContext
                            )

                    Log.d(
                        TAG,
                        "App closing: Cleared " +
                                "$cleared bytes of temp files"
                    )

                }.start()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error cleaning temp files",
                    e
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {

        super.onNewIntent(intent)

        setIntent(intent)

        /*
         * Reset previous PDF values.
         */
        pendingPdfUri = null
        pendingPdfName = null

        /*
         * Handle the new incoming PDF intent.
         */
        try {

            handleIntent(intent)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error handling intent in onNewIntent",
                e
            )
        }

        /*
         * Update Compose state so the new PDF
         * is reflected immediately.
         */
        Log.d(
            TAG,
            "onNewIntent: Updating Compose state " +
                    "with PDF=$pendingPdfUri, " +
                    "Loading=$pendingIsLoading"
        )

        pdfUriState?.value =
            pendingPdfUri

        pdfNameState?.value =
            pendingPdfName

        isLoadingState?.value =
            pendingIsLoading
    }

    /**
     * Handle incoming Intent.
     *
     * Supports:
     * - ACTION_VIEW
     * - ACTION_SEND
     */
    private fun handleIntent(intent: Intent?) {

        if (intent == null) {
            return
        }

        Log.d(
            TAG,
            "Handling intent action: " +
                    "${intent.action}, data: ${intent.data}"
        )

        try {

            when (intent.action) {

                Intent.ACTION_VIEW -> {

                    intent.data?.let { uri ->

                        Log.d(
                            TAG,
                            "ACTION_VIEW with URI: $uri"
                        )

                        processUri(
                            uri,
                            intent
                        )
                    }
                }

                Intent.ACTION_SEND -> {

                    val uri =
                        getParcelableExtraCompat(
                            intent
                        )

                    uri?.let {

                        Log.d(
                            TAG,
                            "ACTION_SEND with URI: $it"
                        )

                        processUri(
                            it,
                            intent
                        )
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error in handleIntent logic",
                e
            )
        }
    }

    /**
     * Process incoming URI and determine file type.
     *
     * ACTION_VIEW and ACTION_SEND are copied to cache
     * synchronously because their temporary permission
     * may expire after onCreate().
     */
    private fun processUri(
        originalUri: Uri,
        intent: Intent
    ) {

        var mimeType: String? = null

        try {

            mimeType =
                contentResolver.getType(
                    originalUri
                )

        } catch (e: SecurityException) {

            Log.w(
                TAG,
                "SecurityException getting MIME type " +
                        "for $originalUri: ${e.message}"
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error getting MIME type " +
                        "for $originalUri: ${e.message}"
            )
        }

        val fileName =
            getFileName(originalUri)
                ?: "document.pdf"

        Log.d(
            TAG,
            "Processing URI: $originalUri, " +
                    "mimeType: $mimeType, " +
                    "fileName: $fileName, " +
                    "action: ${intent.action}"
        )

        /*
         * Only PDF files are handled.
         */
        if (!isPdfUri(originalUri, mimeType)) {

            Log.w(
                TAG,
                "Not a PDF file, ignoring: $mimeType"
            )

            return
        }

        when (intent.action) {

            Intent.ACTION_VIEW,
            Intent.ACTION_SEND -> {

                Log.d(
                    TAG,
                    "ACTION_VIEW/SEND detected - " +
                            "copying to cache synchronously"
                )

                /*
                 * Must happen synchronously while
                 * the temporary permission is valid.
                 */
                val accessibleUri =
                    runBlocking(Dispatchers.IO) {

                        copyToCacheSynchronous(
                            originalUri,
                            fileName
                        )
                    }

                pendingIsLoading = false

                isLoadingState?.value = false

                if (accessibleUri == null) {

                    Log.e(
                        TAG,
                        "Could not obtain access to URI: " +
                                "$originalUri - file will not be opened"
                    )

                } else {

                    Log.d(
                        TAG,
                        "Successfully copied to cache: " +
                                "$accessibleUri"
                    )

                    pendingPdfUri =
                        accessibleUri

                    pendingPdfName =
                        fileName
                            .removeSuffix(".pdf")
                            .removeSuffix(".PDF")

                    pdfUriState?.value =
                        accessibleUri

                    pdfNameState?.value =
                        pendingPdfName

                    /*
                     * Add to recent files.
                     */
                    activityScope.launch(
                        Dispatchers.IO
                    ) {

                        val cleanName =
                            if (
                                fileName.endsWith(
                                    ".pdf",
                                    ignoreCase = true
                                )
                            ) {
                                fileName
                            } else {
                                "$fileName.pdf"
                            }

                        SafUriManager.addRecentFile(
                            applicationContext,
                            accessibleUri,
                            intent.flags,
                            customName = cleanName
                        )
                    }
                }

                return
            }

            else -> {

                /*
                 * Other intents:
                 * try persistable permission first.
                 */
                pendingIsLoading = true

                isLoadingState?.value = true

                lifecycleScope.launch {

                    val accessibleUri =
                        getAccessibleUri(
                            originalUri,
                            intent,
                            fileName
                        )

                    pendingIsLoading = false

                    isLoadingState?.value = false

                    if (accessibleUri == null) {

                        Log.e(
                            TAG,
                            "Could not obtain access to URI: " +
                                    "$originalUri - " +
                                    "file will not be opened"
                        )

                    } else {

                        Log.d(
                            TAG,
                            "Successfully got accessible URI: " +
                                    "$accessibleUri " +
                                    "(original was: $originalUri)"
                        )

                        pendingPdfUri =
                            accessibleUri

                        pendingPdfName =
                            fileName
                                .removeSuffix(".pdf")
                                .removeSuffix(".PDF")

                        pdfUriState?.value =
                            accessibleUri

                        pdfNameState?.value =
                            pendingPdfName

                        Log.d(
                            TAG,
                            "Set pending PDF: " +
                                    "uri=$pendingPdfUri, " +
                                    "name=$pendingPdfName"
                        )
                    }
                }
            }
        }
    }

    /**
     * Attempts to obtain an accessible URI.
     *
     * 1. Persistable permission
     * 2. Cache fallback
     */
    private suspend fun getAccessibleUri(
        uri: Uri,
        intent: Intent,
        fileName: String
    ): Uri? {

        val permissionTaken =
            SafUriManager.takePersistablePermission(
                this,
                uri,
                intent.flags
            )

        if (
            permissionTaken &&
            canAccessUri(uri)
        ) {

            Log.d(
                TAG,
                "Access via persistable permission successful"
            )

            /*
             * Add to recent files.
             */
            activityScope.launch(
                Dispatchers.IO
            ) {

                SafUriManager.addRecentFile(
                    applicationContext,
                    uri,
                    intent.flags
                )
            }

            return uri
        }

        /*
         * Persistable permission unavailable.
         * Use cache fallback.
         */
        Log.d(
            TAG,
            "Persistable permission not available, " +
                    "copying to cache"
        )

        val cachedUri =
            copyToCache(
                uri,
                fileName
            )

        if (cachedUri != null) {

            activityScope.launch(
                Dispatchers.IO
            ) {

                val cleanName =
                    if (
                        fileName.endsWith(
                            ".pdf",
                            ignoreCase = true
                        )
                    ) {
                        fileName
                    } else {
                        "$fileName.pdf"
                    }

                SafUriManager.addRecentFile(
                    applicationContext,
                    cachedUri,
                    intent.flags,
                    customName = cleanName
                )
            }
        }

        return cachedUri
    }

    /**
     * Check whether URI can be read.
     */
    private fun canAccessUri(
        uri: Uri
    ): Boolean {

        return try {

            contentResolver
                .openInputStream(uri)
                ?.use {

                    Log.d(
                        TAG,
                        "URI is accessible: $uri"
                    )

                    true

                } ?: false

        } catch (e: SecurityException) {

            Log.d(
                TAG,
                "SecurityException accessing URI: " +
                        "${e.message}"
            )

            false

        } catch (e: IOException) {

            Log.d(
                TAG,
                "IOException accessing URI: " +
                        "${e.message}"
            )

            false

        } catch (e: Exception) {

            Log.d(
                TAG,
                "Exception accessing URI: " +
                        "${e.message}"
            )

            false
        }
    }

    /**
     * Copy a content URI into app cache.
     *
     * Returns a FileProvider URI.
     */
    private suspend fun copyToCacheSynchronous(
        sourceUri: Uri,
        fileName: String
    ): Uri? =
        withContext(Dispatchers.IO) {

            return@withContext try {

                val cacheDir =
                    File(
                        cacheDir,
                        "shared_files"
                    )

                if (!cacheDir.exists()) {

                    val created =
                        cacheDir.mkdirs()

                    Log.d(
                        TAG,
                        "Cache directory created: " +
                                "$created at " +
                                cacheDir.absolutePath
                    )
                }

                /*
                 * Clean old cached files.
                 */
                cleanOldCachedFiles(
                    cacheDir
                )

                val extension =
                    getFileExtension(
                        fileName
                    )

                val safeFileName =
                    "shared_${System.currentTimeMillis()}.$extension"

                val targetFile =
                    File(
                        cacheDir,
                        safeFileName
                    )

                Log.d(
                    TAG,
                    "Starting copy: " +
                            "$sourceUri -> " +
                            targetFile.absolutePath
                )

                /*
                 * Open source stream while temporary
                 * permission is still available.
                 */
                val inputStream =
                    try {

                        contentResolver
                            .openInputStream(
                                sourceUri
                            )

                    } catch (e: SecurityException) {

                        Log.e(
                            TAG,
                            "SecurityException opening " +
                                    "input stream: " +
                                    "${e.message}"
                        )

                        Log.e(
                            TAG,
                            "URI: $sourceUri"
                        )

                        return@withContext null

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Exception opening input stream: " +
                                    "${e.message}",
                            e
                        )

                        return@withContext null
                    }

                if (inputStream == null) {

                    Log.e(
                        TAG,
                        "Input stream is null for URI: " +
                                sourceUri
                    )

                    return@withContext null
                }

                try {

                    var bytesCopied = 0L

                    inputStream.use { input ->

                        FileOutputStream(
                            targetFile
                        ).use { output ->

                            bytesCopied =
                                input.copyTo(
                                    output
                                )
                        }
                    }

                    Log.d(
                        TAG,
                        "Copied $bytesCopied bytes to " +
                                targetFile.absolutePath
                    )

                    if (
                        targetFile.exists() &&
                        targetFile.length() > 0
                    ) {

                        /*
                         * Generate FileProvider URI.
                         */
                        val resultUri =
                            FileProvider.getUriForFile(
                                this@MainActivity,
                                "$packageName.provider",
                                targetFile
                            )

                        Log.d(
                            TAG,
                            "Successfully copied to cache! " +
                                    "File size: " +
                                    "${targetFile.length()} bytes"
                        )

                        Log.d(
                            TAG,
                            "FileProvider URI: $resultUri"
                        )

                        resultUri

                    } else {

                        Log.e(
                            TAG,
                            "Copy failed - target file missing " +
                                    "or empty. Exists: " +
                                    "${targetFile.exists()}, " +
                                    "Size: " +
                                    "${targetFile.length()}"
                        )

                        null
                    }

                } catch (e: IOException) {

                    Log.e(
                        TAG,
                        "IOException during copy: " +
                                "${e.message}",
                        e
                    )

                    targetFile.delete()

                    null

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Exception during copy: " +
                                "${e.message}",
                        e
                    )

                    targetFile.delete()

                    null
                }

            } catch (e: SecurityException) {

                Log.e(
                    TAG,
                    "SecurityException in " +
                            "copyToCacheSynchronous: " +
                            "${e.message}",
                    e
                )

                null

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unexpected exception in " +
                            "copyToCacheSynchronous: " +
                            "${e.message}",
                    e
                )

                null
            }
        }

    /**
     * Legacy compatibility method.
     */
    private suspend fun copyToCache(
        sourceUri: Uri,
        fileName: String
    ): Uri? {

        return copyToCacheSynchronous(
            sourceUri,
            fileName
        )
    }

    /**
     * Clean cached files older than 1 hour,
     * unless they are preserved in recent files.
     */
    private fun cleanOldCachedFiles(
        cacheDir: File
    ) {

        try {

            val oneHourAgo =
                System.currentTimeMillis() -
                        (60 * 60 * 1000)

            val dao =
                com.pdfwala.data.local.AppDatabase
                    .getDatabase(
                        applicationContext
                    )
                    .recentFilesDao()

            activityScope.launch(
                Dispatchers.IO
            ) {

                val recentUris =
                    try {

                        dao.getAll()
                            .map {
                                it.uriString
                            }

                    } catch (e: Exception) {

                        emptyList()
                    }

                cacheDir
                    .listFiles()
                    ?.forEach { file ->

                        if (
                            file.lastModified() <
                            oneHourAgo
                        ) {

                            val isRecent =
                                recentUris.any {
                                    it.contains(
                                        file.name
                                    )
                                }

                            if (!isRecent) {
                                file.delete()
                            }
                        }
                    }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error cleaning cache: " +
                        "${e.message}"
            )
        }
    }

    /**
     * Get file extension.
     */
    private fun getFileExtension(
        fileName: String
    ): String {

        val lastDot =
            fileName.lastIndexOf('.')

        return if (
            lastDot >= 0 &&
            lastDot < fileName.length - 1
        ) {

            fileName
                .substring(
                    lastDot + 1
                )
                .lowercase()

        } else {

            "pdf"
        }
    }

    /**
     * Android-version-compatible Parcelable extra.
     */
    private fun getParcelableExtraCompat(
        intent: Intent
    ): Uri? {

        return if (
            android.os.Build.VERSION.SDK_INT >= 33
        ) {

            intent.getParcelableExtra(
                Intent.EXTRA_STREAM,
                Uri::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            intent.getParcelableExtra(
                Intent.EXTRA_STREAM
            )
        }
    }

    /**
     * Check whether URI represents a PDF.
     */
    private fun isPdfUri(
        uri: Uri,
        mimeType: String?
    ): Boolean {

        /*
         * MIME type is the preferred check.
         */
        if (
            mimeType != null &&
            mimeType == "application/pdf"
        ) {
            return true
        }

        /*
         * Fallback to file extension.
         */
        return uri
            .toString()
            .endsWith(
                ".pdf",
                ignoreCase = true
            )
    }

    /**
     * Get display filename from URI.
     */
    private fun getFileName(
        uri: Uri
    ): String? {

        var fileName: String? = null

        try {

            when (uri.scheme) {

                "content" -> {

                    try {

                        contentResolver
                            .query(
                                uri,
                                null,
                                null,
                                null,
                                null
                            )
                            ?.use { cursor ->

                                if (
                                    cursor.moveToFirst()
                                ) {

                                    val nameIndex =
                                        cursor.getColumnIndex(
                                            OpenableColumns
                                                .DISPLAY_NAME
                                        )

                                    if (
                                        nameIndex >= 0
                                    ) {

                                        fileName =
                                            cursor.getString(
                                                nameIndex
                                            )
                                    }
                                }
                            }

                    } catch (e: SecurityException) {

                        Log.w(
                            TAG,
                            "SecurityException querying " +
                                    "file name: " +
                                    "${e.message}"
                        )
                    }
                }

                "file" -> {

                    fileName =
                        uri.lastPathSegment
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error parsing file name: " +
                        "${e.message}"
            )

            fileName =
                uri.lastPathSegment
        }

        return fileName
    }
}
