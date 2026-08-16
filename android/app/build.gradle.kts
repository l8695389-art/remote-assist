plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.remoteassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.remoteassist"
        minSdk = 26          // MediaProjection foreground service type requires 29+ at runtime; app targets 26+ with runtime checks
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // WebRTC prebuilt AAR (screen capture + peer connection + data channel APIs).
    // Published mirror of Google's official WebRTC Android build.
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // Signaling transport
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for async signaling / session handling
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
