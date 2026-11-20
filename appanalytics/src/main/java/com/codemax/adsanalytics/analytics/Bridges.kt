package com.codemax.adsanalytics.analytics

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.codemax.adsanalytics.internal.SdkLog
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** True when [className] is on the runtime classpath (optional dependency check). */
internal fun classPresent(className: String): Boolean = try {
    Class.forName(className, false, Bridges::class.java.classLoader); true
} catch (_: Throwable) { false }

internal object Bridges {
    val hasFirebaseAnalytics: Boolean by lazy { classPresent("com.google.firebase.analytics.FirebaseAnalytics") }
    val hasFirebaseMessaging: Boolean by lazy { classPresent("com.google.firebase.messaging.FirebaseMessaging") }
    val hasFacebook: Boolean by lazy { classPresent("com.facebook.FacebookSdk") }
}

/**
 * Firebase access. Only touched after [Bridges] confirms the classes exist, so the SDK works in apps
 * without Firebase. Firebase also needs the host's google-services.json; without it there is no FirebaseApp.
 */
internal object FirebaseBridge {
    private const val TAG = "Firebase"

    fun isAvailable(context: Context): Boolean = Bridges.hasFirebaseAnalytics && try {
        com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Throwable) { false }

    fun logEvent(context: Context, name: String, params: Bundle?) {
        try {
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(context).logEvent(name, params)
        } catch (t: Throwable) { SdkLog.w(TAG, "logEvent($name) failed: ${t.message}") }
    }

    fun setUserProperty(context: Context, name: String, value: String?) {
        try {
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(context).setUserProperty(name, value)
        } catch (t: Throwable) { SdkLog.w(TAG, "setUserProperty failed: ${t.message}") }
    }

    fun setUserId(context: Context, id: String?) {
        try {
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(context).setUserId(id)
        } catch (t: Throwable) { SdkLog.w(TAG, "setUserId failed: ${t.message}") }
    }

    /** "release_devices" or "debug_device", plus the "utc±N" time-zone topic. */
    fun subscribeTopics(debug: Boolean) {
        if (!Bridges.hasFirebaseMessaging) return
        try {
            val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()
            messaging.subscribeToTopic(if (debug) "debug_device" else "release_devices")
            messaging.subscribeToTopic(utcTopic(TimeZone.getDefault()))
                .addOnFailureListener { SdkLog.w(TAG, "Topic subscribe failed: ${it.message}") }
        } catch (t: Throwable) { SdkLog.w(TAG, "subscribeTopics failed: ${t.message}") }
    }

    /**
     * Time-zone topic so a push can be sent to one region at a sensible local time.
     * Topic names only allow [a-zA-Z0-9-_.~%]: "utc5.5" (India), "utc0", "utc-3".
     */
    fun utcTopic(zone: TimeZone): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(zone.rawOffset.toLong())
        val hours = minutes / 60.0
        val text = if (hours % 1.0 == 0.0) hours.toLong().toString() else hours.toString()
        return "utc$text"
    }
}

/** Facebook App Events. Only touched after [Bridges.hasFacebook]. */
internal object FacebookBridge {
    private const val TAG = "Facebook"

    fun init(app: Application, appId: String, clientToken: String?) {
        try {
            com.facebook.FacebookSdk.setApplicationId(appId)
            if (!clientToken.isNullOrEmpty()) com.facebook.FacebookSdk.setClientToken(clientToken)
            com.facebook.FacebookSdk.setAutoInitEnabled(true)
            com.facebook.FacebookSdk.fullyInitialize()
            com.facebook.appevents.AppEventsLogger.activateApp(app)
            SdkLog.i(TAG, "Facebook SDK initialised")
        } catch (t: Throwable) { SdkLog.w(TAG, "init failed: ${t.message}") }
    }

    fun logEvent(context: Context, name: String, params: Bundle?) {
        try {
            com.facebook.appevents.AppEventsLogger.newLogger(context).logEvent(name, params)
        } catch (t: Throwable) { SdkLog.w(TAG, "logEvent failed: ${t.message}") }
    }
}
