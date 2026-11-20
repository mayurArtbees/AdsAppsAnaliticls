package com.codemax.adsanalytics.sample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.codemax.adsanalytics.AdsAnalytics
import com.codemax.adsanalytics.ads.AdsManager
import com.codemax.adsanalytics.ads.AppOpenManager
import com.codemax.adsanalytics.ads.InHouseAdActivity

/** Start-up order: consent (UMP) → Mobile Ads init → splash interstitial → in-house ad → main screen. */
class SplashActivity : AppCompatActivity() {

    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        AdsAnalytics.gatherConsentAndInitAds(this) {
            val firstRun = getSharedPreferences("sample", MODE_PRIVATE).getBoolean("first_run", true)
            AdsManager.showSplashInterstitial(this, showNativeFullNext = firstRun) { route() }
        }
    }

    private fun route() {
        if (routed || isFinishing) return
        routed = true
        getSharedPreferences("sample", MODE_PRIVATE).edit().putBoolean("first_run", false).apply()
        AppOpenManager.load(this) // ready for the next return from background
        InHouseAdActivity.startThenRoute(this, Intent(this, MainActivity::class.java))
        finish()
    }
}
