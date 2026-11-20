package com.codemax.adsanalytics.analytics

import android.os.Bundle
import com.codemax.adsanalytics.AdEvent
import com.codemax.adsanalytics.AdEventType
import com.codemax.adsanalytics.AdFormat
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import com.codemax.adsanalytics.panel.PanelClient
import com.google.android.gms.ads.AdValue

/**
 * App analytics: Firebase Analytics events, Facebook App Events, panel event hits and ad revenue reporting.
 * Every call is safe without Firebase/Facebook/panel configured; missing destinations are skipped.
 */
object Analytics {
    private const val TAG = "Analytics"

    /** Firebase event name used for impression-level ad revenue. */
    const val EVENT_AD_IMPRESSION_REVENUE = "ad_impression_revenue"

    /** True when Firebase is on the classpath and a FirebaseApp exists (google-services.json present). */
    @JvmStatic
    fun isFirebaseAvailable(): Boolean = Sdk.contextOrNull()?.let { FirebaseBridge.isAvailable(it) } ?: false

    /** Logs a Firebase Analytics event. */
    @JvmStatic
    @JvmOverloads
    fun logEvent(name: String, params: Bundle? = null) {
        val ctx = Sdk.contextOrNull() ?: return
        if (!FirebaseBridge.isAvailable(ctx)) { SdkLog.d(TAG, "event $name (Firebase off)"); return }
        FirebaseBridge.logEvent(ctx, name, params)
    }

    /** Logs a Facebook App Event (requires the Facebook SDK in the host app). */
    @JvmStatic
    @JvmOverloads
    fun logFacebookEvent(name: String, params: Bundle? = null) {
        val ctx = Sdk.contextOrNull() ?: return
        if (Bridges.hasFacebook) FacebookBridge.logEvent(ctx, name, params)
    }

    /** Sends an event hit to the analytics panel. */
    @JvmStatic
    fun logPanelEvent(eventKey: String) = PanelClient.sendEvent(eventKey)

    /** Sends an event hit to the panel only when the panel lists [eventKey] as active. Returns whether it was sent. */
    @JvmStatic
    fun logPanelEventIfActive(eventKey: String): Boolean {
        if (!PanelClient.isEventActive(eventKey)) return false
        PanelClient.sendEvent(eventKey); return true
    }

    @JvmStatic
    fun setUserProperty(name: String, value: String?) {
        val ctx = Sdk.contextOrNull() ?: return
        if (FirebaseBridge.isAvailable(ctx)) FirebaseBridge.setUserProperty(ctx, name, value)
    }

    @JvmStatic
    fun setUserId(id: String?) {
        val ctx = Sdk.contextOrNull() ?: return
        if (FirebaseBridge.isAvailable(ctx)) FirebaseBridge.setUserId(ctx, id)
    }

    /** FCM time-zone topic for this device, e.g. "utc5.5". */
    @JvmStatic
    fun utcTopic(): String = FirebaseBridge.utcTopic(java.util.TimeZone.getDefault())

    /**
     * Reports one paid ad impression: Firebase "ad_impression_revenue" + panel `/ad` (revenue, 1 impression)
     * + [com.codemax.adsanalytics.AdEventListener]s. Called automatically for every ad the SDK loads;
     * call it yourself only for ads you load outside the SDK.
     */
    @JvmStatic
    fun logAdRevenue(adUnitId: String, adType: String, value: AdValue, network: String, screen: String, position: String) {
        try {
            val currency = value.currencyCode.takeIf { it.isNotEmpty() } ?: "USD"
            logEvent(EVENT_AD_IMPRESSION_REVENUE, Bundle().apply {
                putString("ad_unit_id", adUnitId)
                putString("ad_type", adType)
                putLong("ad_value_micros", value.valueMicros)
                putString("ad_currency", currency)
                putInt("ad_precision", value.precisionType)
                putString("ad_network", network)
                putString("ad_event", screen)
                putString("ad_position", position)
            })
            val format = AdFormat.fromAdType(adType)
            PanelClient.sendAdRevenue(
                format = format.wireName, network = network, event = screen, position = position,
                adKey = adUnitId, currency = currency, revenueMicros = value.valueMicros
            )
            AdEventBus.dispatch(AdEvent(
                AdEventType.PAID, format, adUnitId, network, screen, position,
                valueMicros = value.valueMicros, currencyCode = currency, precisionType = value.precisionType
            ))
        } catch (e: Exception) {
            SdkLog.w(TAG, "logAdRevenue failed: ${e.message}")
        }
    }

    /** Reports an ad click to the panel (`/ad` with clicks = 1, no revenue) and to listeners. */
    @JvmStatic
    fun logAdClick(adUnitId: String, format: AdFormat, network: String, screen: String, position: String) {
        try {
            PanelClient.sendAdRevenue(
                format = format.wireName, network = network, event = screen, position = position,
                adKey = adUnitId, clicks = 1
            )
        } catch (e: Exception) {
            SdkLog.w(TAG, "logAdClick failed: ${e.message}")
        }
        AdEventBus.dispatch(AdEvent(AdEventType.CLICKED, format, adUnitId, network, screen, position))
    }
}

/** Fan-out of [AdEvent]s to registered listeners, always on the main thread. */
internal object AdEventBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<com.codemax.adsanalytics.AdEventListener>()

    fun add(l: com.codemax.adsanalytics.AdEventListener) { listeners.addIfAbsent(l) }
    fun remove(l: com.codemax.adsanalytics.AdEventListener) { listeners.remove(l) }

    fun dispatch(event: AdEvent) {
        SdkLog.d("AdEvent", event.toString())
        if (listeners.isEmpty()) return
        Sdk.onMain { listeners.forEach { runCatching { it.onAdEvent(event) } } }
    }
}
