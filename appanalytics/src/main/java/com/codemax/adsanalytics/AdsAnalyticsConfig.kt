package com.codemax.adsanalytics

import android.app.Activity

/**
 * Immutable SDK configuration. Build it with [AdsAnalyticsConfig.Builder] (works the same from Java and Kotlin).
 *
 * Everything is optional: without a panel URL/key the panel features are no-ops, without Firebase / Facebook
 * on the classpath their bridges are no-ops, and ad unit IDs fall back to [defaultAdUnits].
 */
class AdsAnalyticsConfig private constructor(b: Builder) {

    /** Analytics panel base URL, e.g. "https://panel.example.com". Empty disables panel features. */
    val panelBaseUrl: String = b.panelBaseUrl.trimEnd('/')
    /** Value of the `x-api-key` header sent to the panel. Empty disables panel features. */
    val panelApiKey: String = b.panelApiKey
    /** Package name the panel knows the app by. Defaults to the app's package without a ".debug" suffix. */
    val panelPackageName: String? = b.panelPackageName
    /** App version reported to the panel. Defaults to the installed versionName. */
    val appVersionName: String? = b.appVersionName

    /** Asset file with local Remote Config defaults (panel config overrides it). */
    val localConfigAsset: String = b.localConfigAsset

    /** Ad unit IDs used when the panel does not return any for a placement. */
    val defaultAdUnits: AdUnits = b.defaultAdUnits

    /** Show an app-open ad when the app returns from background. */
    val appOpenAdsEnabled: Boolean = b.appOpenAdsEnabled
    /** Activities on which no resume (app-open) ad is shown, e.g. splash or paywall screens. */
    val appOpenExcludedActivities: Set<Class<out Activity>> = b.appOpenExcludedActivities.toSet()
    /** After an interstitial closes, show the full-screen native (countdown) on the next screen. */
    val nativeFullAfterInterstitial: Boolean = b.nativeFullAfterInterstitial
    /** Activities on which a pending full-screen native uses the "is_load_native_full_splash" switch. */
    val nativeFullSplashActivities: Set<Class<out Activity>> = b.nativeFullSplashActivities.toSet()
    /** Activities on which a pending full-screen native is never shown. */
    val nativeFullExcludedActivities: Set<Class<out Activity>> = b.nativeFullExcludedActivities.toSet()
    /** Seconds before the close button appears on the full-screen native. */
    val nativeFullCountdownSeconds: Int = b.nativeFullCountdownSeconds.coerceAtLeast(0)
    /** Minimum interstitial interval when Remote Config has none. Never below 15 s. */
    val defaultInterstitialIntervalSeconds: Long = b.defaultInterstitialIntervalSeconds.coerceAtLeast(MIN_INTERSTITIAL_INTERVAL_S)
    /** How long the "Loading ads" overlay waits for an on-demand interstitial. */
    val loadAndShowTimeoutMs: Long = b.loadAndShowTimeoutMs

    /** Log a Firebase `screen_view` event for each resumed activity. */
    val trackScreenViews: Boolean = b.trackScreenViews
    /** Subscribe to FCM topics "release_devices"/"debug_device" and the "utc±N" time-zone topic. */
    val firebasePushTopics: Boolean = b.firebasePushTopics
    /** Facebook app ID; the panel's `facebookId` wins when present. Null/empty skips Facebook init. */
    val facebookAppId: String? = b.facebookAppId
    /** Facebook client token; the panel's `facebookToken` wins when present. */
    val facebookClientToken: String? = b.facebookClientToken

    /** Debug mode: verbose logs, UMP debug settings, "debug_device" push topic. */
    val debug: Boolean = b.debug
    /** Logcat output. Defaults to [debug]. */
    val loggingEnabled: Boolean = b.loggingEnabled ?: b.debug
    /** Hashed device IDs for UMP consent testing (debug only). */
    val consentTestDeviceHashedIds: List<String> = b.consentTestDeviceHashedIds.toList()
    /** UMP debug geography, e.g. ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA (debug only). */
    val consentDebugGeography: Int? = b.consentDebugGeography
    /** AdMob test device IDs passed to MobileAds.setRequestConfiguration. */
    val adTestDeviceIds: List<String> = b.adTestDeviceIds.toList()

    /** Fallback ad unit IDs per format. */
    class AdUnits @JvmOverloads constructor(
        val banner: String = "",
        val interstitial: String = "",
        val native: String = "",
        val nativeFull: String = "",
        val appOpen: String = ""
    ) {
        companion object {
            /** Google's public AdMob test unit IDs. Use only in development. */
            @JvmField
            val GOOGLE_TEST = AdUnits(
                banner = "ca-app-pub-3940256099942544/9214589741",
                interstitial = "ca-app-pub-3940256099942544/1033173712",
                native = "ca-app-pub-3940256099942544/2247696110",
                nativeFull = "ca-app-pub-3940256099942544/2247696110",
                appOpen = "ca-app-pub-3940256099942544/9257395921"
            )
        }
    }

    class Builder {
        internal var panelBaseUrl = ""
        internal var panelApiKey = ""
        internal var panelPackageName: String? = null
        internal var appVersionName: String? = null
        internal var localConfigAsset = "remote_config.json"
        internal var defaultAdUnits = AdUnits()
        internal var appOpenAdsEnabled = true
        internal val appOpenExcludedActivities = mutableSetOf<Class<out Activity>>()
        internal var nativeFullAfterInterstitial = true
        internal val nativeFullSplashActivities = mutableSetOf<Class<out Activity>>()
        internal val nativeFullExcludedActivities = mutableSetOf<Class<out Activity>>()
        internal var nativeFullCountdownSeconds = 5
        internal var defaultInterstitialIntervalSeconds = MIN_INTERSTITIAL_INTERVAL_S
        internal var loadAndShowTimeoutMs = 10_000L
        internal var trackScreenViews = false
        internal var firebasePushTopics = true
        internal var facebookAppId: String? = null
        internal var facebookClientToken: String? = null
        internal var debug = false
        internal var loggingEnabled: Boolean? = null
        internal val consentTestDeviceHashedIds = mutableListOf<String>()
        internal var consentDebugGeography: Int? = null
        internal val adTestDeviceIds = mutableListOf<String>()

        fun panel(baseUrl: String, apiKey: String) = apply { panelBaseUrl = baseUrl; panelApiKey = apiKey }
        fun panelPackageName(packageName: String) = apply { panelPackageName = packageName }
        fun appVersionName(versionName: String) = apply { appVersionName = versionName }
        fun localConfigAsset(assetFileName: String) = apply { localConfigAsset = assetFileName }
        fun defaultAdUnits(units: AdUnits) = apply { defaultAdUnits = units }
        fun appOpenAdsEnabled(enabled: Boolean) = apply { appOpenAdsEnabled = enabled }
        fun excludeFromAppOpen(vararg activities: Class<out Activity>) = apply { appOpenExcludedActivities += activities }
        fun nativeFullAfterInterstitial(enabled: Boolean) = apply { nativeFullAfterInterstitial = enabled }
        fun nativeFullSplashActivities(vararg activities: Class<out Activity>) = apply { nativeFullSplashActivities += activities }
        fun excludeFromNativeFull(vararg activities: Class<out Activity>) = apply { nativeFullExcludedActivities += activities }
        fun nativeFullCountdownSeconds(seconds: Int) = apply { nativeFullCountdownSeconds = seconds }
        fun defaultInterstitialIntervalSeconds(seconds: Long) = apply { defaultInterstitialIntervalSeconds = seconds }
        fun loadAndShowTimeoutMs(ms: Long) = apply { loadAndShowTimeoutMs = ms }
        fun trackScreenViews(enabled: Boolean) = apply { trackScreenViews = enabled }
        fun firebasePushTopics(enabled: Boolean) = apply { firebasePushTopics = enabled }
        @JvmOverloads
        fun facebook(appId: String, clientToken: String? = null) = apply { facebookAppId = appId; facebookClientToken = clientToken }
        fun debug(enabled: Boolean) = apply { debug = enabled }
        fun logging(enabled: Boolean) = apply { loggingEnabled = enabled }
        fun consentTestDeviceHashedIds(vararg ids: String) = apply { consentTestDeviceHashedIds += ids }
        fun consentDebugGeography(geography: Int) = apply { consentDebugGeography = geography }
        fun adTestDeviceIds(vararg ids: String) = apply { adTestDeviceIds += ids }

        fun build() = AdsAnalyticsConfig(this)
    }

    companion object {
        /** AdMob-friendly floor for interstitial frequency (seconds). */
        const val MIN_INTERSTITIAL_INTERVAL_S = 15L
    }
}
