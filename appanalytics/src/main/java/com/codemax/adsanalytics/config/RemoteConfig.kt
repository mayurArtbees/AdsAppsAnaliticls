package com.codemax.adsanalytics.config

import android.content.Context
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import com.codemax.adsanalytics.panel.PanelClient
import org.json.JSONObject

/**
 * Remote Config lookups. Order: panel config (`/api/app-config` → `data.config`) → local asset JSON
 * (default `assets/remote_config.json`, cached in SharedPreferences) → the supplied default.
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"
    private const val PREFS = "adsanalytics_remote_config"
    private const val KEY_JSON = "config_json"
    @Volatile private var localDefaults: JSONObject? = null

    internal fun init(context: Context, assetFile: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val assetJson = runCatching {
            context.assets.open(assetFile).bufferedReader().use { it.readText() }
        }.onFailure { SdkLog.d(TAG, "No local config asset '$assetFile': ${it.message}") }.getOrNull()

        localDefaults = if (assetJson != null) {
            prefs.edit().putString(KEY_JSON, assetJson).apply()
            runCatching { JSONObject(assetJson) }.getOrNull()
        } else {
            prefs.getString(KEY_JSON, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        SdkLog.i(TAG, "${localDefaults?.length() ?: 0} local defaults loaded")
    }

    /** Replaces the local defaults at runtime (e.g. values from your own backend). */
    @JvmStatic fun setLocalDefaults(json: JSONObject?) { localDefaults = json }

    private fun source(key: String): JSONObject? {
        val panel = PanelClient.config
        if (panel != null && panel.has(key)) return panel
        val local = localDefaults
        return if (local != null && local.has(key)) local else null
    }

    // Java names: RemoteConfig.getBoolean / getLong / getDouble / getString ("long" etc. are Java keywords).

    @JvmStatic @JvmOverloads @JvmName("getBoolean")
    fun bool(key: String, default: Boolean = true): Boolean = source(key)?.optBoolean(key, default) ?: default

    @JvmStatic @JvmName("getLong")
    fun long(key: String, default: Long): Long = source(key)?.optLong(key, default) ?: default

    @JvmStatic @JvmName("getDouble")
    fun double(key: String, default: Double): Double = source(key)?.optDouble(key, default) ?: default

    @JvmStatic @JvmOverloads @JvmName("getString")
    fun string(key: String, default: String = ""): String = source(key)?.optString(key, default) ?: default

    @JvmStatic fun has(key: String): Boolean = source(key) != null
}

/** Remote Config keys the SDK reads. Values listed are the built-in defaults. */
object RemoteKeys {
    /** Long, seconds (min 15). Minimum gap between interstitials. */
    const val INTERVAL_SHOW_INTERSTITIAL = "interval_show_interstitial"
    /** Bool, true. Resume (app-open) ad on/off; both keys must be true. */
    const val IS_RESUME_AD_ENABLED = "is_resume_ad_enabled"
    const val RESUME_AD_ENABLED = "resume_ad_enabled"
    /** Bool, true. Full-screen native after interstitials. */
    const val IS_LOAD_NATIVE_FULL = "is_load_native_full"
    /** Bool, true. Full-screen native on first-run screens after the splash ad. */
    const val IS_LOAD_NATIVE_FULL_SPLASH = "is_load_native_full_splash"
    /** Bool, true. Splash interstitial on/off. */
    const val IS_LOAD_INTER_SPLASH_SHOW = "is_load_inter_splash_show"
    /** Bool, false. Skip the splash ad entirely. */
    const val IS_LOAD_FAST_APP = "is_load_fast_app"
    /** Long, seconds (3..30), default 6. Max wait for the splash interstitial. */
    const val IS_DELAY_SHOW_INTER_SPLASH = "is_delay_show_inter_splash"
    /** Long, seconds (min 60), default 3600. Panel config refresh interval. */
    const val CB_FETCH_INTERVAL = "cb_fetch_interval"
}
