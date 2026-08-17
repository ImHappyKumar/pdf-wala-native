package com.pdfwala.review

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks feature usage and session time for the in-app review system.
 * Uses atomic operations for thread-safe counter updates.
 */
class UsageTracker private constructor(context: Context) {

    private val reviewPreferences = ReviewPreferences.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Session tracking
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val accumulatedSessionTime = AtomicLong(0L)
    private val isTracking = AtomicBoolean(true)
    private val isAppInForeground = AtomicBoolean(true)

    init {
        // Start periodic session time persistence
        startSessionPersistence()
    }

    /**
     * Track usage of a specific feature
     * @param feature The feature being used
     */
    fun trackFeatureUsage(feature: ReviewPreferences.FeatureType) {
        if (!isTracking.get()) return

        scope.launch {
            reviewPreferences.incrementFeatureUsage(feature)
            ReviewLogger.logFeatureUsage(feature)
        }
    }

    /**
     * Track PDF viewer usage
     */
    fun trackPdfViewerUsage() = trackFeatureUsage(ReviewPreferences.FeatureType.PDF_VIEWER)

    /**
     * Track PDF merge usage
     */
    fun trackMergeUsage() = trackFeatureUsage(ReviewPreferences.FeatureType.MERGE)

    /**
     * Track PDF split usage
     */
    fun trackSplitUsage() = trackFeatureUsage(ReviewPreferences.FeatureType.SPLIT)

    /**
     * Track PDF lock (password protect) usage
     */
    fun trackLockUsage() = trackFeatureUsage(ReviewPreferences.FeatureType.LOCK)

    /**
     * Track PDF compress usage
     */
    fun trackCompressUsage() = trackFeatureUsage(ReviewPreferences.FeatureType.COMPRESS)

    /**
     * Called when app goes to foreground - resumes session tracking
     */
    fun onAppForeground() {
        isAppInForeground.set(true)
        sessionStartTime.set(System.currentTimeMillis())
        ReviewLogger.logDebug("App in foreground, session tracking resumed")
    }

    /**
     * Called when app goes to background - saves accumulated session time
     */
    fun onAppBackground() {
        isAppInForeground.set(false)
        val currentSession = System.currentTimeMillis() - sessionStartTime.get()
        if (currentSession > 0) {
            accumulatedSessionTime.addAndGet(currentSession)
        }

        // Persist immediately on background
        scope.launch {
            val accumulated = accumulatedSessionTime.getAndSet(0L)
            if (accumulated > 0) {
                reviewPreferences.addSessionTime(accumulated)
                ReviewLogger.logDebug("Saved session time: ${accumulated / 1000}s")
            }
        }
    }

    /**
     * Get current total session time including active session
     */
    suspend fun getCurrentTotalSessionTimeMs(): Long {
        val persisted = reviewPreferences.getReviewData().totalSessionTimeMs
        val active = if (isAppInForeground.get()) {
            System.currentTimeMillis() - sessionStartTime.get()
        } else 0L
        return persisted + accumulatedSessionTime.get() + active
    }

    /**
     * Get current feature usage counts
     */
    suspend fun getFeatureUsage(): ReviewPreferences.ReviewData {
        return reviewPreferences.getReviewData()
    }

    /**
     * Stop tracking and cleanup resources
     */
    fun stopTracking() {
        isTracking.set(false)

        // Save any remaining session time
        if (isAppInForeground.get()) {
            val currentSession = System.currentTimeMillis() - sessionStartTime.get()
            if (currentSession > 0) {
                accumulatedSessionTime.addAndGet(currentSession)
            }
        }

        scope.launch {
            val remaining = accumulatedSessionTime.getAndSet(0L)
            if (remaining > 0) {
                reviewPreferences.addSessionTime(remaining)
            }
            scope.cancel()
        }
    }

    /**
     * Start periodic persistence of session time
     */
    private fun startSessionPersistence() {
        scope.launch {
            while (isActive && isTracking.get()) {
                delay(SESSION_PERSIST_INTERVAL_MS)

                if (isAppInForeground.get()) {
                    val currentSession = System.currentTimeMillis() - sessionStartTime.get()
                    if (currentSession > 0) {
                        accumulatedSessionTime.addAndGet(currentSession)
                        sessionStartTime.set(System.currentTimeMillis())
                    }
                }

                val accumulated = accumulatedSessionTime.getAndSet(0L)
                if (accumulated > 0) {
                    reviewPreferences.addSessionTime(accumulated)
                }
            }
        }
    }

    /**
     * Reset session time (called after review prompt)
     */
    suspend fun resetSessionTime() {
        accumulatedSessionTime.set(0L)
        sessionStartTime.set(System.currentTimeMillis())
        reviewPreferences.resetSessionTime()
    }

    companion object {
        private const val SESSION_PERSIST_INTERVAL_MS = 30000L // 30 seconds

        @Volatile
        private var instance: UsageTracker? = null

        fun getInstance(context: Context): UsageTracker {
            return instance ?: synchronized(this) {
                instance ?: UsageTracker(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * For testing - resets the singleton instance
         */
        internal fun resetInstance() {
            instance?.stopTracking()
            instance = null
        }
    }
}
