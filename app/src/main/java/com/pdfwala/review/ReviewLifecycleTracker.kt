package com.pdfwala.review

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application lifecycle callbacks handler for session tracking.
 * Tracks when app goes to foreground/background to accurately measure session time.
 *
 * Usage: Register in your Application.onCreate()
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ReviewLifecycleTracker.register(this)
 *     }
 * }
 * ```
 */
class ReviewLifecycleTracker private constructor(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    private var activityCount = 0
    private val usageTracker = UsageTracker.getInstance(application)
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        activityCount++
        if (activityCount == 1) {
            // App is coming to foreground
            usageTracker.onAppForeground()
            ReviewLogger.logDebug("App entered foreground")
        }
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        activityCount--
        if (activityCount == 0) {
            // App is going to background
            usageTracker.onAppBackground()
            ReviewLogger.logDebug("App entered background")
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        @Volatile
        private var isRegistered = false

        /**
         * Register the lifecycle tracker with the application.
         * Safe to call multiple times - will only register once.
         *
         * @param application Your Application instance
         */
        fun register(application: Application) {
            if (isRegistered) return

            synchronized(this) {
                if (isRegistered) return

                val tracker = ReviewLifecycleTracker(application)
                application.registerActivityLifecycleCallbacks(tracker)
                isRegistered = true

                ReviewLogger.logDebug("ReviewLifecycleTracker registered")
            }
        }

        /**
         * Unregister the lifecycle tracker (for testing)
         */
        internal fun unregister(application: Application) {
            synchronized(this) {
                // Note: We can't easily unregister without keeping a reference
                // to the tracker instance. For testing, it's better to reset state.
                isRegistered = false
            }
        }
    }
}
