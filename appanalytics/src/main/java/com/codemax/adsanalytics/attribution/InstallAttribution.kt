package com.codemax.adsanalytics.attribution

import android.content.Context
import android.os.Bundle
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.codemax.adsanalytics.OnComplete
import com.codemax.adsanalytics.analytics.Analytics
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Install source from the Google Play Install Referrer API, read once per install and cached.
 * Organic when there is no utm_source, or the source/medium says organic. Logged to Firebase as
 * "install_attribution" and sent to the panel with the launch ping. This is reporting data only.
 */
object InstallAttribution {
    private const val TAG = "Attribution"
    private const val PREFS = "adsanalytics_install_attribution"
    private const val KEY_CHECKED = "checked"
    private const val KEY_ORGANIC = "is_organic"
    private const val KEY_SOURCE = "utm_source"
    private const val KEY_MEDIUM = "utm_medium"
    private const val KEY_CAMPAIGN = "utm_campaign"
    private const val KEY_RAW = "raw_referrer"

    private fun prefs() = Sdk.prefs(PREFS)

    /** True when the referrer has been read for this install. */
    @JvmStatic fun isChecked(): Boolean = prefs().getBoolean(KEY_CHECKED, false)
    /** True until a non-organic source is detected (defaults to organic). */
    @JvmStatic fun isOrganic(): Boolean = prefs().getBoolean(KEY_ORGANIC, true)
    @JvmStatic fun source(): String? = prefs().getString(KEY_SOURCE, null)
    @JvmStatic fun medium(): String? = prefs().getString(KEY_MEDIUM, null)
    @JvmStatic fun campaign(): String? = prefs().getString(KEY_CAMPAIGN, null)
    @JvmStatic fun rawReferrer(): String? = prefs().getString(KEY_RAW, null)

    /** Reads the referrer once after install. [onComplete] runs on the main thread when done (or at once if cached). */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, onComplete: OnComplete? = null) {
        val appContext = context.applicationContext
        val once = AtomicBoolean(false)
        val done = { if (once.compareAndSet(false, true)) onComplete?.let { cb -> Sdk.onMain { cb.onComplete() } } }
        if (isChecked()) { done(); return }
        val client = InstallReferrerClient.newBuilder(appContext).build()
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK ->
                            save(runCatching { client.installReferrer.installReferrer }.getOrNull())
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> save(null)
                        else -> SdkLog.w(TAG, "Install referrer unavailable (code $responseCode); will retry next start")
                    }
                    runCatching { client.endConnection() }
                    done()
                }

                override fun onInstallReferrerServiceDisconnected() = done()
            })
        }.onFailure {
            SdkLog.w(TAG, "Install referrer failed: ${it.message}")
            done()
        }
    }

    internal fun save(referrer: String?) {
        val r = ReferrerParser.parse(referrer)
        prefs().edit()
            .putBoolean(KEY_CHECKED, true)
            .putBoolean(KEY_ORGANIC, r.organic)
            .putString(KEY_SOURCE, r.source)
            .putString(KEY_MEDIUM, r.medium)
            .putString(KEY_CAMPAIGN, r.campaign)
            .putString(KEY_RAW, referrer)
            .apply()
        SdkLog.i(TAG, "organic=${r.organic} source=${r.source} medium=${r.medium} campaign=${r.campaign}")
        Analytics.logEvent("install_attribution", Bundle().apply {
            putBoolean("organic", r.organic)
            putString("utm_source", r.source ?: "")
            putString("utm_medium", r.medium ?: "")
            putString("utm_campaign", r.campaign ?: "")
        })
    }
}

/** Pure parser for Play install referrer strings like "utm_source=google-play&utm_medium=organic". */
object ReferrerParser {
    class Result(val source: String?, val medium: String?, val campaign: String?, val organic: Boolean)

    @JvmStatic
    fun parse(referrer: String?): Result {
        val params = HashMap<String, String>()
        referrer?.takeIf { it.isNotBlank() }?.split('&')?.forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val k = decode(pair.substring(0, idx))
            val v = decode(pair.substring(idx + 1))
            if (k !in params) params[k] = v
        }
        val source = params["utm_source"]?.takeIf { it.isNotEmpty() }
        val medium = params["utm_medium"]?.takeIf { it.isNotEmpty() }
        val campaign = params["utm_campaign"]?.takeIf { it.isNotEmpty() }
        val organic = source.isNullOrEmpty() ||
            source.equals("organic", true) ||
            medium.equals("organic", true) ||
            (source.equals("google-play", true) && (medium.isNullOrEmpty() || medium == "(not set)"))
        return Result(source, medium, campaign, organic)
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
