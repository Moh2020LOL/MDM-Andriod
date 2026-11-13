plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.mdm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mdm.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.6.0"
        // No ndk.abiFilters here: with `splits.abi` below AGP refuses both. Which processors get a
        // library is decided by the folders in src/main/jniLibs (tools/android/build-rust.sh makes all four).
    }

    // One APK per processor (each far smaller than all four together: youtubedl-android carries
    // Python, yt-dlp and ffmpeg for every one) PLUS a universal APK that runs on any of them.
    // The workflow checks that none is over 300 MB (.github/workflows/android.yml).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs on your own phone without a
            // keystore. For a store, put a real signingConfig here.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Only English and Arabic have every text; the other languages fall back to English.
    lint { disable += "MissingTranslation" }

    packaging {
        // youtubedl-android unpacks its Python and yt-dlp from the APK's native libraries.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
}
