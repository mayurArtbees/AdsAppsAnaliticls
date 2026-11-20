package com.codemax.adsanalytics.panel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.codemax.adsanalytics.ResultCallback
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the analytics panel backend:
 * - `GET  /api/app-config/{pkg}`               app config (remote config, ad unit IDs, active events)
 * - `POST /api/app-analytics/{pkg}/open`       launch ping with install source
 * - `POST /api/app-analytics/{pkg}/event`      event hits (single or batch)
 * - `POST /api/app-analytics/{pkg}/ad`         ad impressions / clicks / revenue
 * - `POST /api/app-analytics/{pkg}/revenue`    lifetime revenue of this device
 * - `GET  /api/app-inhouse/{pkg}/active`       in-house ad, `POST .../click` click
 * - `GET  /api/app-config/{pkg}/policy|terms`  legal texts
 *
 * Every response may carry `configUpdatedAt`; a newer value triggers a config re-fetch.
 * All network work runs off the main thread; callbacks are delivered on the main thread.
 * Every method is a no-op when the panel URL or API key is not configured.
 */
object PanelClient {
    private const val TAG = "Panel"
    private const val PREFS = "adsanalytics_panel"
    private const val KEY_CONFIG_CACHE = "app_config_cache"
    private const val KEY_UPDATED_AT = "config_updated_at"
    private const val KEY_DEVICE_ID = "panel_device_id"
    private const val TIMEOUT_MS = 10_000

    @Volatile private var appData: JSONObject? = null
    @Volatile private var updatedAt: Long = 0
    @Volatile private var lastFetchAt = 0L
    private var baseUrl = ""
    private var apiKey = ""
    private var pkg = ""
    private var appVersion = ""
    private var deviceId = ""
    private val configListeners = java.util.concurrent.CopyOnWriteArrayList<OnConfigUpdatedListener>()

    /** Notified (main thread) every time a fresh app config has been fetched. */
    fun interface OnConfigUpdatedListener {
        fun onConfigUpdated()
    }

    // ---- App config data ----

    @JvmStatic val config: JSONObject? get() = appData?.optJSONObject("config")
    @JvmStatic val ads: JSONObject? get() = appData?.optJSONObject("ads")
    @JvmStatic val events: JSONArray? get() = appData?.optJSONArray("event")
    @JvmStatic val serverVersion: Int get() = appData?.optInt("appVersion", 0) ?: 0
    @JvmStatic val isActive: Boolean get() = appData?.optBoolean("isActive", true) ?: true
    @JvmStatic val amount: Int get() = appData?.optInt("amount", 0) ?: 0
    @JvmStatic val facebookId: String? get() = appData.str("facebookId")
    @JvmStatic val facebookToken: String? get() = appData.str("facebookToken")
    @JvmStatic val policyUpdatedAt: String? get() = appData.str("policyUpdatedAt")
    @JvmStatic val termsUpdatedAt: String? get() = appData.str("termsUpdatedAt")

    /** True when a panel URL and API key are configured. */
    @JvmStatic val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()

    /** Stable per-install device ID sent with every analytics call (ANDROID_ID, or a random UUID). */
    @JvmStatic fun getDeviceId(): String = deviceId

    @JvmStatic fun addConfigListener(listener: OnConfigUpdatedListener) { configListeners += listener }
    @JvmStatic fun removeConfigListener(listener: OnConfigUpdatedListener) { configListeners -= listener }

    @SuppressLint("HardwareIds")
    internal fun init(context: Context) {
        val cfg = Sdk.config
        baseUrl = cfg.panelBaseUrl
        apiKey = cfg.panelApiKey
        pkg = cfg.panelPackageName ?: context.packageName.removeSuffix(".debug")
        appVersion = cfg.appVersionName ?: runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val prefs = prefs()
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }

        updatedAt = prefs.getLong(KEY_UPDATED_AT, 0)
        prefs.getString(KEY_CONFIG_CACHE, null)?.let { cached ->
            appData = runCatching { JSONObject(cached) }.getOrNull()
        }
        SdkLog.i(TAG, "Init: pkg=$pkg deviceId=$deviceId cachedConfigKeys=${config?.length() ?: 0}")

        if (!isConfigured) {
            SdkLog.i(TAG, "Panel not configured (empty base URL or API key); panel features are off")
            return
        }
        fetchAppConfig()
    }

    // ---- App config (/app-config) ----

    /** Fetches the app config, at most once per `cb_fetch_interval` seconds (default 3600, min 60). */
    @JvmStatic
    @JvmOverloads
    fun fetchAppConfig(onDone: Runnable? = null) {
        if (!isConfigured) { onDone?.let { Sdk.onMain { it.run() } }; return }
        val now = System.currentTimeMillis()
        val intervalMs = configLong("cb_fetch_interval", 3600).coerceAtLeast(60) * 1000
        if (lastFetchAt > 0 && now - lastFetchAt < intervalMs) { onDone?.let { Sdk.onMain { it.run() } }; return }
        lastFetchAt = now
        bg {
            var fetched = false
            try {
                val json = get("/api/app-config/$pkg") ?: return@bg
                val data = json.optJSONObject("data") ?: return@bg
                appData = data
                val ts = json.optLong("configUpdatedAt", 0)
                if (ts > 0) updatedAt = ts
                prefs().edit()
                    .putString(KEY_CONFIG_CACHE, data.toString())
                    .putLong(KEY_UPDATED_AT, updatedAt)
                    .apply()
                fetched = true
                SdkLog.i(TAG, "Config fetched: v=${data.optInt("appVersion")} active=${data.optBoolean("isActive")} " +
                    "configKeys=${config?.length()} adFormats=${ads?.length()} events=${events?.length()}")
            } catch (e: Exception) {
                SdkLog.w(TAG, "Config fetch: ${e.message}")
            } finally {
                Sdk.onMain {
                    if (fetched) configListeners.forEach { runCatching { it.onConfigUpdated() } }
                    onDone?.run()
                }
            }
        }
    }

    private fun onConfigUpdatedAt(serverTs: Long) {
        if (serverTs > updatedAt) {
            SdkLog.i(TAG, "configUpdatedAt moved ($updatedAt -> $serverTs), re-fetching")
            fetchAppConfig()
        }
    }

    private fun JSONObject?.checkUpdated() {
        this?.optLong("configUpdatedAt", 0)?.let { if (it > 0) onConfigUpdatedAt(it) }
    }

    // ---- Remote config accessors (panel only; see RemoteConfig for panel + local fallback) ----

    @JvmStatic fun configBool(key: String, default: Boolean): Boolean {
        val c = config ?: return default
        return if (c.has(key)) c.optBoolean(key, default) else default
    }

    @JvmStatic fun configLong(key: String, default: Long): Long {
        val c = config ?: return default
        return if (c.has(key)) c.optLong(key, default) else default
    }

    @JvmStatic @JvmOverloads fun configString(key: String, default: String = ""): String {
        val c = config ?: return default
        return if (c.has(key)) c.optString(key, default) else default
    }

    @JvmStatic fun configArray(key: String): JSONArray? = config?.optJSONArray(key)

    // ---- Remote ad unit IDs ----

    /** Ad unit IDs for a [format] ("banner", "fsads", "ntv", "open") and [placement] (e.g. "native_home"). */
    @JvmStatic fun adIds(format: String, placement: String): List<String> {
        val fmt = ads?.optJSONObject(format) ?: return emptyList()
        val arr = fmt.optJSONArray(placement) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
    }

    // ---- Active events ----

    /** True when the panel lists [key] in the app's active event keys. */
    @JvmStatic fun isEventActive(key: String): Boolean {
        val arr = events ?: return false
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return true
        return false
    }

    @JvmStatic fun activeEventKeys(): List<String> {
        val arr = events ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    // ---- Analytics: launch ping (/open) ----

    @JvmStatic
    @JvmOverloads
    fun sendOpen(source: String? = null, campaign: String? = null) {
        if (!isConfigured) return
        bg {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("appVersion", appVersion)
                if (!source.isNullOrEmpty()) {
                    put("source", JSONObject().apply {
                        put("channel", source)
                        if (!campaign.isNullOrEmpty()) put("campaign", campaign)
                    })
                }
            }
            post("/api/app-analytics/$pkg/open", body).checkUpdated()
        }
    }

    // ---- Analytics: event hits (/event) ----

    @JvmStatic fun sendEvent(event: String) {
        if (!isConfigured) return
        bg {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("event", event)
            }
            post("/api/app-analytics/$pkg/event", body).checkUpdated()
        }
    }

    /** Batch of (eventKey → count). Counts of 1 are sent without a "count" field. */
    @JvmStatic fun sendEvents(events: Map<String, Int>) {
        if (!isConfigured || events.isEmpty()) return
        bg {
            val arr = JSONArray()
            for ((key, count) in events) {
                arr.put(JSONObject().apply {
                    put("event", key)
                    if (count > 1) put("count", count)
                })
            }
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("events", arr)
            }
            post("/api/app-analytics/$pkg/event", body).checkUpdated()
        }
    }

    // ---- Analytics: ad revenue / impressions / clicks (/ad) ----

    @JvmStatic
    @JvmOverloads
    fun sendAdRevenue(
        format: String,
        network: String = "admob",
        event: String = "paid",
        position: String = "",
        adKey: String? = null,
        currency: String = "USD",
        revenueMicros: Long = 0,
        impressions: Int = 1,
        clicks: Int = 0
    ) {
        if (!isConfigured) return
        SdkLog.d(TAG, "ad: format=$format event=$event position=$position network=$network micros=$revenueMicros impr=$impressions clicks=$clicks")
        bg {
            val body = buildAdBody(deviceId, format, network, event, position, adKey, currency, revenueMicros, impressions, clicks)
            post("/api/app-analytics/$pkg/ad", body).checkUpdated()
        }
    }

    internal fun buildAdBody(
        deviceId: String, format: String, network: String, event: String, position: String,
        adKey: String?, currency: String, revenueMicros: Long, impressions: Int, clicks: Int
    ) = JSONObject().apply {
        put("deviceId", deviceId)
        put("format", format)
        put("network", network)
        put("event", event)
        put("position", position)
        if (adKey != null) put("adKey", adKey)
        put("currency", currency)
        put("revenueMicros", revenueMicros)
        if (impressions != 1) put("impressions", impressions)
        if (clicks > 0) put("clicks", clicks)
    }

    // ---- In-house ads (/app-inhouse) ----

    /** In-house (house) ad served by the panel. */
    class InHouseAd(
        val id: String,
        val adType: String,
        val medType: String?,
        val mediaUrl: String?,
        val title: String?,
        val ctaUrl: String?,
        /** Seconds before the close button appears. */
        val webStayTime: Int
    )

    /** Fetches the active in-house ad of [type] (e.g. "fs" for full screen). Delivers null when none. */
    @JvmStatic fun fetchInHouseAd(type: String, callback: ResultCallback<InHouseAd?>) {
        if (!isConfigured) { Sdk.onMain { callback.onResult(null) }; return }
        bg {
            var result: InHouseAd? = null
            try {
                val resp = get("/api/app-inhouse/$pkg/active?type=${URLEncoder.encode(type, "UTF-8")}")
                resp.checkUpdated()
                val d = resp?.takeIf { it.optBoolean("success", false) }?.optJSONObject("data")
                if (d != null) {
                    result = InHouseAd(
                        id = d.optString("id"),
                        adType = d.optString("adType"),
                        medType = d.str("medType"),
                        mediaUrl = d.str("mediaUrl"),
                        title = d.str("title"),
                        ctaUrl = d.str("ctaUrl"),
                        webStayTime = d.optInt("webStayTime", 5)
                    )
                }
            } catch (e: Exception) {
                SdkLog.w(TAG, "InHouse fetch: ${e.message}")
            }
            Sdk.onMain { callback.onResult(result) }
        }
    }

    @JvmStatic fun sendInHouseClick(adId: String) {
        if (!isConfigured || adId.isEmpty()) return
        bg { post("/api/app-inhouse/$pkg/click", JSONObject().put("adId", adId)).checkUpdated() }
    }

    // ---- Lifetime revenue (/revenue) ----

    class LifetimeRevenue(val amount: Double, val currency: String, val nativeAmount: Double, val rate: Double?)

    /** Lifetime ad revenue of this device as computed by the panel. Not called when unavailable. */
    @JvmStatic fun fetchLifetimeRevenue(callback: ResultCallback<LifetimeRevenue>) {
        if (!isConfigured) return
        bg {
            val resp = post("/api/app-analytics/$pkg/revenue", JSONObject().put("deviceId", deviceId)) ?: return@bg
            if (resp.optBoolean("success")) {
                val r = LifetimeRevenue(
                    resp.optDouble("amount", 0.0),
                    resp.optString("currency", "USD"),
                    resp.optDouble("native", 0.0),
                    if (resp.isNull("rate")) null else resp.optDouble("rate")
                )
                Sdk.onMain { callback.onResult(r) }
            }
        }
    }

    // ---- Legal (/policy, /terms) ----

    class LegalText(val text: String, val updatedAt: String?)

    @JvmStatic fun fetchPolicy(callback: ResultCallback<LegalText>) = fetchLegal("policy", callback)

    @JvmStatic fun fetchTerms(callback: ResultCallback<LegalText>) = fetchLegal("terms", callback)

    private fun fetchLegal(kind: String, callback: ResultCallback<LegalText>) {
        if (!isConfigured) return
        bg {
            val data = get("/api/app-config/$pkg/$kind")?.optJSONObject("data") ?: return@bg
            val text = LegalText(data.optString(kind, ""), data.str("updatedAt"))
            Sdk.onMain { callback.onResult(text) }
        }
    }

    // ---- HTTP ----

    private fun get(path: String): JSONObject? = request("GET", path, null)

    private fun post(path: String, body: JSONObject): JSONObject? = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("User-Agent", "AdsAnalytics-Android/${Build.VERSION.SDK_INT}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val ok = if (method == "GET") conn.responseCode == 200 else conn.responseCode in 200..299
            if (ok) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                SdkLog.w(TAG, "$method $path → ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            SdkLog.w(TAG, "$method $path failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun bg(block: () -> Unit) {
        Sdk.io.execute {
            try { block() } catch (e: Exception) { SdkLog.w(TAG, "background call failed: ${e.message}") }
        }
    }

    private fun prefs() = Sdk.prefs(PREFS)

    private fun JSONObject?.str(key: String): String? =
        this?.optString(key, "")?.takeIf { it.isNotEmpty() && it != "null" }
}
