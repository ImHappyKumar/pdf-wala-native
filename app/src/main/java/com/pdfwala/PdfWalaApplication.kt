package com.pdfwala

import android.app.Application
import android.util.Log
import com.pdfwala.config.RemoteConfigManager
import com.pdfwala.review.ReviewIntegration
import com.pdfwala.util.CacheManager
//import com.pdfwala.util.LanguageManager
import com.pdfwala.util.ThemeManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application class for PDF Wala.
 *
 * Handles application-level initialization:
 * - PDFBox
 * - In-App Review
 * - Theme
 * - Firebase Remote Config
 * - Cache cleanup
 */
class PdfWalaApplication : Application() {

    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single Remote Config instance for the whole application.
     *
     * AppUpdateManager and other components should use this instance
     * instead of creating their own RemoteConfigManager.
     */
    lateinit var remoteConfigManager: RemoteConfigManager
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize PDFBox-Android
        PDFBoxResourceLoader.init(applicationContext)

        // Initialize In-App Review system for session tracking
        ReviewIntegration.initialize(this)

        // Initialize Remote Config centrally
        remoteConfigManager = RemoteConfigManager(applicationContext)

        /*
         * Fetch Remote Config once when the application starts.
         *
         * RemoteConfigManager is configured with Firebase's minimum
         * fetch interval, so this does NOT mean a network request
         * happens on every app launch.
         */
        remoteConfigManager.fetchAndActivate { success ->
            Log.d(
                "PdfToolkit",
                "Remote Config fetch completed. success=$success"
            )
        }

        // Initialize theme synchronously to avoid flicker
        runBlocking {
            val themeMode =
                ThemeManager.getThemeMode(applicationContext).first()

            ThemeManager.applyTheme(themeMode)

            Log.d(
                "PdfToolkit",
                "Theme initialized: $themeMode"
            )
        }

        // Initialize language
        // LanguageManager.initializeLanguage(applicationContext)
        // Log.d(
        //     "PdfToolkit",
        //     "Language initialized: ${LanguageManager.getCurrentLanguage()}"
        // )

        // Auto-clean cache on startup (runs in background)
        applicationScope.launch {
            try {
                // Clean old cache files (older than 24 hours)
                val cleanedBytes =
                    CacheManager.clearOldCache(applicationContext)

                if (cleanedBytes > 0) {
                    Log.d(
                        "PdfToolkit",
                        "Auto-cleaned ${cleanedBytes / 1024} KB from cache"
                    )
                }

                // Also clean PDF operation temp files
                CacheManager.clearPdfOperationsCache(applicationContext)

            } catch (e: Exception) {
                Log.e(
                    "PdfToolkit",
                    "Cache cleanup failed",
                    e
                )
            }
        }
    }
}