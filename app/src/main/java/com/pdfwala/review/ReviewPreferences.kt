package com.pdfwala.review

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe SharedPreferences wrapper for review-related data storage.
 * Stores feature usage counts, session time, and review prompt state.
 */
class ReviewPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val mutex = Mutex()

    /**
     * Data class representing all review-related preferences
     */
    data class ReviewData(
        val pdfViewerUsage: Int,
        val mergeUsage: Int,
        val splitUsage: Int,
        val lockUsage: Int,
        val compressUsage: Int,
        val totalSessionTimeMs: Long,
        val lastReviewPromptTimestamp: Long,
        val hasRated: Boolean
    ) {
        companion object {
            val DEFAULT = ReviewData(0, 0, 0, 0, 0, 0L, 0L, false)
        }

        /**
         * Get usage count for a specific feature
         */
        fun getFeatureUsage(feature: FeatureType): Int = when (feature) {
            FeatureType.PDF_VIEWER -> pdfViewerUsage
            FeatureType.MERGE -> mergeUsage
            FeatureType.SPLIT -> splitUsage
            FeatureType.LOCK -> lockUsage
            FeatureType.COMPRESS -> compressUsage
        }

        /**
         * Get total feature usage across all features
         */
        fun getTotalFeatureUsage(): Int = pdfViewerUsage + mergeUsage + splitUsage + lockUsage + compressUsage
    }

    /**
     * Enum representing trackable features
     */
    enum class FeatureType {
        PDF_VIEWER,
        MERGE,
        SPLIT,
        LOCK,
        COMPRESS
    }

    /**
     * Get all review data in a thread-safe manner
     */
    suspend fun getReviewData(): ReviewData = mutex.withLock {
        withContext(Dispatchers.IO) {
            ReviewData(
                pdfViewerUsage = prefs.getInt(KEY_PDF_VIEWER_USAGE, 0),
                mergeUsage = prefs.getInt(KEY_MERGE_USAGE, 0),
                splitUsage = prefs.getInt(KEY_SPLIT_USAGE, 0),
                lockUsage = prefs.getInt(KEY_LOCK_USAGE, 0),
                compressUsage = prefs.getInt(KEY_COMPRESS_USAGE, 0),
                totalSessionTimeMs = prefs.getLong(KEY_TOTAL_SESSION_TIME_MS, 0L),
                lastReviewPromptTimestamp = prefs.getLong(KEY_LAST_REVIEW_PROMPT, 0L),
                hasRated = prefs.getBoolean(KEY_HAS_RATED, false)
            )
        }
    }

    /**
     * Increment usage count for a specific feature
     */
    suspend fun incrementFeatureUsage(feature: FeatureType) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                val key = when (feature) {
                    FeatureType.PDF_VIEWER -> KEY_PDF_VIEWER_USAGE
                    FeatureType.MERGE -> KEY_MERGE_USAGE
                    FeatureType.SPLIT -> KEY_SPLIT_USAGE
                    FeatureType.LOCK -> KEY_LOCK_USAGE
                    FeatureType.COMPRESS -> KEY_COMPRESS_USAGE
                }
                putInt(key, prefs.getInt(key, 0) + 1)
            }
        }
    }

    /**
     * Add session time to total (typically called when app goes to background)
     */
    suspend fun addSessionTime(sessionTimeMs: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putLong(KEY_TOTAL_SESSION_TIME_MS, prefs.getLong(KEY_TOTAL_SESSION_TIME_MS, 0L) + sessionTimeMs)
            }
        }
    }

    /**
     * Reset total session time (e.g., after review prompt)
     */
    suspend fun resetSessionTime() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putLong(KEY_TOTAL_SESSION_TIME_MS, 0L)
            }
        }
    }

    /**
     * Mark that a review prompt was shown
     */
    suspend fun markReviewPromptShown() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putLong(KEY_LAST_REVIEW_PROMPT, System.currentTimeMillis())
            }
        }
    }

    /**
     * Mark that user has completed rating
     */
    suspend fun markAsRated() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putBoolean(KEY_HAS_RATED, true)
            }
        }
    }

    /**
     * Reset all review data (for testing purposes)
     */
    suspend fun resetAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                clear()
            }
        }
    }

    /**
     * For testing: Set last review prompt timestamp directly
     */
    internal suspend fun setLastReviewPromptForTesting(timestamp: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putLong(KEY_LAST_REVIEW_PROMPT, timestamp)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "review_preferences"

        private const val KEY_PDF_VIEWER_USAGE = "pdf_viewer_usage"
        private const val KEY_MERGE_USAGE = "merge_usage"
        private const val KEY_SPLIT_USAGE = "split_usage"
        private const val KEY_LOCK_USAGE = "lock_usage"
        private const val KEY_COMPRESS_USAGE = "compress_usage"
        private const val KEY_TOTAL_SESSION_TIME_MS = "total_session_time_ms"
        private const val KEY_LAST_REVIEW_PROMPT = "last_review_prompt"
        private const val KEY_HAS_RATED = "has_rated"

        @Volatile
        private var instance: ReviewPreferences? = null

        fun getInstance(context: Context): ReviewPreferences {
            return instance ?: synchronized(this) {
                instance ?: ReviewPreferences(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * For testing - resets the singleton instance
         */
        internal fun resetInstance() {
            instance = null
        }
    }
}
