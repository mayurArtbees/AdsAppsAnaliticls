package com.codemax.adsanalytics.ads

import android.app.Activity
import android.content.Context
import com.codemax.adsanalytics.OnComplete
import com.codemax.adsanalytics.internal.Sdk
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google UMP consent flow. Ads load only after [canRequestAds] is true; "no consent info yet" is
 * not treated as allowed, so call [gather] (usually on the splash screen) before initialising ads.
 */
object ConsentManager {
    private fun info(context: Context): ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    @JvmStatic
    fun canRequestAds(context: Context): Boolean = info(context).canRequestAds()

    /** Updates consent info and shows the form when required. [onDone] runs exactly once, on the main thread. */
    @JvmStatic
    fun gather(activity: Activity, onDone: OnComplete) {
        val cfg = Sdk.config
        val params = ConsentRequestParameters.Builder().apply {
            if (cfg.debug) {
                setConsentDebugSettings(ConsentDebugSettings.Builder(activity).apply {
                    cfg.consentDebugGeography?.let { setDebugGeography(it) }
                    cfg.consentTestDeviceHashedIds.forEach { addTestDeviceHashedId(it) }
                }.build())
            }
        }.build()
        val done = AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) Sdk.onMain { onDone.onComplete() } }
        info(activity).requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { finish() }
        }, { finish() })
    }

    /** True when a "Privacy settings" entry must be offered in the app's settings. */
    @JvmStatic
    fun isPrivacyOptionsRequired(context: Context): Boolean =
        info(context).privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    @JvmStatic
    @JvmOverloads
    fun showPrivacyOptions(activity: Activity, onDismissed: OnComplete? = null) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDismissed?.onComplete() }
    }

    /** Clears consent state (testing only). */
    @JvmStatic
    fun reset(context: Context) = info(context).reset()
}
