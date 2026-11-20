package com.codemax.adsanalytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.codemax.adsanalytics.ads.AdsManager
import com.codemax.adsanalytics.ads.AppOpenManager
import com.codemax.adsanalytics.ads.ConsentManager
import com.codemax.adsanalytics.analytics.ActivityTracker
import com.codemax.adsanalytics.analytics.AdEventBus
import com.codemax.adsanalytics.analytics.Analytics
import com.codemax.adsanalytics.analytics.Bridges
import com.codemax.adsanalytics.analytics.FacebookBridge
import com.codemax.adsanalytics.analytics.FirebaseBridge
import com.codemax.adsanalytics.attribution.InstallAttribution
import com.codemax.adsanalytics.config.RemoteConfig
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import com.codemax.adsanalytics.panel.PanelClient

/**
 * Entry point of the Ads & Analytics SDK.
 *
 * ```kotlin
 * // Application.onCreate
 * AdsAnalytics.initialize(this, AdsAnalyticsConfig.Builder()
 *     .panel("https://panel.example.com", "API_KEY")
 *     .defaultAdUnits(AdsAnalyticsConfig.AdUnits.GOOGLE_TEST)
 *     .excludeFromAppOpen(SplashActivity::class.java)
 *     .build())
 *
 * // Splash
 * AdsAnalytics.gatherConsentAndInitAds(this) { AdsManager.showSplashInterstitial(this) { goNext() } }
 * ```
 *
 * Detailed APIs live in [AdsManager], [AppOpenManager], [ConsentManager], [Analytics], [PanelClient],
 * [RemoteConfig], [InstallAttribution] and [ActivityTracker].
 */
object AdsAnalytics {
    private const val TAG = "SDK"

    /** SDK version name. */
    @JvmStatic val version: String get() = BuildConfig.SDK_VERSION

    @JvmStatic val isInitialized: Boolean get() = Sdk.isInitialized

    @JvmStatic val config: AdsAnalyticsConfig get() = Sdk.config

    /**
     * Initialises everything that doesn't need an Activity. Call once from Application.onCreate:
     * Remote Config defaults, panel config fetch, Firebase push topics, Facebook App Events,
     * install attribution followed by the panel launch ping, activity tracking and resume ads.
     * Also initialises Mobile Ads right away when consent was already given in an earlier session.
     */
    @JvmStatic
    fun initialize(application: Application, config: AdsAnalyticsConfig) {
        if (Sdk.isInitialized) { SdkLog.w(TAG, "initialize() called twice; ignored"); return }
        Sdk.setup(application, config)
        SdkLog.i(TAG, "AdsAnalytics $version initialising")

        RemoteConfig.init(application, config.localConfigAsset)
        PanelClient.init(application)

        if (config.firebasePushTopics && FirebaseBridge.isAvailable(application)) FirebaseBridge.subscribeTopics(config.debug)
        initFacebook(application, config)

        InstallAttribution.init(application) {
            PanelClient.sendOpen(source = InstallAttribution.source(), campaign = InstallAttribution.campaign())
        }

        ActivityTracker.register(application)
        AppOpenManager.register(application)
        // After process death Android restores the last screen without the splash; consent obtained
        // earlier is persisted by UMP, so ads can be initialised here (no-op without consent).
        AdsManager.initialize(application)
    }

    private fun initFacebook(app: Application, config: AdsAnalyticsConfig) {
        if (!Bridges.hasFacebook) return
        val appId = PanelClient.facebookId ?: config.facebookAppId
        if (appId.isNullOrEmpty()) return
        FacebookBridge.init(app, appId, PanelClient.facebookToken ?: config.facebookClientToken)
    }

    /**
     * Runs the UMP consent flow, then initialises Mobile Ads. [onReady] runs once on the main thread,
     * whether or not ads can be shown — check [AdsManager.canShowAds] if needed.
     */
    @JvmStatic
    fun gatherConsentAndInitAds(activity: Activity, onReady: OnComplete) {
        ConsentManager.gather(activity) { AdsManager.initialize(activity, onReady) }
    }

    /** Registers a listener for every ad lifecycle event (loads, impressions, clicks, paid events...). */
    @JvmStatic fun addAdEventListener(listener: AdEventListener) = AdEventBus.add(listener)

    @JvmStatic fun removeAdEventListener(listener: AdEventListener) = AdEventBus.remove(listener)

    /** Shortcut for [Analytics.logEvent]. */
    @JvmStatic
    @JvmOverloads
    fun logEvent(name: String, params: Bundle? = null) = Analytics.logEvent(name, params)

    /** Shortcut for [AdsManager.setAdsEnabled], e.g. false for premium users. */
    @JvmStatic fun setAdsEnabled(enabled: Boolean) = AdsManager.setAdsEnabled(enabled)
}
