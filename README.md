# AdsAnalytics SDK (`com.codemax.adsanalytics`)

Reusable Android library for **ad monetization** (Google Mobile Ads / AdMob + mediation), **ad activity tracking**
(load, impression, click, paid-revenue events), and **app analytics** (Firebase Analytics, Facebook App Events,
an analytics-panel backend, and install attribution). It works the same from Kotlin and Java.

- Min SDK 24, compile SDK 36. Kotlin, built with AGP 9 (built-in Kotlin).
- Modules: `:adsanalytics` (the library) and `:sample` (demo app with a Kotlin flow and a Java screen).

---

## 1. Install

**As a module:** copy `adsanalytics/` into your project, then:

```kotlin
// settings.gradle.kts
include(":adsanalytics")
// app/build.gradle.kts
dependencies { implementation(project(":adsanalytics")) }
```

**As a Maven artifact:** run `./gradlew :adsanalytics:publishToMavenLocal`, then:

```kotlin
repositories { mavenLocal() }
dependencies { implementation("com.codemax:adsanalytics:1.0.0") }
```

The AAR is built at `adsanalytics/build/outputs/aar/adsanalytics-release.aar`.

### Required in your app manifest

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

The SDK's manifest already declares `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` and its `InHouseAdActivity`.

### Optional integrations

The SDK detects these at runtime. If they're absent, the matching calls do nothing.

| Integration | Add to your app | Notes |
|---|---|---|
| Firebase Analytics | `firebase-analytics` + `google-services.json` + the google-services plugin | events, `ad_impression_revenue`, `screen_view`, `install_attribution` |
| Firebase Messaging | `firebase-messaging` | auto-subscribes to `release_devices`/`debug_device` and `utc±N` topics |
| Facebook App Events | `com.facebook.android:facebook-core` | init + `activateApp`; app ID from the panel or config |
| Analytics panel | `.panel(baseUrl, apiKey)` in the config | remote config, remote ad unit IDs, event/ad/revenue reporting |

---

## 2. Quick start

### Application

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsAnalytics.initialize(this, AdsAnalyticsConfig.Builder()
            .panel("https://panel.example.com", BuildConfig.PANEL_API_KEY)  // optional
            .defaultAdUnits(AdsAnalyticsConfig.AdUnits(
                banner = "ca-app-pub-…/…", interstitial = "ca-app-pub-…/…",
                native = "ca-app-pub-…/…", nativeFull = "ca-app-pub-…/…", appOpen = "ca-app-pub-…/…"))
            .excludeFromAppOpen(SplashActivity::class.java, PremiumActivity::class.java)
            .excludeFromNativeFull(SplashActivity::class.java)
            .nativeFullSplashActivities(LanguageActivity::class.java)
            .facebook("FB_APP_ID", "FB_CLIENT_TOKEN")                        // optional
            .debug(BuildConfig.DEBUG)
            .build())

        // Forward impression-level revenue anywhere (AppsFlyer, Adjust, your backend...)
        AdsAnalytics.addAdEventListener { e ->
            if (e.type == AdEventType.PAID) { /* e.valueMicros, e.currencyCode, e.network, e.position */ }
        }
    }
}
```

Java:

```java
AdsAnalytics.initialize(this, new AdsAnalyticsConfig.Builder()
        .panel("https://panel.example.com", apiKey)
        .defaultAdUnits(AdsAnalyticsConfig.AdUnits.GOOGLE_TEST)
        .excludeFromAppOpen(SplashActivity.class)
        .build());
```

### Splash screen (consent → ads init → splash interstitial)

```kotlin
AdsAnalytics.gatherConsentAndInitAds(this) {
    AdsManager.showSplashInterstitial(this, showNativeFullNext = isFirstRun) {
        AppOpenManager.load(this)                                   // preload the resume ad
        InHouseAdActivity.startThenRoute(this, Intent(this, MainActivity::class.java))
        finish()
    }
}
```

### Screens

```kotlin
// Native ad (container is hidden automatically when no ad can be shown)
AdsManager.loadNative(this, binding.layoutNative, binding.frNative,
    shimmer = binding.shimmer, layout = R.layout.my_native /* or SDK's aa_native_small / aa_native_medium */,
    key = "is_load_native_home")

// Adaptive banner
AdsManager.loadBanner(this, binding.bannerContainer, key = "is_load_banner_home")

// Interstitials: next always runs exactly once
AdsManager.preloadInterstitial(this)
AdsManager.showInterstitial(this, key = "is_load_inter_save") { finish() }
AdsManager.loadAndShowInterstitial(this, key = "is_load_inter_tab", screenProvider = { "stats_screen" }) { openTab() }
```

```java
AdsManager.showInterstitial(this, "is_load_inter_save", () -> "edit_screen", () -> finish());
```

Custom native layouts work as-is if they use the standard IDs: `ad_headline`, `ad_body`, `ad_call_to_action`,
`ad_app_icon`, `ad_advertiser`, `ad_media` (all optional), with `NativeAdView` as the root.

---

## 3. API overview

| Class | Purpose |
|---|---|
| `AdsAnalytics` | `initialize`, `gatherConsentAndInitAds`, `add/removeAdEventListener`, `logEvent`, `setAdsEnabled`, `version` |
| `AdsAnalyticsConfig.Builder` | Panel, default ad units, app-open/native-full rules, Facebook, debug, test devices |
| `ads.AdsManager` | `initialize`, `canShowAds`, `loadNative`, `loadBanner`, `preloadInterstitial`, `showInterstitial`, `loadAndShowInterstitial`, `showSplashInterstitial`, `preloadNativeFull`, `showNativeFull`, `showPendingNativeFull`, `markInterstitialShown`, `setAdsEnabled`, unit-ID resolvers, `adNetworkName` |
| `ads.AppOpenManager` | Resume ad: `load`, `show`, `isAvailable`, `skipNextResume`, `EXTRA_NO_RESUME_AD` |
| `ads.ConsentManager` | UMP: `gather`, `canRequestAds`, `isPrivacyOptionsRequired`, `showPrivacyOptions`, `reset` |
| `ads.InHouseAdActivity` | Panel house ad: `startThenRoute(context, nextIntent)` |
| `ads.NativeAdBinder` | Bind a `NativeAd` to any `NativeAdView` |
| `ads.Placements` | Placement-key → unit suffix / screen name; mediation adapter → network name |
| `analytics.Analytics` | `logEvent`, `logFacebookEvent`, `logPanelEvent(IfActive)`, `setUserProperty`, `setUserId`, `logAdRevenue`, `logAdClick`, `utcTopic` |
| `analytics.ActivityTracker` | `currentActivity`, `resumedActivity`, `isAppInForeground`; optional `screen_view` events |
| `attribution.InstallAttribution` | Play Install Referrer: `isOrganic`, `source`, `medium`, `campaign`, `rawReferrer` |
| `panel.PanelClient` | Panel backend: config, remote ad IDs, events, ad revenue, in-house ads, lifetime revenue, policy/terms |
| `config.RemoteConfig` | `bool/long/double/string` (Java: `getBoolean/getLong/getDouble/getString`): panel → local asset → default |

Callbacks are Java-friendly single-method interfaces (`OnComplete`, `ScreenNameProvider`, `ResultCallback<T>`,
`AdEventListener`). The SDK delivers all of them on the main thread.

### Ad events

`AdEventType`: `LOAD_REQUESTED`, `LOADED`, `FAILED_TO_LOAD`, `SHOWN`, `FAILED_TO_SHOW`, `IMPRESSION`, `CLICKED`,
`DISMISSED`, `PAID`. Each `AdEvent` carries `format`, `adUnitId`, `network` (`admob`, `meta`, `applovin`, …),
`screen`, `position` (`home_screen:bottom`, `…:fullscreen`), and for `PAID` events `valueMicros` / `value` /
`currencyCode` / `precisionType`.

### What is tracked automatically

| Trigger | Firebase | Panel | Listener |
|---|---|---|---|
| Paid event (every SDK ad) | `ad_impression_revenue` (unit, type, micros, currency, precision, network, screen, position) | `POST /ad` with revenue, 1 impression | `PAID` |
| Ad click | — | `POST /ad` with `clicks=1` | `CLICKED` |
| Loads, impressions, shows, dismissals | — | — | all types |
| First launch after install | `install_attribution` | `POST /open` with source + campaign | — |
| Every launch | — | `POST /open` | — |
| Screen resumed (`trackScreenViews(true)`) | `screen_view` | — | — |

---

## 4. Remote Config keys

Values come from the panel's `data.config` first, then from `assets/remote_config.json` (filename configurable), then from the built-in default.

| Key | Type / default | Effect |
|---|---|---|
| `is_load_<format>_<screen>` | bool / `true` | Per-placement switch, e.g. `is_load_native_home`, `is_load_inter_tab` |
| `interval_show_interstitial` | seconds / 15 (min 15) | Interstitial frequency cap (full-screen native counts too) |
| `is_resume_ad_enabled`, `resume_ad_enabled` | bool / `true` | Both must be true for resume ads |
| `is_load_native_full` / `is_load_native_full_splash` | bool / `true` | Full-screen native after interstitials / on first-run screens |
| `is_load_inter_splash_show` | bool / `true` | Splash interstitial on/off |
| `is_load_fast_app` | bool / `false` | Skip the splash ad |
| `is_delay_show_inter_splash` | seconds / 6 (3..30) | Max wait for the splash interstitial |
| `cb_fetch_interval` | seconds / 3600 (min 60) | Panel config refresh interval |

### Ad unit resolution

For a placement key `is_load_native_home`, the native unit is resolved in this order:
panel `ads.ntv.native_home[0]` → panel `ads.ntv.native_main[0]` → `defaultAdUnits.native`.
The panel format keys are `banner` (`banner_all`), `fsads` (`interstitial_main`), `ntv` (`native_main`, `native_fullscreen`) and `open` (`splash_appopen`).

---

## 5. Analytics panel API

All requests send the `x-api-key` header. `{pkg}` is the package name without a `.debug` suffix (configurable).
If any response contains a newer `configUpdatedAt`, the SDK fetches the config again.

| Method | Path | Body / query |
|---|---|---|
| GET | `/api/app-config/{pkg}` | → `data.{config, ads, event, appVersion, isActive, facebookId, facebookToken, …}` |
| POST | `/api/app-analytics/{pkg}/open` | `deviceId, appVersion, source{channel, campaign}` |
| POST | `/api/app-analytics/{pkg}/event` | `deviceId, event` or `deviceId, events[{event, count}]` |
| POST | `/api/app-analytics/{pkg}/ad` | `deviceId, format, network, event, position, adKey, currency, revenueMicros, impressions?, clicks?` |
| POST | `/api/app-analytics/{pkg}/revenue` | `deviceId` → `amount, currency, native, rate` |
| GET | `/api/app-inhouse/{pkg}/active?type=fs` | → `data.{id, adType, medType, mediaUrl, title, ctaUrl, webStayTime}` |
| POST | `/api/app-inhouse/{pkg}/click` | `adId` |
| GET | `/api/app-config/{pkg}/policy` and `/terms` | → `data.{policy|terms, updatedAt}` |

**Keep the API key out of source control.** Inject it through `local.properties` or CI secrets into a `buildConfigField`.

---

## 6. ProGuard / R8

`consumer-rules.pro` is packaged in the AAR and applied to your app automatically. It keeps the public API and
`InHouseAdActivity`, and adds `-dontwarn` for the optional Firebase and Facebook classes. Google Mobile Ads, UMP and
Install Referrer ship their own rules. The sample's minified release build (`:sample:assembleRelease`) verifies this.

## 7. Theming

Override these colors in your app to restyle the SDK's layouts (dark-mode values are in `values-night`):
`aa_native_background`, `aa_native_stroke`, `aa_text_primary`, `aa_text_secondary`, `aa_cta_background`,
`aa_cta_text`, `aa_badge_background`, `aa_badge_text`, `aa_overlay_background`, `aa_loading_background`.
Override the `aa_loading_ads`, `aa_ad_badge` and `aa_close` strings to localise them.

---

## 8. Build & test

```bash
./gradlew :adsanalytics:testDebugUnitTest      # JVM unit tests (placements, referrer parsing, revenue payloads, config)
./gradlew :adsanalytics:assembleRelease        # AAR
./gradlew :sample:assembleDebug                # demo app (Google test ad units, no panel)
./gradlew :sample:assembleRelease              # minified; checks consumer R8 rules
./gradlew :adsanalytics:publishToMavenLocal
```

## 9. Migrating from the original app

| Original | SDK |
|---|---|
| `AdsManager.*` | `com.codemax.adsanalytics.ads.AdsManager.*` (same method names) |
| `AdsManager.AppOpen.*` | `AppOpenManager.*` (`resumedActivity` → `ActivityTracker.resumedActivity`) |
| `ConsentManager` | `ads.ConsentManager` |
| `FirebaseSupport.logEvent / logAdRevenue` | `Analytics.logEvent / logAdRevenue` |
| `FirebaseSupport.RemoteConfig.bool/long` | `RemoteConfig.bool/long` |
| `PanelClient.*` | `panel.PanelClient.*` (callbacks now on the main thread) |
| `InstallAttribution.*` | `attribution.InstallAttribution.*` (no `Context` argument) |
| `InHouseAdActivity.startThenRoute` | same |
| Full-screen native overlay views in every layout (`frAdsNativeFull`, …) | Not needed. The SDK adds the overlay on top of the current screen |
| `BaseActivity.onResume → showPendingNativeFull` | Automatic via `ActivityTracker` |
| (new) premium / no-ads switch | `AdsAnalytics.setAdsEnabled(false)` |

### Not included

The SDK deliberately leaves out the original app's reviewer-detection logic (`PolicyGate`: "first $0-revenue
impression ⇒ hide ads/features" and the `forceUserType` overrides), the lock-screen full-screen ads, and the native
notification engine. Hiding behavior from app reviewers breaks Google Play's deceptive-behavior policy, and the
notification code is not part of analytics or ads tracking. Install attribution is still available as
reporting data (`InstallAttribution.isOrganic()`).
