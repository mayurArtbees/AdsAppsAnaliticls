package com.codemax.adsanalytics.sample;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.codemax.adsanalytics.AdEventListener;
import com.codemax.adsanalytics.AdEventType;
import com.codemax.adsanalytics.AdsAnalytics;
import com.codemax.adsanalytics.ads.AdsManager;
import com.codemax.adsanalytics.analytics.Analytics;
import com.codemax.adsanalytics.config.RemoteConfig;
import com.codemax.adsanalytics.panel.PanelClient;

/** The same SDK used from Java: static calls, overloads and single-method callbacks. */
public class JavaDemoActivity extends AppCompatActivity {

    private final AdEventListener clickListener = event -> {
        if (event.getType() == AdEventType.CLICKED) {
            Toast.makeText(this, "Clicked " + event.getFormat().getWireName(), Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_demo);

        TextView info = findViewById(R.id.tvJavaInfo);
        info.setText("Device ID: " + PanelClient.getDeviceId()
                + "\nInterstitial interval: " + RemoteConfig.getLong("interval_show_interstitial", 15) + " s"
                + "\nInterstitial unit: " + AdsManager.getInterstitialUnitId());

        AdsManager.loadNative(this, findViewById(R.id.javaNativeContainer),
                (FrameLayout) findViewById(R.id.javaNativeFrame));
        AdsManager.loadBanner(this, findViewById(R.id.javaBanner), "is_load_banner_java");

        findViewById(R.id.btnJavaInterstitial).setOnClickListener(v ->
                AdsManager.showInterstitial(this, "is_load_inter_java", () -> "java_screen",
                        () -> Toast.makeText(this, "Continue after ad", Toast.LENGTH_SHORT).show()));

        findViewById(R.id.btnJavaEvent).setOnClickListener(v -> {
            Bundle params = new Bundle();
            params.putString("source", "java");
            Analytics.logEvent("java_demo_event", params);
            AdsAnalytics.logEvent("java_demo_event_short");
        });

        AdsAnalytics.addAdEventListener(clickListener);
    }

    @Override
    protected void onDestroy() {
        AdsAnalytics.removeAdEventListener(clickListener);
        super.onDestroy();
    }
}
