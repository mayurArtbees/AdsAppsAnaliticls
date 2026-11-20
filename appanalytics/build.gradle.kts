plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.codemax"
version = "1.0.0"

android {
    namespace = "com.codemax.adsanalytics"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.installreferrer)

    // Exposed to the host app: AdValue / ResponseInfo / LoadAdError appear in the public API.
    api(libs.play.services.ads)
    api(libs.ump)

    // Optional integrations. The SDK detects them at runtime; the host app adds them if it wants them.
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.analytics)
    compileOnly(libs.firebase.messaging)
    compileOnly(libs.facebook.core)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.codemax"
                artifactId = "adsanalytics"
                version = project.version.toString()
            }
        }
    }
}
