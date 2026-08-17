package com.pdfwala.review

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages the in-app review flow using Google Play In-App Review API.
 * Handles trigger conditions, review prompts, and fallback to Play Store.
 */
class ReviewManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val reviewPreferences = ReviewPreferences.getInstance(context)
    private val usageTracker = UsageTracker.getInstance(context)

    // Play Store review manager (only available on Play Store flavor)
    private val playReviewManager by lazy {
        if (isGooglePlayAvailable()) {
            ReviewManagerFactory.create(appContext)
        } else {
            null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Check if Google Play Services are available.
     */
    private fun isGooglePlayAvailable(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val result = availability.isGooglePlayServicesAvailable(appContext)
        return result == ConnectionResult.SUCCESS
    }

    /**
     * Check if review should be triggered based on all conditions:
     * - Feature used >= MIN_FEATURE_USAGE times
     * - Session time >= MIN_SESSION_TIME_MS
     * - Last prompt >= DAYS_BETWEEN_PROMPTS days ago
     * - User has not rated
     *
     * @return true if review should be shown
     */
    suspend fun shouldRequestReview(): Boolean = withContext(Dispatchers.IO) {
        val data = reviewPreferences.getReviewData()

        // If user has already rated, never show again.
        if (data.hasRated) {
            ReviewLogger.logAlreadyRated()
            return@withContext false
        }

        val currentTime = System.currentTimeMillis()

        // Check cooldown period.
        if (data.lastReviewPromptTimestamp > 0) {
            val daysSinceLastPrompt =
                (currentTime - data.lastReviewPromptTimestamp) / (1000 * 60 * 60 * 24)

            if (daysSinceLastPrompt < DAYS_BETWEEN_PROMPTS) {
                ReviewLogger.logCooldown(
                    DAYS_BETWEEN_PROMPTS - daysSinceLastPrompt
                )
                return@withContext false
            }
        }

        // Get current total feature usage across all features.
        val totalUsage = data.getTotalFeatureUsage()

        // Get current total session time.
        val sessionTimeMs = usageTracker.getCurrentTotalSessionTimeMs()

        // Check if thresholds are met.
        val hasEnoughUsage = totalUsage >= MIN_FEATURE_USAGE
        val hasEnoughSessionTime = sessionTimeMs >= MIN_SESSION_TIME_MS

        // Log the check.
        val shouldTrigger = hasEnoughUsage && hasEnoughSessionTime

        ReviewLogger.logTriggerCheck(
            totalUsage,
            sessionTimeMs,
            data.lastReviewPromptTimestamp,
            data.hasRated,
            shouldTrigger
        )

        // Log specific thresholds not met.
        if (!hasEnoughUsage) {
            ReviewLogger.logThresholdNotMet(
                "Feature usage: $totalUsage < $MIN_FEATURE_USAGE"
            )
        }

        if (!hasEnoughSessionTime) {
            ReviewLogger.logThresholdNotMet(
                "Session time: ${sessionTimeMs / 1000}s < ${MIN_SESSION_TIME_MS / 1000}s"
            )
        }

        shouldTrigger
    }

    /**
     * Request a review flow. Call this when the user completes a meaningful action.
     * The review flow will only show if all conditions are met.
     *
     * @param activity The current activity for showing the review dialog.
     * @param onComplete Called when the review flow completes (success or failure).
     */
    fun requestReview(
        activity: Activity,
        onComplete: (Boolean) -> Unit = {}
    ) {
        scope.launch {
            try {
                if (!shouldRequestReview()) {
                    onComplete(false)
                    return@launch
                }

                // Mark that we're showing the prompt (prevents spam).
                reviewPreferences.markReviewPromptShown()

                // Try to show in-app review.
                val success = showInAppReview(activity)

                if (success) {
                    // Mark as rated to prevent future prompts.
                    markAsRated()
                    onComplete(true)
                } else {
                    // Fallback to Play Store.
                    redirectToPlayStore(activity)
                    markAsRated()
                    onComplete(true)
                }
            } catch (e: Exception) {
                ReviewLogger.logError("Error in requestReview", e)
                onComplete(false)
            }
        }
    }

    /**
     * Request review with suspend function for coroutine usage.
     *
     * @param activity The current activity.
     * @return true if review was shown or redirected, false otherwise.
     */
    suspend fun requestReviewSuspend(activity: Activity): Boolean =
        withContext(Dispatchers.Main) {
            try {
                if (!shouldRequestReview()) {
                    return@withContext false
                }

                // Mark that we're showing the prompt.
                reviewPreferences.markReviewPromptShown()

                // Try to show in-app review.
                val success = showInAppReview(activity)

                if (success) {
                    markAsRated()
                    true
                } else {
                    redirectToPlayStore(activity)
                    markAsRated()
                    true
                }
            } catch (e: Exception) {
                ReviewLogger.logError("Error in requestReviewSuspend", e)
                false
            }
        }

    /**
     * Show the in-app review dialog using Google Play API.
     *
     * @param activity The current activity.
     * @return true if flow was launched successfully, false otherwise.
     */
    private suspend fun showInAppReview(activity: Activity): Boolean {
        val manager = playReviewManager ?: run {
            ReviewLogger.logDebug("Play Review Manager not available")
            return false
        }

        return withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
            try {
                // Request review info.
                val reviewInfo = requestReviewInfo(manager)

                if (reviewInfo == null) {
                    ReviewLogger.logReviewFlowFailure(
                        Exception("Failed to get review info")
                    )
                    return@withTimeoutOrNull false
                }

                // Launch the review flow.
                ReviewLogger.logReviewFlowLaunched()

                val result = launchReviewFlow(
                    activity,
                    manager,
                    reviewInfo
                )

                if (result) {
                    ReviewLogger.logReviewFlowSuccess()
                }

                result
            } catch (e: ReviewException) {
                ReviewLogger.logReviewFlowFailure(e)
                false
            } catch (e: Exception) {
                ReviewLogger.logReviewFlowFailure(e)
                false
            }
        } ?: false
    }

    /**
     * Request review info from Play Store.
     */
    private suspend fun requestReviewInfo(
        manager: com.google.android.play.core.review.ReviewManager
    ): ReviewInfo? = suspendCancellableCoroutine { continuation ->

        val request = manager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                val exception =
                    task.exception ?: Exception("Unknown error getting review info")

                ReviewLogger.logError(
                    "Failed to get review info",
                    exception
                )

                continuation.resume(null)
            }
        }
    }

    /**
     * Launch the review flow.
     */
    private suspend fun launchReviewFlow(
        activity: Activity,
        manager: com.google.android.play.core.review.ReviewManager,
        reviewInfo: ReviewInfo
    ): Boolean = suspendCancellableCoroutine { continuation ->

        val flow = manager.launchReviewFlow(
            activity,
            reviewInfo
        )

        flow.addOnCompleteListener {
            // The flow doesn't provide success/failure information directly.
            // We assume success if the completion callback is reached.
            continuation.resume(true)
        }
    }

    /**
     * Redirect user to Play Store for manual rating.
     *
     * @param activity The current activity.
     */
    private fun redirectToPlayStore(activity: Activity) {
        ReviewLogger.logPlayStoreRedirect()

        val packageName = appContext.packageName

        val uri = Uri.parse(
            "market://details?id=$packageName"
        )

        val intent = Intent(
            Intent.ACTION_VIEW,
            uri
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }

        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Fallback to web browser if Play Store is not available.
            val webUri = Uri.parse(
                "https://play.google.com/store/apps/details?id=$packageName"
            )

            val webIntent = Intent(
                Intent.ACTION_VIEW,
                webUri
            )

            activity.startActivity(webIntent)
        }
    }

    /**
     * Mark user as rated to permanently suppress review prompts.
     */
    private suspend fun markAsRated() {
        reviewPreferences.markAsRated()
        usageTracker.resetSessionTime()
        ReviewLogger.logUserMarkedAsRated()
    }

    /**
     * Manually mark user as rated (e.g., from settings screen).
     */
    suspend fun markUserAsRated() {
        markAsRated()
    }

    /**
     * Check if user has already rated.
     */
    suspend fun hasUserRated(): Boolean {
        return reviewPreferences.getReviewData().hasRated
    }

    /**
     * Get current review statistics (for debugging/analytics).
     */
    suspend fun getReviewStats(): ReviewStats =
        withContext(Dispatchers.IO) {

            val data = reviewPreferences.getReviewData()
            val sessionTime = usageTracker.getCurrentTotalSessionTimeMs()

            ReviewStats(
                pdfViewerUsage = data.pdfViewerUsage,
                mergeUsage = data.mergeUsage,
                splitUsage = data.splitUsage,
                lockUsage = data.lockUsage,
                compressUsage = data.compressUsage,
                totalFeatureUsage = data.getTotalFeatureUsage(),
                totalSessionTimeMs = sessionTime,
                totalSessionTimeMinutes = sessionTime / (1000 * 60),
                lastReviewPromptTimestamp = data.lastReviewPromptTimestamp,
                hasRated = data.hasRated,

                // IMPORTANT:
                // compareTo() returns Int, not Boolean.
                canRequestReview =
                    !data.hasRated &&
                            data.getTotalFeatureUsage() >= MIN_FEATURE_USAGE &&
                            sessionTime >= MIN_SESSION_TIME_MS
            )
        }

    /**
     * Data class containing review statistics.
     */
    data class ReviewStats(
        val pdfViewerUsage: Int,
        val mergeUsage: Int,
        val splitUsage: Int,
        val lockUsage: Int,
        val compressUsage: Int,
        val totalFeatureUsage: Int,
        val totalSessionTimeMs: Long,
        val totalSessionTimeMinutes: Long,
        val lastReviewPromptTimestamp: Long,
        val hasRated: Boolean,
        val canRequestReview: Boolean
    )

    companion object {

        // Trigger thresholds.
        const val MIN_FEATURE_USAGE = 3
        const val MIN_SESSION_TIME_MS = 10 * 60 * 1000L
        const val DAYS_BETWEEN_PROMPTS = 3L

        // Timeout for review flow.
        private const val REVIEW_TIMEOUT_MS = 10000L

        @Volatile
        private var instance: ReviewManager? = null

        fun getInstance(context: Context): ReviewManager {
            return instance ?: synchronized(this) {
                instance ?: ReviewManager(
                    context.applicationContext
                ).also {
                    instance = it
                }
            }
        }

        /**
         * For testing - resets the singleton instance.
         */
        internal fun resetInstance() {
            instance = null
        }
    }
}