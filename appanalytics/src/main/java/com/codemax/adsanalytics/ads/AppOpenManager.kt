package com.codemax.adsanalytics.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.codemax.adsanalytics.AdEvent
import com.codemax.adsanalytics.AdEventType
import com.codemax.adsanalytics.AdFormat
import com.codemax.adsanalytics.OnComplete
import com.codemax.adsanalytics.analytics.ActivityTracker
import com.codemax.adsanalytics.analytics.AdEventBus
import com.codemax.adsanalytics.analytics.Analytics
import com.codemax.adsanalytics.config.RemoteConfig
import com.codemax.adsanalytics.config.RemoteKeys
import com.codemax.adsanalytics.internal.Sdk
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * App-open ad shown when the app returns from background (resume ad). Expires after 4 hours.
 * Skipped on excluded activities ([com.codemax.adsanalytics.AdsAnalyticsConfig.appOpenExcludedActivities]),
 * after [skipNextResume], on screens opened with [EXTRA_NO_RESUME_AD], and when Remote Config
 * "is_resume_ad_enabled" or "resume_ad_enabled" is false.
 */
object AppOpenManager : DefaultLifecycleObserver {
    private const val EXPIRY_MS = 4 * 60 * 60 * 1000L
    private const val SCREEN = "app_open_screen"
    private const val POSITION = "app_open_screen:fullscreen"

    /** Intent extra: the screen was opened from a notification button, so no resume ad on top of it (once). */
    const val EXTRA_NO_RESUME_AD = "no_resume_ad"

    private var ad: AppOpenAd? = null
    private var adUnit = ""
    private var loadedAt = 0L
    private var loading = false
    private var wentBackground = false
    private var skipNextResume = false
    private var registered = false

    internal fun register(app: Application) {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun emit(type: AdEventType, network: String?, error: AdError? = null) =
        AdEventBus.dispatch(AdEvent(type, AdFormat.APP_OPEN, adUnit.ifEmpty { AdsManager.appOpenUnitId }, network, SCREEN, POSITION,
            errorCode = error?.code, errorMessage = error?.message))

    /** Preloads an app-open ad (call e.g. when leaving the splash screen). */
    @JvmStatic
    fun load(context: Context) {
        if (loading || isAvailable() || !AdsManager.canShowAds(context)) return
        loading = true
        val unitId = AdsManager.appOpenUnitId
        adUnit = unitId
        emit(AdEventType.LOAD_REQUESTED, null)
        AppOpenAd.load(context.applicationContext, unitId, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
                    val net = AdsManager.adNetworkName(loaded.responseInfo)
                    loaded.setOnPaidEventListener { value -> Analytics.logAdRevenue(unitId, "open", value, net, SCREEN, POSITION) }
                    ad = loaded; loadedAt = SystemClock.elapsedRealtime(); loading = false
                    emit(AdEventType.LOADED, net)
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    emit(AdEventType.FAILED_TO_LOAD, null, error)
                }
            })
    }

    @JvmStatic
    fun isAvailable(): Boolean = ad != null && SystemClock.elapsedRealtime() - loadedAt < EXPIRY_MS

    /** Shows the ad if one is ready; [onDone] runs when it closes, or immediately if none. */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity, onDone: OnComplete? = null) {
        val a = ad
        if (a == null || !isAvailable() || AdsManager.isFullScreenAdShowing || !AdsManager.canShowAds(activity)) {
            load(activity); onDone?.onComplete(); return
        }
        ad = null
        val net = AdsManager.adNetworkName(a.responseInfo)
        val unitId = adUnit
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() = emit(AdEventType.SHOWN, net)
            override fun onAdImpression() = emit(AdEventType.IMPRESSION, net)
            override fun onAdDismissedFullScreenContent() {
                AdsManager.isFullScreenAdShowing = false
                emit(AdEventType.DISMISSED, net)
                load(activity); onDone?.onComplete()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdsManager.isFullScreenAdShowing = false
                emit(AdEventType.FAILED_TO_SHOW, net, error)
                load(activity); onDone?.onComplete()
            }
            override fun onAdClicked() = Analytics.logAdClick(unitId, AdFormat.APP_OPEN, net, SCREEN, POSITION)
        }
        AdsManager.isFullScreenAdShowing = true
        a.show(activity)
    }

    /** Don't show the resume ad when the user comes back from a screen the app itself opened (e.g. system Settings). */
    @JvmStatic
    fun skipNextResume() { skipNextResume = true }

    override fun onStop(owner: LifecycleOwner) { wentBackground = true }

    override fun onStart(owner: LifecycleOwner) {
        if (!wentBackground) return
        wentBackground = false
        if (skipNextResume) { skipNextResume = false; return }
        if (!Sdk.config.appOpenAdsEnabled) return
        if (!RemoteConfig.bool(RemoteKeys.IS_RESUME_AD_ENABLED, true) || !RemoteConfig.bool(RemoteKeys.RESUME_AD_ENABLED, true)) return
        // Wait for the screen that actually comes to the front (a launcher splash may finish at once).
        Sdk.mainHandler.postDelayed({
            val activity = ActivityTracker.currentActivity ?: return@postDelayed
            if (activity.isFinishing || activity is InHouseAdActivity) return@postDelayed
            if (Sdk.config.appOpenExcludedActivities.any { it.isInstance(activity) }) return@postDelayed
            val intent = activity.intent
            if (intent?.getBooleanExtra(EXTRA_NO_RESUME_AD, false) == true) {
                intent.removeExtra(EXTRA_NO_RESUME_AD)
                return@postDelayed
            }
            show(activity)
        }, 400)
    }
}
