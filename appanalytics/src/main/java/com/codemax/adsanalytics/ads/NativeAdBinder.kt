package com.codemax.adsanalytics.ads

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.codemax.adsanalytics.R
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Binds a [NativeAd] to a [NativeAdView]. Works with any layout that uses the standard view IDs
 * `ad_headline`, `ad_body`, `ad_call_to_action`, `ad_app_icon`, `ad_advertiser`, `ad_media`
 * (all optional), so existing app layouts can be reused unchanged.
 */
object NativeAdBinder {
    @JvmStatic
    fun bind(ad: NativeAd, view: NativeAdView) {
        view.findViewById<TextView>(R.id.ad_headline)?.let { it.text = ad.headline; view.headlineView = it }
        view.findViewById<TextView>(R.id.ad_body)?.let {
            if (ad.body == null) it.visibility = View.INVISIBLE else it.text = ad.body
            view.bodyView = it
        }
        view.findViewById<TextView>(R.id.ad_call_to_action)?.let {
            if (ad.callToAction == null) it.visibility = View.INVISIBLE else it.text = ad.callToAction
            view.callToActionView = it
        }
        view.findViewById<ImageView>(R.id.ad_app_icon)?.let {
            val icon = ad.icon
            if (icon == null) it.visibility = View.GONE else it.setImageDrawable(icon.drawable)
            view.iconView = it
        }
        view.findViewById<TextView>(R.id.ad_advertiser)?.let {
            if (ad.advertiser == null) it.visibility = View.GONE else it.text = ad.advertiser
            view.advertiserView = it
        }
        view.findViewById<MediaView>(R.id.ad_media)?.let { view.mediaView = it }
        view.setNativeAd(ad)
    }
}
