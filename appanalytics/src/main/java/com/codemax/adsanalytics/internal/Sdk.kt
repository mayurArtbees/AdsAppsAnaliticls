package com.codemax.adsanalytics.internal

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.codemax.adsanalytics.AdsAnalyticsConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Process-wide SDK state. */
internal object Sdk {
    @Volatile private var ctx: Context? = null
    @Volatile var config: AdsAnalyticsConfig = AdsAnalyticsConfig.Builder().build()
        private set

    val mainHandler = Handler(Looper.getMainLooper())

    /** Background executor for panel HTTP calls (bounded, daemon threads). */
    val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "adsanalytics-io").apply { isDaemon = true }
    }

    val isInitialized: Boolean get() = ctx != null

    val context: Context
        get() = ctx ?: error("AdsAnalytics.initialize(application, config) must be called first")

    fun contextOrNull(): Context? = ctx

    fun setup(context: Context, config: AdsAnalyticsConfig) {
        ctx = context.applicationContext
        this.config = config
    }

    fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Runs [block] on the main thread (immediately when already there). */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun isNetworkAvailable(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true // never block ads on a failed check
    }
}

internal object SdkLog {
    private const val PREFIX = "AdsAnalytics/"
    private val enabled get() = Sdk.config.loggingEnabled

    fun d(tag: String, msg: String) { if (enabled) Log.d(PREFIX + tag, msg) }
    fun i(tag: String, msg: String) { if (enabled) Log.i(PREFIX + tag, msg) }
    fun w(tag: String, msg: String) { if (enabled) Log.w(PREFIX + tag, msg) }
}
