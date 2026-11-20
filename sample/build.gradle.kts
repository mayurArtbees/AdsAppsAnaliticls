plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.codemax.adsanalytics.sample"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.codemax.adsanalytics.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Google's public AdMob test app ID. Replace with your own in a real app.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        // Analytics panel. Leave empty to run the sample without a panel (panel calls become no-ops).
        buildConfigField("String", "PANEL_BASE_URL", "\"\"")
        buildConfigField("String", "PANEL_API_KEY", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so the minified sample can be installed for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":appanalytics"))
    implementation(libs.androidx.appcompat)
    // Optional integrations the SDK picks up automatically when present:
    // implementation(platform(libs.firebase.bom)); implementation(libs.firebase.analytics)  // + google-services.json
    // implementation(libs.facebook.core)
}
