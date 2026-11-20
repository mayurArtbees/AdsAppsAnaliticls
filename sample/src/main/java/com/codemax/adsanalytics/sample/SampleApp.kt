package com.codemax.adsanalytics.sample

import android.app.Application
import android.util.Log
import com.codemax.adsanalytics.AdEventType
import com.codemax.adsanalytics.AdsAnalytics
import com.codemax.adsanalytics.AdsAnalyticsConfig

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        AdsAnalytics.initialize(
            this,
            AdsAnalyticsConfig.Builder()
                .panel(BuildConfig.PANEL_BASE_URL, BuildConfig.PANEL_API_KEY)
                .defaultAdUnits(AdsAnalyticsConfig.AdUnits.GOOGLE_TEST)
                .excludeFromAppOpen(SplashActivity::class.java)
                .excludeFromNativeFull(SplashActivity::class.java)
                .trackScreenViews(true)
                .debug(BuildConfig.DEBUG)
                .build()
        )

        // Every ad event (loads, impressions, clicks, paid events) is available here,
        // e.g. to forward impression-level revenue to AppsFlyer / Adjust.
        AdsAnalytics.addAdEventListener { event ->
            EventLog.add(event.toString())
            if (event.type == AdEventType.PAID) {
                Log.i("SampleApp", "revenue ${event.value} ${event.currencyCode} from ${event.network} @ ${event.position}")
            }
        }
    }
}

/** In-memory log shown on the main screen. */
object EventLog {
    private val lines = ArrayDeque<String>()
    var onChanged: (() -> Unit)? = null

    fun add(line: String) {
        lines.addFirst(line)
        while (lines.size > 50) lines.removeLast()
        onChanged?.invoke()
    }

    fun text(): String = lines.joinToString("\n")
}
