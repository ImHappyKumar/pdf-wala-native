package com.pdfwala.review

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Integration helper for easy setup of the in-app review system.
 * Provides a simplified API for common review system operations.
 *
 * Usage:
 * ```
 * // In your Application class
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ReviewIntegration.initialize(this)
 *     }
 * }
 *
 * // In your Activity - track feature usage
 * ReviewIntegration.trackPdfViewerUsage()
 * ReviewIntegration.trackMergeUsage()
 *
 * // Request review when appropriate
 * ReviewIntegration.requestReview(this) { success ->
 *     if (success) {
 *         // Review shown or Play Store opened
 *     }
 * }
 * ```
 */
object ReviewIntegration {

    private var isInitialized = false

    /**
     * Initialize the review system.
     * Should be called once in your Application.onCreate()
     *
     * @param application Your Application instance
     */
    fun initialize(application: android.app.Application) {
        if (isInitialized) return

        // Register lifecycle tracker for session time
        ReviewLifecycleTracker.register(application)

        isInitialized = true
        ReviewLogger.logDebug("ReviewIntegration initialized")
    }

    /**
     * Track PDF Viewer usage
     */
    fun trackPdfViewerUsage(context: android.content.Context) {
        UsageTracker.getInstance(context).trackPdfViewerUsage()
    }

    /**
     * Track PDF Merge usage
     */
    fun trackMergeUsage(context: android.content.Context) {
        UsageTracker.getInstance(context).trackMergeUsage()
    }

    /**
     * Track PDF Split usage
     */
    fun trackSplitUsage(context: android.content.Context) {
        UsageTracker.getInstance(context).trackSplitUsage()
    }

    /**
     * Track PDF Lock (password protect) usage
     */
    fun trackLockUsage(context: android.content.Context) {
        UsageTracker.getInstance(context).trackLockUsage()
    }

    /**
     * Track PDF Compress usage
     */
    fun trackCompressUsage(context: android.content.Context) {
        UsageTracker.getInstance(context).trackCompressUsage()
    }

    /**
     * Request a review if all conditions are met.
     *
     * @param activity Current activity for showing review dialog
     * @param onComplete Callback with true if review was shown/redirected, false otherwise
     */
    fun requestReview(activity: android.app.Activity, onComplete: (Boolean) -> Unit = {}) {
        ReviewManager.getInstance(activity).requestReview(activity, onComplete)
    }

    /**
     * Check if review should be requested based on current thresholds.
     * Useful for debugging or showing a pre-review UI.
     *
     * @param context Application context
     * @param callback Called with true if review should be requested
     */
    fun shouldRequestReview(
        context: android.content.Context,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val shouldRequest = ReviewManager.getInstance(context).shouldRequestReview()
            callback(shouldRequest)
        }
    }

    /**
     * Get current review statistics for debugging/analytics.
     *
     * @param context Application context
     * @param callback Called with review statistics
     */
    fun getReviewStats(
        context: android.content.Context,
        callback: (ReviewManager.ReviewStats) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val stats = ReviewManager.getInstance(context).getReviewStats()
            callback(stats)
        }
    }

    /**
     * Manually mark user as rated (e.g., from settings screen).
     * This will permanently suppress all future review prompts.
     *
     * @param context Application context
     */
    fun markAsRated(context: android.content.Context) {
        CoroutineScope(Dispatchers.Main).launch {
            ReviewManager.getInstance(context).markUserAsRated()
        }
    }

    /**
     * Check if user has already rated.
     *
     * @param context Application context
     * @param callback Called with true if user has rated
     */
    fun hasUserRated(
        context: android.content.Context,
        callback: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val hasRated = ReviewManager.getInstance(context).hasUserRated()
            callback(hasRated)
        }
    }

    /**
     * Set the log level for review system logging.
     *
     * @param level Desired log level
     */
    fun setLogLevel(level: ReviewLogger.LogLevel) {
        ReviewLogger.logLevel = level
    }

    /**
     * Reset all review data (for testing purposes).
     * WARNING: This will reset all usage tracking and review state.
     *
     * @param context Application context
     */
    fun resetAllData(context: android.content.Context) {
        CoroutineScope(Dispatchers.Main).launch {
            ReviewPreferences.getInstance(context).resetAll()
            UsageTracker.resetInstance()
            ReviewManager.resetInstance()
            ReviewLogger.logDebug("All review data reset")
        }
    }
}
