package com.codemax.adsanalytics

import java.util.Locale

/** Ad format as reported to the analytics panel. */
enum class AdFormat(val wireName: String) {
    BANNER("banner"),
    INTERSTITIAL("interstitial"),
    NATIVE("native"),
    APP_OPEN("open"),
    REWARDED("rewarded");

    companion object {
        /** Maps a free-form ad type ("native_full", "Interstitial", ...) to a format; unknown → BANNER. */
        @JvmStatic
        fun fromAdType(adType: String): AdFormat = when {
            adType.contains("banner", true) -> BANNER
            adType.contains("interstitial", true) -> INTERSTITIAL
            adType.contains("native", true) -> NATIVE
            adType.contains("reward", true) -> REWARDED
            adType.contains("open", true) -> APP_OPEN
            else -> BANNER
        }
    }
}

/** Ad lifecycle event types delivered to [AdEventListener]. */
enum class AdEventType {
    LOAD_REQUESTED, LOADED, FAILED_TO_LOAD,
    SHOWN, FAILED_TO_SHOW, IMPRESSION, CLICKED, DISMISSED,
    /** Paid event (impression-level revenue). [AdEvent.valueMicros] / [AdEvent.currencyCode] are set. */
    PAID
}

/** One ad lifecycle event. Fields that don't apply to [type] are null/0. */
class AdEvent internal constructor(
    val type: AdEventType,
    val format: AdFormat,
    val adUnitId: String,
    /** Mediation network that served the ad ("admob", "meta", "applovin", ...), when known. */
    val network: String?,
    /** Screen the ad belongs to, e.g. "home_screen". */
    val screen: String,
    /** Screen plus slot, e.g. "home_screen:bottom". */
    val position: String,
    val valueMicros: Long = 0,
    val currencyCode: String? = null,
    val precisionType: Int = 0,
    val errorCode: Int? = null,
    val errorMessage: String? = null
) {
    /** Revenue in currency units (valueMicros / 1,000,000). */
    val value: Double get() = valueMicros / 1_000_000.0

    override fun toString(): String = String.format(
        Locale.ROOT, "AdEvent(%s %s unit=%s net=%s pos=%s value=%d %s err=%s)",
        type, format.wireName, adUnitId, network, position, valueMicros, currencyCode ?: "", errorMessage ?: ""
    )
}

/** Receives every ad lifecycle event on the main thread. Use it to forward revenue to AppsFlyer, Adjust, etc. */
fun interface AdEventListener {
    fun onAdEvent(event: AdEvent)
}

/** Generic "done" callback, Java-friendly. Always invoked on the main thread. */
fun interface OnComplete {
    fun onComplete()
}

/** Supplies the screen name used in revenue/click reports at the moment the event fires. */
fun interface ScreenNameProvider {
    fun screenName(): String
}

/** Callback with a single result value, invoked on the main thread. */
fun interface ResultCallback<T> {
    fun onResult(result: T)
}
