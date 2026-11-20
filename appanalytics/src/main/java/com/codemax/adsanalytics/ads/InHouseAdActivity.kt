package com.codemax.adsanalytics.ads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.codemax.adsanalytics.R
import com.codemax.adsanalytics.internal.Sdk
import com.codemax.adsanalytics.internal.SdkLog
import com.codemax.adsanalytics.panel.PanelClient
import java.net.URL

/**
 * Full-screen in-house ad served by the panel (`/api/app-inhouse/{pkg}/active?type=fs`).
 * Shows the creative with a countdown (panel `webStayTime`, 1..30 s), then a close button.
 * Tapping the creative records a click and opens the CTA URL. Afterwards the next screen is started.
 * With no ad available it goes straight to the next screen.
 */
class InHouseAdActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.aa_activity_inhouse_ad)

        val imgCreative = findViewById<ImageView>(R.id.aa_inhouse_creative)
        val btnClose = findViewById<ImageButton>(R.id.aa_inhouse_close)
        val tvTitle = findViewById<TextView>(R.id.aa_inhouse_title)
        val tvCountdown = findViewById<TextView>(R.id.aa_inhouse_countdown)
        val progress = findViewById<ProgressBar>(R.id.aa_inhouse_progress)

        btnClose.visibility = View.GONE
        tvCountdown.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        PanelClient.fetchInHouseAd(intent.getStringExtra(EXTRA_TYPE) ?: TYPE_FULL_SCREEN) { ad ->
            if (isFinishing || isDestroyed) return@fetchInHouseAd
            progress.visibility = View.GONE
            if (ad == null || ad.mediaUrl.isNullOrEmpty()) { routeNext(); return@fetchInHouseAd }
            tvTitle.text = ad.title.orEmpty()
            tvTitle.visibility = if (ad.title.isNullOrEmpty()) View.GONE else View.VISIBLE

            Sdk.io.execute {
                try {
                    val bmp = URL(ad.mediaUrl).openStream().use { BitmapFactory.decodeStream(it) }
                    runOnUiThread { if (bmp != null) imgCreative.setImageBitmap(bmp) else routeNext() }
                } catch (e: Exception) {
                    SdkLog.w("InHouseAd", "Image load: ${e.message}")
                    runOnUiThread { routeNext() }
                }
            }

            startCountdown(ad.webStayTime.coerceIn(1, 30), tvCountdown, btnClose)

            imgCreative.setOnClickListener {
                ad.ctaUrl?.let { url ->
                    PanelClient.sendInHouseClick(ad.id)
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                }
                routeNext()
            }
            btnClose.setOnClickListener { routeNext() }
        }
    }

    private fun routeNext() {
        if (routed) return
        routed = true
        IntentCompat.getParcelableExtra(intent, EXTRA_NEXT, Intent::class.java)?.let {
            try { startActivity(it) } catch (e: Exception) { SdkLog.w("InHouseAd", "next screen: ${e.message}") }
        }
        finish()
    }

    private fun startCountdown(seconds: Int, tvCountdown: TextView, btnClose: ImageButton) {
        var remaining = seconds
        tvCountdown.text = remaining.toString()
        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    tvCountdown.visibility = View.GONE
                    btnClose.visibility = View.VISIBLE
                } else {
                    tvCountdown.text = remaining.toString()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back behaves like close once the close button is visible.
        if (findViewById<View>(R.id.aa_inhouse_close).visibility == View.VISIBLE) routeNext()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NEXT = "com.codemax.adsanalytics.next_intent"
        private const val EXTRA_TYPE = "com.codemax.adsanalytics.inhouse_type"
        const val TYPE_FULL_SCREEN = "fs"

        /** Shows the in-house ad (if the panel has one), then starts [nextIntent]. */
        @JvmStatic
        @JvmOverloads
        fun startThenRoute(context: Context, nextIntent: Intent, type: String = TYPE_FULL_SCREEN) {
            context.startActivity(Intent(context, InHouseAdActivity::class.java).apply {
                putExtra(EXTRA_NEXT, nextIntent)
                putExtra(EXTRA_TYPE, type)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
