# AdsAnalytics SDK — User Guide

A step-by-step guide for adding the AdsAnalytics SDK to an Android app.
For the full API reference, see [README.md](README.md).

---

## Contents

1. [Before you start](#1-before-you-start)
2. [Add the SDK to your project](#2-add-the-sdk-to-your-project)
3. [Set up the manifest](#3-set-up-the-manifest)
4. [Initialise the SDK in your Application](#4-initialise-the-sdk-in-your-application)
5. [Set up the splash screen](#5-set-up-the-splash-screen)
6. [Show ads on your screens](#6-show-ads-on-your-screens)
7. [Control ads with Remote Config](#7-control-ads-with-remote-config)
8. [Track analytics events](#8-track-analytics-events)
9. [Track ad revenue in other tools](#9-track-ad-revenue-in-other-tools)
10. [Common tasks](#10-common-tasks)
11. [Testing](#11-testing)
12. [Troubleshooting](#12-troubleshooting)
13. [Go-live checklist](#13-go-live-checklist)

---

## 1. Before you start

You need:

| Item | Where to get it | Required? |
|---|---|---|
| AdMob App ID (`ca-app-pub-…~…`) | AdMob console → Apps → App settings | **Yes** |
| AdMob ad unit IDs (banner, interstitial, native, app open) | AdMob console → Ad units | **Yes** (or use the panel) |
| Analytics panel URL + API key | Your panel admin | Optional |
| `google-services.json` | Firebase console | Optional (for Firebase Analytics) |
| Facebook App ID + Client Token | Meta for Developers | Optional |

The app must use **minSdk 24 or higher** and **compileSdk 36**.

---

## 2. Add the SDK to your project

### Option A — as a module (simplest)

1. Copy the `adsanalytics/` folder into your project's root folder.
2. In `settings.gradle.kts`:

   ```kotlin
   include(":adsanalytics")
   ```

3. In `app/build.gradle.kts`:

   ```kotlin
   dependencies {
       implementation(project(":adsanalytics"))
   }
   ```

4. The SDK reads its library versions from `gradle/libs.versions.toml`. Copy these entries into your
   project's version catalog if they are missing: `playServicesAds`, `ump`, `installReferrer`, `firebaseBom`,
   `facebookSdk`, `coreKtx`, `activity`, `lifecycle`, `junit`, `json`, and the `android-library` plugin.

### Option B — as a Maven artifact

1. In the SDK project, run:

   ```bash
   ./gradlew :adsanalytics:publishToMavenLocal
   ```

2. In your app:

   ```kotlin
   repositories { mavenLocal() }
   dependencies { implementation("com.codemax:adsanalytics:1.0.0") }
   ```

### Optional: Firebase and Facebook

The SDK uses these only if your app includes them. Add whichever you need:

```kotlin
// Firebase Analytics (also needs app/google-services.json and the google-services plugin)
implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
implementation("com.google.firebase:firebase-analytics")
implementation("com.google.firebase:firebase-messaging")   // only for push topics

// Facebook App Events
implementation("com.facebook.android:facebook-core:17.0.1")
```

---

## 3. Set up the manifest

Add your AdMob App ID inside `<application>` in `app/src/main/AndroidManifest.xml`:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

> ⚠️ Without this line the app **crashes at startup**.

You don't need to add any permissions or activities. The SDK already declares `INTERNET`,
`ACCESS_NETWORK_STATE`, `AD_ID` and its own `InHouseAdActivity`.

---

## 4. Initialise the SDK in your Application

Create an `Application` class (or open your existing one) and call `AdsAnalytics.initialize()` **once** in `onCreate()`.

**Kotlin**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AdsAnalytics.initialize(
            this,
            AdsAnalyticsConfig.Builder()
                // Your AdMob ad units (used when the panel doesn't supply IDs)
                .defaultAdUnits(AdsAnalyticsConfig.AdUnits(
                    banner = "ca-app-pub-xxx/111",
                    interstitial = "ca-app-pub-xxx/222",
                    native = "ca-app-pub-xxx/333",
                    nativeFull = "ca-app-pub-xxx/444",
                    appOpen = "ca-app-pub-xxx/555"
                ))
                // Optional: analytics panel
                .panel(BuildConfig.PANEL_BASE_URL, BuildConfig.PANEL_API_KEY)
                // Screens where the "welcome back" ad must NOT appear
                .excludeFromAppOpen(SplashActivity::class.java)
                .excludeFromNativeFull(SplashActivity::class.java)
                // Logs + consent test mode in debug builds
                .debug(BuildConfig.DEBUG)
                .build()
        )
    }
}
```

**Java**

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AdsAnalytics.initialize(this, new AdsAnalyticsConfig.Builder()
                .defaultAdUnits(new AdsAnalyticsConfig.AdUnits(
                        "ca-app-pub-xxx/111", "ca-app-pub-xxx/222",
                        "ca-app-pub-xxx/333", "ca-app-pub-xxx/444", "ca-app-pub-xxx/555"))
                .panel(BuildConfig.PANEL_BASE_URL, BuildConfig.PANEL_API_KEY)
                .excludeFromAppOpen(SplashActivity.class)
                .debug(BuildConfig.DEBUG)
                .build());
    }
}
```

Register it in the manifest:

```xml
<application android:name=".MyApp" ... >
```

### Config options at a glance

| Builder method | What it does | Default |
|---|---|---|
| `defaultAdUnits(...)` | Fallback ad unit IDs | empty |
| `panel(url, key)` | Connects the analytics panel | off |
| `excludeFromAppOpen(...)` | No resume ad on these screens | none |
| `excludeFromNativeFull(...)` | No full-screen native on these screens | none |
| `nativeFullSplashActivities(...)` | Screens that use the `is_load_native_full_splash` switch | none |
| `appOpenAdsEnabled(false)` | Turns resume ads off completely | on |
| `nativeFullAfterInterstitial(false)` | No full-screen native after interstitials | on |
| `nativeFullCountdownSeconds(n)` | Countdown before the close button | 5 |
| `defaultInterstitialIntervalSeconds(n)` | Minimum gap between interstitials (min 15) | 15 |
| `trackScreenViews(true)` | Logs a Firebase `screen_view` for every screen | off |
| `facebook(appId, token)` | Facebook App Events | off |
| `adTestDeviceIds(...)` | AdMob test devices | none |
| `debug(true)` | Logcat logs + UMP debug mode | off |

---

## 5. Set up the splash screen

The splash screen does three things in order:
**ask for consent → start ads → show the splash ad**, then opens the next screen.

```kotlin
class SplashActivity : AppCompatActivity() {
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        AdsAnalytics.gatherConsentAndInitAds(this) {
            AdsManager.showSplashInterstitial(this, showNativeFullNext = isFirstRun()) {
                goNext()
            }
        }
    }

    private fun goNext() {
        if (routed || isFinishing) return
        routed = true
        AppOpenManager.load(this)   // get the "welcome back" ad ready
        // Shows the panel's house ad first (if any), then opens MainActivity
        InHouseAdActivity.startThenRoute(this, Intent(this, MainActivity::class.java))
        finish()
    }
}
```

`goNext()` always runs, even if there is no ad, no internet or no consent, so the user is never stuck on the splash screen.

> 💡 You don't use the panel's house ads? Replace the `startThenRoute(...)` line with a normal
> `startActivity(Intent(this, MainActivity::class.java))`.

---

## 6. Show ads on your screens

### 6.1 Native ad

**Layout:** add an outer container and an inner frame where the ad goes.

```xml
<FrameLayout
    android:id="@+id/layoutNative"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone">

    <FrameLayout
        android:id="@+id/frNative"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />
</FrameLayout>
```

**Code:**

```kotlin
AdsManager.loadNative(
    this,
    container = binding.layoutNative,
    adFrame = binding.frNative,
    layout = com.codemax.adsanalytics.R.layout.aa_native_medium,  // or aa_native_small
    key = "is_load_native_home"
)
```

```java
AdsManager.loadNative(this, binding.layoutNative, binding.frNative);
```

The container hides itself when no ad can be shown.

**Using your own design:** create a layout with `com.google.android.gms.ads.nativead.NativeAdView` as the root and
use these view IDs: `ad_headline`, `ad_body`, `ad_call_to_action`, `ad_app_icon`, `ad_advertiser`, `ad_media`.
Then pass `layout = R.layout.your_layout`.

### 6.2 Banner ad

```xml
<FrameLayout
    android:id="@+id/bannerContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

```kotlin
AdsManager.loadBanner(this, binding.bannerContainer, key = "is_load_banner_home")
```

```java
AdsManager.loadBanner(this, binding.bannerContainer, "is_load_banner_home");
```

The banner sizes itself to the container's width and pauses, resumes and is destroyed along with the screen.

### 6.3 Interstitial ad — two ways

**Way 1: preloaded (shows instantly).** Use this for actions like Save or Back.

```kotlin
// In onCreate:
AdsManager.preloadInterstitial(this)

// When the user taps Save:
AdsManager.showInterstitial(this, key = "is_load_inter_save") {
    finish()   // ALWAYS runs exactly once: after the ad, or right away if there is no ad
}
```

**Way 2: load on the spot with a "Loading ads…" screen.** Use this for tab switches and opening a detail screen.

```kotlin
AdsManager.loadAndShowInterstitial(this, key = "is_load_inter_tab") {
    openStatisticsTab()
}
```

```java
AdsManager.loadAndShowInterstitial(this, "is_load_inter_tab", () -> openStatisticsTab());
```

> ✅ **Rule:** always put your next action *inside* the callback, never after the call.
> The callback runs whether or not an ad is shown.

The SDK automatically skips the ad when:
- the last interstitial was less than 15 seconds ago (`interval_show_interstitial`),
- another full-screen ad is already showing,
- there is no internet or no consent,
- ads are turned off (premium user), or
- the placement's Remote Config switch is `false`.

### 6.4 Full-screen native ad

After an interstitial closes, the SDK **automatically** shows a full-screen native ad with a 5-second countdown on
the next screen. You don't need to write any code for this.

To show one yourself:

```kotlin
val shown = AdsManager.showNativeFull(this)   // false if no ad is ready yet
```

### 6.5 "Welcome back" ad (App Open)

This works **automatically**. When the user leaves the app and returns, an app-open ad is shown.
It is never shown on screens listed in `excludeFromAppOpen(...)`.

---

## 7. Control ads with Remote Config

Every `key` you pass (like `is_load_native_home`) is an on/off switch. You can change it **without releasing an update**.

### Where values come from (first match wins)

1. Your analytics panel (`config` section)
2. `app/src/main/assets/remote_config.json` in your app
3. The default (`true` for all `is_load_*` switches)

### Example `assets/remote_config.json`

```json
{
  "interval_show_interstitial": 15,
  "is_load_inter_splash_show": true,
  "is_delay_show_inter_splash": 6,
  "is_load_fast_app": false,
  "is_resume_ad_enabled": true,
  "resume_ad_enabled": true,
  "is_load_native_full": true,
  "is_load_native_home": true,
  "is_load_banner_home": true,
  "is_load_inter_save": false
}
```

Here `"is_load_inter_save": false` turns off the Save-button interstitial.

### Naming your keys

Use the pattern `is_load_<type>_<screen>`:

| Type | Example key | Panel ad unit it looks for |
|---|---|---|
| native | `is_load_native_home` | `ntv` → `native_home` |
| interstitial | `is_load_inter_save` | `fsads` → `interstitial_save` |
| banner | `is_load_banner_home` | `banner` → `banner_home` |

If the panel has no screen-specific unit, the SDK uses the main unit, then your `defaultAdUnits`.

### Reading your own values

```kotlin
val showNewOnboarding = RemoteConfig.bool("is_load_onboarding_new", false)
val delay = RemoteConfig.long("my_delay", 5)
```

```java
boolean showNew = RemoteConfig.getBoolean("is_load_onboarding_new", false);
long delay = RemoteConfig.getLong("my_delay", 5);
```

---

## 8. Track analytics events

```kotlin
// Firebase Analytics event
Analytics.logEvent("budget_created", bundleOf("amount" to 500))

// Event hit on your analytics panel
Analytics.logPanelEvent("budget_created")

// Send to the panel only if the panel lists this event as active
Analytics.logPanelEventIfActive("budget_created")

// Facebook App Event
Analytics.logFacebookEvent("budget_created")

// User properties
Analytics.setUserProperty("plan", "free")
```

```java
Bundle params = new Bundle();
params.putInt("amount", 500);
Analytics.logEvent("budget_created", params);
Analytics.logPanelEvent("budget_created");
```

### Tracked automatically — no code needed

- Revenue from every ad impression (Firebase `ad_impression_revenue` + panel)
- Every ad click (panel)
- Install source, organic or paid (Firebase `install_attribution` + panel)
- App opens (panel)
- Screen views (Firebase `screen_view`, if `trackScreenViews(true)`)

### Install source

```kotlin
InstallAttribution.isOrganic()   // true = organic, false = from a campaign
InstallAttribution.source()      // e.g. "google-ads"
InstallAttribution.campaign()
```

---

## 9. Track ad revenue in other tools

Register a listener once in your `Application` to receive every ad event:

```kotlin
AdsAnalytics.addAdEventListener { event ->
    when (event.type) {
        AdEventType.PAID -> {
            // Example: AppsFlyer, Adjust, your own backend
            // event.value (e.g. 0.0012), event.currencyCode ("USD"),
            // event.network ("admob"/"meta"/...), event.adUnitId, event.position
        }
        AdEventType.CLICKED -> { /* ... */ }
        AdEventType.FAILED_TO_LOAD -> Log.w("Ads", "No fill: ${event.errorMessage}")
        else -> Unit
    }
}
```

| Event type | When it fires |
|---|---|
| `LOAD_REQUESTED` | The SDK requested an ad |
| `LOADED` / `FAILED_TO_LOAD` | The ad loaded / did not load |
| `SHOWN` / `FAILED_TO_SHOW` | A full-screen ad appeared / could not appear |
| `IMPRESSION` | The ad was counted as seen |
| `PAID` | Revenue was received for an impression |
| `CLICKED` | The user tapped the ad |
| `DISMISSED` | The user closed a full-screen ad |

---

## 10. Common tasks

### Premium users (remove ads)

```kotlin
AdsAnalytics.setAdsEnabled(false)   // after purchase
AdsAnalytics.setAdsEnabled(true)    // after the subscription ends
```

The setting is saved, so it survives app restarts.

### Privacy settings button (required in GDPR regions)

```kotlin
privacyButton.isVisible = ConsentManager.isPrivacyOptionsRequired(this)
privacyButton.setOnClickListener { ConsentManager.showPrivacyOptions(this) }
```

### No resume ad after opening system settings / share sheet / camera

```kotlin
AppOpenManager.skipNextResume()
startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
```

### No resume ad when opening a screen from a notification

```kotlin
val intent = Intent(context, AddTransactionActivity::class.java)
    .putExtra(AppOpenManager.EXTRA_NO_RESUME_AD, true)
```

### Panel extras

```kotlin
PanelClient.fetchLifetimeRevenue { r -> showRevenue(r.amount, r.currency) }
PanelClient.fetchPolicy { p -> showPolicy(p.text) }
PanelClient.fetchTerms { t -> showTerms(t.text) }
```

All panel callbacks arrive on the main thread, so you can update the UI directly.

---

## 11. Testing

1. **Use test ads during development.** Never click your own real ads.

   ```kotlin
   .defaultAdUnits(AdsAnalyticsConfig.AdUnits.GOOGLE_TEST)
   ```

   Or keep real IDs and register your phone as a test device:

   ```kotlin
   .adTestDeviceIds("YOUR_DEVICE_HASH")   // printed in Logcat by the Ads SDK
   ```

2. **Turn on logs** with `.debug(true)`, then filter Logcat:

   ```bash
   adb logcat | grep "AdsAnalytics/"
   ```

   You will see lines like:

   ```
   AdsAnalytics/Ads: Mobile Ads initialised
   AdsAnalytics/AdEvent: AdEvent(LOADED native unit=... net=admob pos=home_screen:bottom ...)
   AdsAnalytics/AdEvent: AdEvent(PAID banner ... value=1200 USD)
   ```

3. **Test the consent form (EEA)** in debug builds:

   ```kotlin
   .debug(true)
   .consentDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
   .consentTestDeviceHashedIds("YOUR_HASHED_ID")
   ```

   To show the form again: `ConsentManager.reset(context)`, then restart the app.

4. **Test the resume ad:** open the app, press Home, then reopen it from Recents.

5. **Run the sample app** to see every feature working:

   ```bash
   ./gradlew :sample:installDebug
   ```

---

## 12. Troubleshooting

| Problem | Likely cause | Fix |
|---|---|---|
| App crashes on start: *"Missing application ID"* | AdMob App ID missing from the manifest | Add the `<meta-data>` from step 3 |
| *"AdsAnalytics.initialize(...) must be called first"* | Using the SDK before `initialize()` | Call it in `Application.onCreate()` and register the Application in the manifest |
| No ads at all | No consent yet, or ads not initialised | Call `gatherConsentAndInitAds()` on the splash screen |
| No ads at all | Ads turned off | Check `AdsManager.isAdsEnabled()` |
| One placement never shows | Its Remote Config key is `false` | Check the panel and `remote_config.json` |
| Interstitial sometimes doesn't show | 15-second frequency cap | Expected behavior; change `interval_show_interstitial` |
| `FAILED_TO_LOAD`, error code 3 | No fill (common for new ad units) | Wait for AdMob, or test with test IDs |
| Resume ad shows on the splash screen | Splash not excluded | Add `.excludeFromAppOpen(SplashActivity::class.java)` |
| Firebase events missing | Firebase not added, or no `google-services.json` | See the optional step in section 2. `Analytics.isFirebaseAvailable()` should return `true` |
| Panel calls do nothing | URL or key empty | Check `PanelClient.isConfigured` |
| Native ad looks wrong in dark mode | Colors not themed | Override the `aa_*` colors (see README §7) |
| Release build crashes, but debug works | R8 removed classes | The SDK's rules are automatic. Check your own `proguard-rules.pro` for conflicting rules |

---

## 13. Go-live checklist

- [ ] Real AdMob **App ID** in the manifest (not `ca-app-pub-3940256099942544~…`)
- [ ] Real **ad unit IDs** in `defaultAdUnits(...)` or in the panel
- [ ] `.debug(BuildConfig.DEBUG)`, so debug mode is **off** in release builds
- [ ] Panel API key loaded from `local.properties` / CI secrets, **not** written in source code
- [ ] `google-services.json` added (if you use Firebase)
- [ ] Splash, paywall and permission screens added to `excludeFromAppOpen(...)`
- [ ] Privacy settings button added to the settings screen
- [ ] `setAdsEnabled(false)` called for premium users
- [ ] `remote_config.json` reviewed (no test switches left on)
- [ ] App's privacy policy and Play Data safety form mention advertising ID, analytics and ad networks
- [ ] Release build (`assembleRelease`) tested on a real device
