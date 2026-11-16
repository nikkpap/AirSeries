plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version code comes from the CI build number (GitHub Actions run_number).
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "gr.nikkpap.airseries"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.nikkpap.airseries"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"nikkpap/AirSeries\"")
        // Movies: personal TMDB key from the GitHub secret TMDB_API_KEY (empty locally = movies tab shows a hint).
        // Trakt sync: app credentials from GitHub secrets (empty = Trakt section shows a hint).
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${(System.getenv("TRAKT_CLIENT_ID") ?: "").trim()}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${(System.getenv("TRAKT_CLIENT_SECRET") ?: "").trim()}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${(System.getenv("TMDB_API_KEY") ?: "").trim()}\"")
        // Phones only (ARM). Drops x86 copies of the translation engine (~35 MB).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    packaging {
        // Compress native libraries inside the APK -> much smaller download.
        jniLibs { useLegacyPackaging = true }
    }

    signingConfigs {
        // Fixed release key (from GitHub secrets) so OTA updates install over the old version.
        val ks = System.getenv("KEYSTORE_FILE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI: fixed release key. Local Android Studio build: debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mlkit.translate)
}
