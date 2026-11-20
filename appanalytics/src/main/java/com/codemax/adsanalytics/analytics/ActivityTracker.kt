package com.codemax.adsanalytics.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.codemax.adsanalytics.ads.AdsManager
import com.codemax.adsanalytics.ads.InHouseAdActivity
import com.codemax.adsanalytics.internal.Sdk
import com.google.android.gms.ads.AdActivity
import java.lang.ref.WeakReference

/**
 * Activity lifecycle tracking: which screen is in front (used for resume ads and the pending
 * full-screen native), optional Firebase `screen_view` events, and a running count of started screens.
 */
object ActivityTracker : Application.ActivityLifecycleCallbacks {
    private var currentRef: WeakReference<Activity>? = null
    private var resumedRef: WeakReference<Activity>? = null
    private var startedCount = 0

    /** Activity currently started (excluding the ad SDK's own AdActivity). */
    @JvmStatic val currentActivity: Activity? get() = currentRef?.get()

    /** Activity currently resumed (null while switching screens). */
    @JvmStatic val resumedActivity: Activity? get() = resumedRef?.get()

    /** True while at least one activity of the app is started. */
    @JvmStatic val isAppInForeground: Boolean get() = startedCount > 0

    internal fun register(app: Application) {
        app.unregisterActivityLifecycleCallbacks(this)
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun isSdkScreen(a: Activity) = a is AdActivity || a is InHouseAdActivity

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        if (activity !is AdActivity) currentRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentRef = WeakReference(activity)
        if (activity is AdActivity) return
        resumedRef = WeakReference(activity)
        if (isSdkScreen(activity)) return
        if (Sdk.config.trackScreenViews) {
            Analytics.logEvent("screen_view", Bundle().apply {
                putString("screen_name", activity.javaClass.simpleName)
                putString("screen_class", activity.javaClass.name)
            })
        }
        // A screen reached after an interstitial shows the pending full-screen native.
        activity.window?.decorView?.post { if (!activity.isFinishing) AdsManager.showPendingNativeFull(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityStopped(activity: Activity) { if (startedCount > 0) startedCount-- }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
