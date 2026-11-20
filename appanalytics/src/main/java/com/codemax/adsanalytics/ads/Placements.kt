package com.codemax.adsanalytics.ads

import java.util.Locale

/**
 * Placement key conventions. A placement key is a Remote Config switch such as "is_load_native_home";
 * its suffix ("home") selects the screen-specific ad unit ("native_home") and the reported screen ("home_screen").
 */
object Placements {
    private val PREFIXES = listOf("is_load_native_full_", "is_load_native_", "is_load_inter_", "is_load_banner_")

    /** "is_load_native_home" → "home"; null stays null. */
    @JvmStatic
    fun suffix(key: String?): String? {
        if (key == null) return null
        var s: String = key
        for (p in PREFIXES) s = s.removePrefix(p)
        return s
    }

    /** "is_load_native_home" → "home_screen"; null → "unknown". */
    @JvmStatic
    fun screenFromKey(key: String?): String {
        if (key == null) return "unknown"
        val screen = key.removePrefix("is_load_native_")
            .removePrefix("is_load_inter_")
            .removePrefix("is_load_banner_")
        return "${screen}_screen"
    }

    /** Clean ad network name from a mediation adapter class name, e.g. "admob", "meta", "applovin". */
    @JvmStatic
    fun networkFromAdapterClass(className: String?): String {
        if (className.isNullOrBlank()) return "admob"
        return when {
            className.contains("admob", true) -> "admob"
            className.contains("facebook", true) || className.contains("meta", true) -> "meta"
            className.contains("applovin", true) -> "applovin"
            className.contains("unity", true) -> "unity"
            className.contains("vungle", true) || className.contains("liftoff", true) -> "vungle"
            className.contains("ironsource", true) -> "ironsource"
            className.contains("inmobi", true) -> "inmobi"
            className.contains("chartboost", true) -> "chartboost"
            className.contains("mintegral", true) -> "mintegral"
            className.contains("pangle", true) -> "pangle"
            className.contains("adcolony", true) -> "adcolony"
            else -> {
                val parts = className.split(".")
                if (parts.size >= 2) parts[parts.size - 2].lowercase(Locale.ROOT) else "unknown"
            }
        }
    }
}

/** Interstitial frequency cap: true when [lastShownAt] is set and less than [intervalMs] has passed. */
internal fun withinFrequencyCap(lastShownAt: Long, now: Long, intervalMs: Long): Boolean =
    lastShownAt != 0L && now - lastShownAt < intervalMs
