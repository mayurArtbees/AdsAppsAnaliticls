package com.codemax.adsanalytics.ads

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.LayoutRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.codemax.adsanalytics.AdEvent
import com.codemax.adsanalytics.AdEventType
import com.codemax.adsanalytics.AdFormat
import com.codemax.adsanalytics.OnComplete
import com.codemax.adsanalytics.R
import com.codemax.adsanalytics.ScreenNameProvider
import com.codemax.adsanalytics.analytics.ActivityTracker
import com.codemax.adsanalytics.analytics.AdEventBus
import com.codemax.adsanalytics.analytics.Analytics
import com.codemax.adsanalytics.config.RemoteConfig
import com.codemax.adsanalytics.config.RemoteKeys
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import com.codemax.adsanalytics.panel.PanelClient
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Mobile Ads (AdMob + mediation) placements with built-in revenue, impression and click tracking:
 * banner, native, interstitial (preloaded or loaded on demand behind a "Loading ads" overlay),
 * full-screen native with countdown, splash interstitial and app-open (see [AppOpenManager]).
 *
 * Every placement takes an optional Remote Config switch key such as "is_load_native_home" (on by default);
 * its suffix picks a screen-specific ad unit from the panel ("native_home") before the main one.
 * Nothing loads before consent, without network, or while ads are disabled ([setAdsEnabled]).
 */
object AdsManager {
    private const val TAG = "Ads"
    private const val PREFS = "adsanalytics_ads"
    private const val KEY_ADS_DISABLED = "ads_disabled"
    private const val KEY_NATIVE_FULL_PENDING = "is_show_cd_native_full"
    private const val NATIVE_FULL_EXPIRY_MS = 60 * 60 * 1000L

    private val initialized = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var interstitialUnit = ""
    private var interLoading = false
    private var lastInterShown = 0L

    /** True while any full-screen ad (interstitial, app-open, full-screen native) is on screen. */
    @JvmStatic
    var isFullScreenAdShowing = false
        internal set

    // ---- Ad unit IDs: panel placement → panel main → configured default ----

    private val defaults get() = Sdk.config.defaultAdUnits
    private fun panelId(format: String, placement: String) = PanelClient.adIds(format, placement).firstOrNull()

    @JvmStatic val bannerUnitId: String get() = panelId("banner", "banner_all") ?: defaults.banner
    @JvmStatic val interstitialUnitId: String get() = panelId("fsads", "interstitial_main") ?: defaults.interstitial
    @JvmStatic val nativeUnitId: String get() = panelId("ntv", "native_main") ?: defaults.native
    @JvmStatic val nativeFullUnitId: String get() = panelId("ntv", "native_fullscreen") ?: defaults.nativeFull.ifEmpty { defaults.native }
    @JvmStatic val appOpenUnitId: String get() = panelId("open", "splash_appopen") ?: defaults.appOpen

    @JvmStatic fun nativeIdFor(key: String?): String =
        Placements.suffix(key)?.let { panelId("ntv", "native_$it") } ?: nativeUnitId

    @JvmStatic fun interstitialIdFor(key: String?): String =
        Placements.suffix(key)?.let { panelId("fsads", "interstitial_$it") } ?: interstitialUnitId

    @JvmStatic fun bannerIdFor(key: String?): String =
        Placements.suffix(key)?.let { panelId("banner", "banner_$it") } ?: bannerUnitId

    /** Network name of the mediation adapter that served an ad. */
    @JvmStatic fun adNetworkName(responseInfo: ResponseInfo?): String =
        try { Placements.networkFromAdapterClass(responseInfo?.mediationAdapterClassName) } catch (_: Exception) { "admob" }

    // ---- State / gating ----

    private fun prefs() = Sdk.prefs(PREFS)

    /** Turn all ads off (e.g. premium users) or back on. Persisted. */
    @JvmStatic fun setAdsEnabled(enabled: Boolean) { prefs().edit().putBoolean(KEY_ADS_DISABLED, !enabled).apply() }

    @JvmStatic fun isAdsEnabled(): Boolean = !prefs().getBoolean(KEY_ADS_DISABLED, false)

    @JvmStatic fun isInitialized(): Boolean = initialized.get()

    @JvmStatic
    fun canShowAds(context: Context): Boolean =
        initialized.get() && isAdsEnabled() && ConsentManager.canRequestAds(context) && Sdk.isNetworkAvailable(context)

    /** Per-placement switch via Remote Config key; on by default. */
    private fun enabled(key: String?): Boolean = key == null || RemoteConfig.bool(key, true)

    private val interMinIntervalMs: Long
        get() = RemoteConfig.long(RemoteKeys.INTERVAL_SHOW_INTERSTITIAL, Sdk.config.defaultInterstitialIntervalSeconds)
            .coerceAtLeast(com.codemax.adsanalytics.AdsAnalyticsConfig.MIN_INTERSTITIAL_INTERVAL_S) * 1000

    private fun capped(): Boolean = withinFrequencyCap(lastInterShown, SystemClock.elapsedRealtime(), interMinIntervalMs)

    private fun request() = AdRequest.Builder().build()

    private fun screenOf(key: String?, provider: ScreenNameProvider?) =
        provider?.let { runCatching { it.screenName() }.getOrNull() } ?: Placements.screenFromKey(key)

    private fun emit(
        type: AdEventType, format: AdFormat, unit: String, network: String?, screen: String, position: String,
        error: AdError? = null
    ) = AdEventBus.dispatch(AdEvent(type, format, unit, network, screen, position, errorCode = error?.code, errorMessage = error?.message))

    /**
     * Initialises the Mobile Ads SDK once consent allows it. Safe to call repeatedly (e.g. from
     * Application.onCreate after process death, and again from the splash after consent).
     * [onDone] always runs once, on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, onDone: OnComplete? = null) {
        val app = context.applicationContext
        if (!ConsentManager.canRequestAds(app) || !initialized.compareAndSet(false, true)) {
            onDone?.let { Sdk.onMain { it.onComplete() } }; return
        }
        val testIds = Sdk.config.adTestDeviceIds
        if (testIds.isNotEmpty()) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(testIds).build())
        }
        MobileAds.initialize(app) {
            SdkLog.i(TAG, "Mobile Ads initialised")
            preloadNativeFull(app)
            Sdk.onMain { onDone?.onComplete() }
        }
    }

    // ---- Native ----

    /**
     * Loads a native ad into [adFrame] using [layout] (any NativeAdView layout with the standard IDs
     * ad_headline / ad_body / ad_call_to_action / ad_app_icon / ad_advertiser / ad_media).
     * [container] is hidden when no ad can be shown; [shimmer] is shown while loading.
     */
    @JvmStatic
    @JvmOverloads
    fun loadNative(
        activity: Activity,
        container: View,
        adFrame: FrameLayout,
        shimmer: View? = null,
        @LayoutRes layout: Int = R.layout.aa_native_small,
        key: String? = null,
        screenProvider: ScreenNameProvider? = null
    ) {
        if (!canShowAds(activity) || !enabled(key)) { container.visibility = View.GONE; return }
        container.visibility = View.VISIBLE
        shimmer?.visibility = View.VISIBLE
        val unitId = nativeIdFor(key)
        var loaded: NativeAd? = null
        val screen = { screenOf(key, screenProvider) }
        emit(AdEventType.LOAD_REQUESTED, AdFormat.NATIVE, unitId, null, screen(), "${screen()}:bottom")
        AdLoader.Builder(activity, unitId)
            .forNativeAd { ad ->
                if (activity.isDestroyed || activity.isFinishing) { ad.destroy(); return@forNativeAd }
                loaded = ad
                val net = adNetworkName(ad.responseInfo)
                ad.setOnPaidEventListener { value ->
                    val s = screen()
                    Analytics.logAdRevenue(unitId, "native", value, net, s, "$s:bottom")
                }
                val adView = LayoutInflater.from(activity).inflate(layout, adFrame, false) as NativeAdView
                NativeAdBinder.bind(ad, adView)
                adFrame.removeAllViews()
                adFrame.addView(adView)
                adFrame.visibility = View.VISIBLE
                shimmer?.visibility = View.GONE
                emit(AdEventType.LOADED, AdFormat.NATIVE, unitId, net, screen(), "${screen()}:bottom")
                destroyWith(activity, ad)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    SdkLog.d(TAG, "native failed: ${error.message}")
                    container.visibility = View.GONE
                    emit(AdEventType.FAILED_TO_LOAD, AdFormat.NATIVE, unitId, null, screen(), "${screen()}:bottom", error)
                }
                override fun onAdImpression() {
                    emit(AdEventType.IMPRESSION, AdFormat.NATIVE, unitId, adNetworkName(loaded?.responseInfo), screen(), "${screen()}:bottom")
                }
                override fun onAdClicked() {
                    val s = screen()
                    Analytics.logAdClick(unitId, AdFormat.NATIVE, adNetworkName(loaded?.responseInfo), s, "$s:bottom")
                }
            })
            .build()
            .loadAd(request())
    }

    private fun destroyWith(activity: Activity, ad: NativeAd) {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { ad.destroy() }
        })
    }

    // ---- Banner ----

    /** Loads an anchored adaptive banner sized to [container]'s width. */
    @JvmStatic
    @JvmOverloads
    fun loadBanner(activity: Activity, container: FrameLayout, key: String? = null, screenProvider: ScreenNameProvider? = null) {
        if (!canShowAds(activity) || !enabled(key)) { container.visibility = View.GONE; return }
        container.visibility = View.VISIBLE
        // Size the adaptive banner to the container (it may have padding/margins), not the screen.
        container.post {
            if (activity.isDestroyed) return@post
            val metrics = activity.resources.displayMetrics
            val widthPx = if (container.width > 0) container.width - container.paddingLeft - container.paddingRight else metrics.widthPixels
            showBanner(activity, container, (widthPx / metrics.density).toInt(), key, screenProvider)
        }
    }

    private fun showBanner(activity: Activity, container: FrameLayout, widthDp: Int, key: String?, screenProvider: ScreenNameProvider?) {
        val unitId = bannerIdFor(key)
        val screen = { screenOf(key, screenProvider) }
        val adView = AdView(activity)
        adView.adUnitId = unitId
        adView.setOnPaidEventListener { value ->
            val s = screen()
            Analytics.logAdRevenue(unitId, "banner", value, adNetworkName(adView.responseInfo), s, "$s:bottom")
        }
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                emit(AdEventType.LOADED, AdFormat.BANNER, unitId, adNetworkName(adView.responseInfo), screen(), "${screen()}:bottom")
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                container.visibility = View.GONE
                emit(AdEventType.FAILED_TO_LOAD, AdFormat.BANNER, unitId, null, screen(), "${screen()}:bottom", error)
            }
            override fun onAdImpression() {
                emit(AdEventType.IMPRESSION, AdFormat.BANNER, unitId, adNetworkName(adView.responseInfo), screen(), "${screen()}:bottom")
            }
            override fun onAdClicked() {
                val s = screen()
                Analytics.logAdClick(unitId, AdFormat.BANNER, adNetworkName(adView.responseInfo), s, "$s:bottom")
            }
        }
        container.removeAllViews()
        container.addView(adView)
        container.visibility = View.VISIBLE
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = adView.pause()
            override fun onResume(owner: LifecycleOwner) = adView.resume()
            override fun onDestroy(owner: LifecycleOwner) = adView.destroy()
        })
        emit(AdEventType.LOAD_REQUESTED, AdFormat.BANNER, unitId, null, screen(), "${screen()}:bottom")
        adView.loadAd(request())
    }

    // ---- Interstitial ----

    /**
     * Starts the frequency cap for an interstitial shown outside the SDK's own flows. With
     * [showNativeFullNext] the full-screen native is shown on the next screen.
     */
    @JvmStatic
    @JvmOverloads
    fun markInterstitialShown(showNativeFullNext: Boolean = false) {
        lastInterShown = SystemClock.elapsedRealtime()
        if (showNativeFullNext) nativeFullPending = true
    }

    /** Preloads the main interstitial (and the full-screen native) for [showInterstitial]. */
    @JvmStatic
    fun preloadInterstitial(context: Context) {
        preloadNativeFull(context)
        if (interstitial != null || interLoading || !canShowAds(context)) return
        interLoading = true
        val unitId = interstitialUnitId
        emit(AdEventType.LOAD_REQUESTED, AdFormat.INTERSTITIAL, unitId, null, "interstitial_screen", "interstitial_screen:fullscreen")
        InterstitialAd.load(context.applicationContext, unitId, request(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad; interstitialUnit = unitId; interLoading = false
                    emit(AdEventType.LOADED, AdFormat.INTERSTITIAL, unitId, adNetworkName(ad.responseInfo), "interstitial_screen", "interstitial_screen:fullscreen")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null; interLoading = false
                    emit(AdEventType.FAILED_TO_LOAD, AdFormat.INTERSTITIAL, unitId, null, "interstitial_screen", "interstitial_screen:fullscreen", error)
                }
            })
    }

    /**
     * Shows the preloaded interstitial if allowed, then runs [next]. [next] always runs exactly once —
     * immediately when no ad is shown (not loaded, disabled, or within the frequency cap).
     * When the ad closes (or fails to show) the full-screen native is queued for the next screen.
     */
    @JvmStatic
    @JvmOverloads
    fun showInterstitial(activity: Activity, key: String? = null, screenProvider: ScreenNameProvider? = null, next: OnComplete) {
        val ad = interstitial
        if (!canShowAds(activity) || !enabled(key) || isFullScreenAdShowing || capped() || ad == null) {
            preloadInterstitial(activity)
            next.onComplete(); return
        }
        interstitial = null
        val unitId = interstitialUnit.ifEmpty { interstitialUnitId }
        val net = adNetworkName(ad.responseInfo)
        val screen = { screenOf(key, screenProvider) }
        val once = AtomicBoolean(false)
        val runNext = { if (once.compareAndSet(false, true)) next.onComplete() }
        ad.setOnPaidEventListener { value ->
            val s = screen()
            Analytics.logAdRevenue(unitId, "interstitial", value, net, s, "$s:fullscreen")
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                emit(AdEventType.SHOWN, AdFormat.INTERSTITIAL, unitId, net, screen(), "${screen()}:fullscreen")
            }
            override fun onAdImpression() {
                emit(AdEventType.IMPRESSION, AdFormat.INTERSTITIAL, unitId, net, screen(), "${screen()}:fullscreen")
            }
            override fun onAdDismissedFullScreenContent() {
                isFullScreenAdShowing = false
                lastInterShown = SystemClock.elapsedRealtime()
                emit(AdEventType.DISMISSED, AdFormat.INTERSTITIAL, unitId, net, screen(), "${screen()}:fullscreen")
                preloadInterstitial(activity)
                queueNativeFull()
                runNext()
                showPendingNativeFullSoon()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isFullScreenAdShowing = false
                emit(AdEventType.FAILED_TO_SHOW, AdFormat.INTERSTITIAL, unitId, net, screen(), "${screen()}:fullscreen", error)
                preloadInterstitial(activity)
                queueNativeFull()
                runNext()
                showPendingNativeFullSoon()
            }
            override fun onAdClicked() {
                val s = screen()
                Analytics.logAdClick(unitId, AdFormat.INTERSTITIAL, net, s, "$s:fullscreen")
            }
        }
        isFullScreenAdShowing = true
        ad.show(activity)
    }

    private val loadAndShowToken = Any()

    /**
     * Same checks as [showInterstitial], then a full-screen "Loading ads" overlay while an interstitial
     * for [key] is loaded on the spot (up to the configured timeout) and shown. [next] always runs exactly once.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAndShowInterstitial(activity: Activity, key: String? = null, screenProvider: ScreenNameProvider? = null, next: OnComplete) {
        if (!canShowAds(activity) || !enabled(key) || isFullScreenAdShowing || capped()) {
            preloadInterstitial(activity)
            next.onComplete(); return
        }
        SdkLog.d(TAG, "loading an interstitial on demand behind the loading overlay")
        val loading = Dialog(activity, android.R.style.Theme_Light_NoTitleBar_Fullscreen).apply {
            setContentView(R.layout.aa_loading_ads)
            setCancelable(false)
        }
        runCatching { loading.show() }
        val unitId = interstitialIdFor(key)
        val screen = { screenOf(key, screenProvider) }
        var finished = false
        fun finish(ad: InterstitialAd?) {
            if (finished) return
            finished = true
            Sdk.mainHandler.removeCallbacksAndMessages(loadAndShowToken)
            runCatching { loading.dismiss() }
            if (ad != null && !activity.isFinishing && !activity.isDestroyed) {
                interstitial = ad; interstitialUnit = unitId
                showInterstitial(activity, key, screenProvider, next)
            } else {
                if (ad != null) { interstitial = ad; interstitialUnit = unitId } else queueNativeFull()
                next.onComplete()
                if (ad == null) showPendingNativeFullSoon()
            }
        }
        Sdk.mainHandler.postAtTime({ finish(null) }, loadAndShowToken, SystemClock.uptimeMillis() + Sdk.config.loadAndShowTimeoutMs)
        emit(AdEventType.LOAD_REQUESTED, AdFormat.INTERSTITIAL, unitId, null, screen(), "${screen()}:fullscreen")
        InterstitialAd.load(activity, unitId, request(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    emit(AdEventType.LOADED, AdFormat.INTERSTITIAL, unitId, adNetworkName(ad.responseInfo), screen(), "${screen()}:fullscreen")
                    if (finished) { if (interstitial == null) { interstitial = ad; interstitialUnit = unitId }; return }
                    finish(ad)
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    emit(AdEventType.FAILED_TO_LOAD, AdFormat.INTERSTITIAL, unitId, null, screen(), "${screen()}:fullscreen", error)
                    finish(null)
                }
            })
    }

    /**
     * Splash interstitial: waits up to Remote Config "is_delay_show_inter_splash" seconds (3..30, default 6)
     * for an ad, shows it, then runs [next] once. Skipped when "is_load_fast_app" is true or
     * "is_load_inter_splash_show" is false. With [showNativeFullNext] (e.g. first run) the full-screen
     * native follows on the next screen.
     */
    @JvmStatic
    @JvmOverloads
    fun showSplashInterstitial(activity: Activity, showNativeFullNext: Boolean = false, next: OnComplete) {
        val once = AtomicBoolean(false)
        val token = Any()
        val route = {
            if (once.compareAndSet(false, true)) {
                Sdk.mainHandler.removeCallbacksAndMessages(token)
                next.onComplete()
            }
        }
        if (!canShowAds(activity) || RemoteConfig.bool(RemoteKeys.IS_LOAD_FAST_APP, false) ||
            !RemoteConfig.bool(RemoteKeys.IS_LOAD_INTER_SPLASH_SHOW, true)
        ) { route(); return }
        val delaySec = RemoteConfig.long(RemoteKeys.IS_DELAY_SHOW_INTER_SPLASH, 6).coerceIn(3, 30)
        Sdk.mainHandler.postAtTime({ route() }, token, SystemClock.uptimeMillis() + delaySec * 1000)
        val unitId = interstitialUnitId
        val screen = "splash_screen"
        val position = "splash_screen:fullscreen"
        emit(AdEventType.LOAD_REQUESTED, AdFormat.INTERSTITIAL, unitId, null, screen, position)
        InterstitialAd.load(activity, unitId, request(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                val net = adNetworkName(ad.responseInfo)
                emit(AdEventType.LOADED, AdFormat.INTERSTITIAL, unitId, net, screen, position)
                if (once.get() || activity.isFinishing) return
                Sdk.mainHandler.removeCallbacksAndMessages(token)
                ad.setOnPaidEventListener { value -> Analytics.logAdRevenue(unitId, "interstitial", value, net, screen, position) }
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdShowedFullScreenContent() = emit(AdEventType.SHOWN, AdFormat.INTERSTITIAL, unitId, net, screen, position)
                    override fun onAdImpression() = emit(AdEventType.IMPRESSION, AdFormat.INTERSTITIAL, unitId, net, screen, position)
                    override fun onAdDismissedFullScreenContent() {
                        isFullScreenAdShowing = false
                        emit(AdEventType.DISMISSED, AdFormat.INTERSTITIAL, unitId, net, screen, position)
                        markInterstitialShown(showNativeFullNext)
                        route()
                    }
                    override fun onAdFailedToShowFullScreenContent(e: AdError) {
                        isFullScreenAdShowing = false
                        emit(AdEventType.FAILED_TO_SHOW, AdFormat.INTERSTITIAL, unitId, net, screen, position, e)
                        route()
                    }
                    override fun onAdClicked() = Analytics.logAdClick(unitId, AdFormat.INTERSTITIAL, net, screen, position)
                }
                isFullScreenAdShowing = true
                ad.show(activity)
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                emit(AdEventType.FAILED_TO_LOAD, AdFormat.INTERSTITIAL, unitId, null, screen, position, error)
                route()
            }
        })
    }

    // ---- Full-screen native ("native_full") ----

    private var nativeFullPending: Boolean
        get() = prefs().getBoolean(KEY_NATIVE_FULL_PENDING, false)
        set(value) { prefs().edit().putBoolean(KEY_NATIVE_FULL_PENDING, value).apply() }

    private fun queueNativeFull() { if (Sdk.config.nativeFullAfterInterstitial) nativeFullPending = true }

    private var nativeFull: NativeAd? = null
    private var nativeFullLoadedAt = 0L
    private var nativeFullLoading = false

    /** True when a fresh full-screen native is ready. */
    @JvmStatic
    fun isNativeFullReady(): Boolean =
        nativeFull != null && SystemClock.elapsedRealtime() - nativeFullLoadedAt < NATIVE_FULL_EXPIRY_MS

    @JvmStatic
    fun preloadNativeFull(context: Context) {
        if (isNativeFullReady() || nativeFullLoading || !canShowAds(context)) return
        nativeFull?.destroy(); nativeFull = null
        nativeFullLoading = true
        val unitId = nativeFullUnitId
        val screen = "native_full_screen"
        val position = "native_full_screen:fullscreen"
        var loaded: NativeAd? = null
        emit(AdEventType.LOAD_REQUESTED, AdFormat.NATIVE, unitId, null, screen, position)
        AdLoader.Builder(context.applicationContext, unitId)
            .forNativeAd { ad ->
                loaded = ad
                val net = adNetworkName(ad.responseInfo)
                ad.setOnPaidEventListener { value -> Analytics.logAdRevenue(unitId, "native", value, net, screen, position) }
                nativeFull = ad; nativeFullLoadedAt = SystemClock.elapsedRealtime(); nativeFullLoading = false
                emit(AdEventType.LOADED, AdFormat.NATIVE, unitId, net, screen, position)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    nativeFullLoading = false
                    emit(AdEventType.FAILED_TO_LOAD, AdFormat.NATIVE, unitId, null, screen, position, error)
                }
                override fun onAdImpression() = emit(AdEventType.IMPRESSION, AdFormat.NATIVE, unitId, adNetworkName(loaded?.responseInfo), screen, position)
                override fun onAdClicked() = Analytics.logAdClick(unitId, AdFormat.NATIVE, adNetworkName(loaded?.responseInfo), screen, position)
            })
            .build()
            .loadAd(request())
    }

    /**
     * Shows the pending full-screen native on [activity] if one was queued by an interstitial.
     * Called automatically when a screen resumes; call it yourself only for custom flows.
     */
    @JvmStatic
    fun showPendingNativeFull(activity: Activity) {
        if (!nativeFullPending || isFullScreenAdShowing) return
        val cfg = Sdk.config
        if (activity !is ComponentActivity || cfg.nativeFullExcludedActivities.any { it.isInstance(activity) }) return
        nativeFullPending = false
        val key = if (cfg.nativeFullSplashActivities.any { it.isInstance(activity) }) RemoteKeys.IS_LOAD_NATIVE_FULL_SPLASH else RemoteKeys.IS_LOAD_NATIVE_FULL
        if (!canShowAds(activity) || !enabled(key)) return
        showNativeFull(activity, null)
    }

    /** After the interstitial closes the next screen may already be resumed; try on it shortly. */
    private fun showPendingNativeFullSoon() {
        Sdk.mainHandler.postDelayed({
            val activity = ActivityTracker.resumedActivity ?: return@postDelayed
            if (!activity.isFinishing) showPendingNativeFull(activity)
        }, 500)
    }

    /**
     * Shows the full-screen native overlay on [activity] now: the ad fills the screen, a countdown runs,
     * then the close button appears. Back is ignored during the countdown. Returns false when no ad is ready.
     * [onClosed] runs when the user closes it.
     */
    @JvmStatic
    @JvmOverloads
    fun showNativeFull(activity: Activity, onClosed: OnComplete? = null): Boolean {
        val ad = nativeFull
        if (ad == null || !isNativeFullReady()) { preloadNativeFull(activity); return false }
        val owner = activity as? ComponentActivity ?: return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        nativeFull = null

        val root = LayoutInflater.from(activity).inflate(R.layout.aa_native_full_overlay, content, false)
        val adFrame = root.findViewById<FrameLayout>(R.id.aa_full_ad_frame)
        val countdown = root.findViewById<TextView>(R.id.aa_full_countdown)
        val close = root.findViewById<View>(R.id.aa_full_close)
        val adView = LayoutInflater.from(activity).inflate(R.layout.aa_native_full, adFrame, false) as NativeAdView
        NativeAdBinder.bind(ad, adView)
        adFrame.addView(adView)
        root.isClickable = true // keep taps from reaching the screen underneath
        root.translationZ = 1000f // above elevated views
        content.addView(root)
        isFullScreenAdShowing = true
        lastInterShown = SystemClock.elapsedRealtime()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var secondsLeft = Sdk.config.nativeFullCountdownSeconds
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (secondsLeft <= 0) close.performClick() }
        }
        owner.onBackPressedDispatcher.addCallback(owner, backCallback)

        close.visibility = View.GONE
        countdown.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                if (secondsLeft <= 0) {
                    countdown.visibility = View.GONE
                    close.visibility = View.VISIBLE
                    return
                }
                countdown.text = secondsLeft.toString()
                secondsLeft--
                handler.postDelayed(this, 1000)
            }
        }
        tick.run()

        var closed = false
        close.setOnClickListener {
            if (closed) return@setOnClickListener
            closed = true
            handler.removeCallbacksAndMessages(null)
            backCallback.remove()
            content.removeView(root)
            ad.destroy()
            isFullScreenAdShowing = false
            lastInterShown = SystemClock.elapsedRealtime()
            emit(AdEventType.DISMISSED, AdFormat.NATIVE, nativeFullUnitId, adNetworkName(ad.responseInfo), "native_full_screen", "native_full_screen:fullscreen")
            preloadNativeFull(activity)
            onClosed?.onComplete()
        }
        // If the screen is closed while the ad is up, release it.
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                if (!closed) { closed = true; ad.destroy(); isFullScreenAdShowing = false }
            }
        })
        emit(AdEventType.SHOWN, AdFormat.NATIVE, nativeFullUnitId, adNetworkName(ad.responseInfo), "native_full_screen", "native_full_screen:fullscreen")
        return true
    }
}
