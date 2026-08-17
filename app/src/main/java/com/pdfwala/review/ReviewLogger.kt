package com.pdfwala.review

import android.util.Log

/**
 * Logger for review system events.
 * Provides structured logging for trigger conditions, flow success/failure.
 */
object ReviewLogger {

    private const val TAG = "InAppReview"

    /**
     * Log level for controlling verbosity
     */
    var logLevel: LogLevel = LogLevel.DEBUG

    enum class LogLevel {
        NONE,
        ERROR,
        INFO,
        DEBUG
    }

    /**
     * Log when a feature is used
     */
    fun logFeatureUsage(feature: ReviewPreferences.FeatureType) {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            Log.i(TAG, "Feature used: ${feature.name}")
        }
    }

    /**
     * Log trigger condition check
     */
    fun logTriggerCheck(
        featureUsage: Int,
        sessionTimeMs: Long,
        lastPromptMs: Long,
        hasRated: Boolean,
        shouldTrigger: Boolean
    ) {
        if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
            val daysSincePrompt = if (lastPromptMs > 0) {
                (System.currentTimeMillis() - lastPromptMs) / (1000 * 60 * 60 * 24)
            } else -1

            Log.d(TAG, """
                Trigger Check:
                - Feature Usage: $featureUsage (min: ${ReviewManager.MIN_FEATURE_USAGE})
                - Session Time: ${sessionTimeMs / 1000}s (min: ${ReviewManager.MIN_SESSION_TIME_MS / 1000}s)
                - Days Since Last Prompt: $daysSincePrompt (min: ${ReviewManager.DAYS_BETWEEN_PROMPTS})
                - Has Rated: $hasRated
                - Should Trigger: $shouldTrigger
            """.trimIndent())
        }
    }

    /**
     * Log when review flow is launched
     */
    fun logReviewFlowLaunched() {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            Log.i(TAG, "Review flow launched")
        }
    }

    /**
     * Log review flow success
     */
    fun logReviewFlowSuccess() {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            Log.i(TAG, "Review flow completed successfully")
        }
    }

    /**
     * Log review flow failure
     */
    fun logReviewFlowFailure(error: Exception) {
        if (logLevel.ordinal >= LogLevel.ERROR.ordinal) {
            Log.e(TAG, "Review flow failed: ${error.message}", error)
        }
    }

    /**
     * Log when redirecting to Play Store
     */
    fun logPlayStoreRedirect() {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            Log.i(TAG, "Redirecting to Play Store")
        }
    }

    /**
     * Log when user has already rated
     */
    fun logAlreadyRated() {
        if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
            Log.d(TAG, "User has already rated - skipping review prompt")
        }
    }

    /**
     * Log when review is suppressed (cooldown period)
     */
    fun logCooldown(daysRemaining: Long) {
        if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
            Log.d(TAG, "Review suppressed - cooldown period: $daysRemaining days remaining")
        }
    }

    /**
     * Log threshold not met
     */
    fun logThresholdNotMet(reason: String) {
        if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
            Log.d(TAG, "Review threshold not met: $reason")
        }
    }

    /**
     * Log general debug message
     */
    fun logDebug(message: String) {
        if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
            Log.d(TAG, message)
        }
    }

    /**
     * Log error
     */
    fun logError(message: String, error: Throwable? = null) {
        if (logLevel.ordinal >= LogLevel.ERROR.ordinal) {
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.e(TAG, message)
            }
        }
    }

    /**
     * Log user marked as rated (permanent suppression)
     */
    fun logUserMarkedAsRated() {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            Log.i(TAG, "User marked as rated - all future prompts suppressed")
        }
    }
}
