package com.codemax.adsanalytics.sample

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import com.codemax.adsanalytics.AdsAnalytics
import com.codemax.adsanalytics.R as SdkR
import com.codemax.adsanalytics.ads.AdsManager
import com.codemax.adsanalytics.ads.ConsentManager
import com.codemax.adsanalytics.analytics.Analytics
import com.codemax.adsanalytics.attribution.InstallAttribution
import com.codemax.adsanalytics.panel.PanelClient

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.tvLog)

        findViewById<TextView>(R.id.tvInfo).text = buildString {
            append("SDK ").append(AdsAnalytics.version)
            append("\nFirebase: ").append(if (Analytics.isFirebaseAvailable()) "on" else "off")
            append("\nPanel: ").append(if (PanelClient.isConfigured) "on" else "off (no URL/key)")
            append("\nInstall: ").append(if (InstallAttribution.isOrganic()) "organic" else "paid")
            append(" source=").append(InstallAttribution.source() ?: "-")
            append("\nCan show ads: ").append(AdsManager.canShowAds(this@MainActivity))
        }

        // Banner + native at the bottom, each behind its own Remote Config switch.
        AdsManager.loadBanner(this, findViewById(R.id.bannerContainer), key = "is_load_banner_home")
        AdsManager.loadNative(
            this,
            container = findViewById(R.id.nativeContainer),
            adFrame = findViewById<FrameLayout>(R.id.nativeFrame),
            layout = SdkR.layout.aa_native_medium,
            key = "is_load_native_home"
        )
        AdsManager.preloadInterstitial(this)

        button(R.id.btnInterstitial) {
            AdsManager.showInterstitial(this, key = "is_load_inter_home") { toast("next action (preloaded)") }
        }
        button(R.id.btnLoadAndShow) {
            AdsManager.loadAndShowInterstitial(this, key = "is_load_inter_tab", screenProvider = { "main_screen" }) {
                toast("next action (on demand)")
            }
        }
        button(R.id.btnNativeFull) {
            if (!AdsManager.showNativeFull(this)) toast("Full-screen native not ready yet")
        }
        button(R.id.btnEvent) {
            Analytics.logEvent("sample_button_click", bundleOf("button" to "event"))
            Analytics.logPanelEvent("sample_button_click")
            EventLog.add("logged sample_button_click")
        }
        button(R.id.btnRevenue) {
            PanelClient.fetchLifetimeRevenue { r -> toast("Lifetime revenue ${r.amount} ${r.currency}") }
            if (!PanelClient.isConfigured) toast("Panel not configured")
        }
        button(R.id.btnPremium) {
            val enable = !AdsManager.isAdsEnabled()
            AdsAnalytics.setAdsEnabled(enable)
            toast(if (enable) "Ads enabled" else "Ads disabled (premium)")
        }
        findViewById<Button>(R.id.btnPrivacy).apply {
            visibility = if (ConsentManager.isPrivacyOptionsRequired(this@MainActivity)) View.VISIBLE else View.GONE
            setOnClickListener { ConsentManager.showPrivacyOptions(this@MainActivity) }
        }
        button(R.id.btnJava) {
            AdsManager.loadAndShowInterstitial(this, key = "is_load_inter_java") {
                startActivity(Intent(this, JavaDemoActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.onChanged = { runOnUiThread { log.text = EventLog.text() } }
        log.text = EventLog.text()
    }

    override fun onPause() {
        EventLog.onChanged = null
        super.onPause()
    }

    private fun button(id: Int, onClick: () -> Unit) = findViewById<Button>(id).setOnClickListener { onClick() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
