package com.codemax.adsanalytics

import com.codemax.adsanalytics.ads.Placements
import com.codemax.adsanalytics.ads.withinFrequencyCap
import com.codemax.adsanalytics.analytics.FirebaseBridge
import com.codemax.adsanalytics.attribution.ReferrerParser
import com.codemax.adsanalytics.panel.PanelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PlacementsTest {
    @Test fun suffixStripsAllKnownPrefixes() {
        assertEquals("home", Placements.suffix("is_load_native_home"))
        assertEquals("splash", Placements.suffix("is_load_native_full_splash"))
        assertEquals("tab", Placements.suffix("is_load_inter_tab"))
        assertEquals("budget_detail", Placements.suffix("is_load_banner_budget_detail"))
        assertEquals("custom", Placements.suffix("custom"))
        assertNull(Placements.suffix(null))
    }

    @Test fun screenFromKeyMatchesOriginalReporting() {
        assertEquals("home_screen", Placements.screenFromKey("is_load_native_home"))
        assertEquals("tab_screen", Placements.screenFromKey("is_load_inter_tab"))
        // Original logic strips only "is_load_native_" for full-screen keys.
        assertEquals("full_splash_screen", Placements.screenFromKey("is_load_native_full_splash"))
        assertEquals("unknown", Placements.screenFromKey(null))
    }

    @Test fun networkNamesFromAdapterClasses() {
        assertEquals("admob", Placements.networkFromAdapterClass(null))
        assertEquals("admob", Placements.networkFromAdapterClass(""))
        assertEquals("admob", Placements.networkFromAdapterClass("com.google.ads.mediation.admob.AdMobAdapter"))
        assertEquals("meta", Placements.networkFromAdapterClass("com.google.ads.mediation.facebook.FacebookMediationAdapter"))
        assertEquals("applovin", Placements.networkFromAdapterClass("com.google.ads.mediation.applovin.AppLovinMediationAdapter"))
        assertEquals("vungle", Placements.networkFromAdapterClass("com.google.ads.mediation.vungle.VungleMediationAdapter"))
        assertEquals("pangle", Placements.networkFromAdapterClass("com.google.ads.mediation.pangle.PangleMediationAdapter"))
        assertEquals("othernet", Placements.networkFromAdapterClass("com.example.othernet.Adapter"))
        assertEquals("unknown", Placements.networkFromAdapterClass("Adapter"))
    }

    @Test fun frequencyCap() {
        assertFalse(withinFrequencyCap(0L, 1_000L, 15_000L))
        assertTrue(withinFrequencyCap(10_000L, 20_000L, 15_000L))
        assertFalse(withinFrequencyCap(10_000L, 25_000L, 15_000L))
    }
}

class AdFormatTest {
    @Test fun adTypeNormalisation() {
        assertEquals(AdFormat.BANNER, AdFormat.fromAdType("banner"))
        assertEquals(AdFormat.INTERSTITIAL, AdFormat.fromAdType("Interstitial"))
        assertEquals(AdFormat.NATIVE, AdFormat.fromAdType("native_full"))
        assertEquals(AdFormat.REWARDED, AdFormat.fromAdType("rewarded"))
        // Same precedence as the original: "interstitial" wins over "reward".
        assertEquals(AdFormat.INTERSTITIAL, AdFormat.fromAdType("rewarded_interstitial"))
        assertEquals(AdFormat.APP_OPEN, AdFormat.fromAdType("open"))
        assertEquals(AdFormat.BANNER, AdFormat.fromAdType("something"))
        assertEquals("open", AdFormat.APP_OPEN.wireName)
    }
}

class ReferrerParserTest {
    @Test fun emptyReferrerIsOrganic() {
        assertTrue(ReferrerParser.parse(null).organic)
        assertTrue(ReferrerParser.parse("").organic)
    }

    @Test fun googlePlayOrganic() {
        val r = ReferrerParser.parse("utm_source=google-play&utm_medium=organic")
        assertTrue(r.organic)
        assertEquals("google-play", r.source)
        assertTrue(ReferrerParser.parse("utm_source=google-play&utm_medium=(not%20set)").organic)
        assertTrue(ReferrerParser.parse("utm_source=google-play").organic)
    }

    @Test fun paidCampaign() {
        val r = ReferrerParser.parse("utm_source=google-ads&utm_medium=cpc&utm_campaign=Summer%20Sale")
        assertFalse(r.organic)
        assertEquals("google-ads", r.source)
        assertEquals("cpc", r.medium)
        assertEquals("Summer Sale", r.campaign)
    }

    @Test fun malformedPairsIgnored() {
        val r = ReferrerParser.parse("garbage&=x&utm_source=fb")
        assertEquals("fb", r.source)
        assertFalse(r.organic)
    }
}

class FirebaseTopicTest {
    @Test fun utcTopics() {
        assertEquals("utc5.5", FirebaseBridge.utcTopic(TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals("utc0", FirebaseBridge.utcTopic(TimeZone.getTimeZone("UTC")))
        assertEquals("utc-3", FirebaseBridge.utcTopic(TimeZone.getTimeZone("America/Sao_Paulo")))
    }
}

class PanelAdBodyTest {
    @Test fun impressionWithRevenue() {
        val body = PanelClient.buildAdBody("dev", "native", "admob", "home_screen", "home_screen:bottom",
            "unit-1", "USD", 1234L, impressions = 1, clicks = 0)
        assertEquals("dev", body.getString("deviceId"))
        assertEquals(1234L, body.getLong("revenueMicros"))
        assertEquals("unit-1", body.getString("adKey"))
        // impressions == 1 and clicks == 0 are omitted (server defaults).
        assertFalse(body.has("impressions"))
        assertFalse(body.has("clicks"))
    }

    @Test fun clickHasNoRevenue() {
        val body = PanelClient.buildAdBody("dev", "banner", "meta", "s", "s:bottom", null, "USD", 0L, 1, 1)
        assertEquals(1, body.getInt("clicks"))
        assertEquals(0L, body.getLong("revenueMicros"))
        assertFalse(body.has("adKey"))
    }
}

class ConfigTest {
    @Test fun builderDefaultsAndFloors() {
        val c = AdsAnalyticsConfig.Builder()
            .panel("https://panel.example.com/", "k")
            .defaultInterstitialIntervalSeconds(5)
            .nativeFullCountdownSeconds(-1)
            .build()
        assertEquals("https://panel.example.com", c.panelBaseUrl)
        assertEquals(15L, c.defaultInterstitialIntervalSeconds)
        assertEquals(0, c.nativeFullCountdownSeconds)
        assertFalse(c.loggingEnabled)
        assertTrue(AdsAnalyticsConfig.Builder().debug(true).build().loggingEnabled)
        assertTrue(c.appOpenAdsEnabled)
    }
}
