plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "raf.console.quran7hours"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.quran7hours.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.4"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    androidResources {
        // The complete Mushaf pack is committed/copied into src/main/assets.
        // Gradle performs ZERO network/Python work. Keep fonts/media directly
        // addressable by AssetManager / Media3 for the fastest runtime access.
        noCompress += listOf("m4a", "mp3", "ttf", "otf", "json")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
}

/*
 * IMPORTANT: offline Mushaf assets are NOT prepared by Gradle.
 *
 * Before the first build of a fresh checkout run once on Windows:
 *
 *   powershell -ExecutionPolicy Bypass -File tools/install_quran_offline_pack.ps1
 *
 * Optional verification:
 *
 *   powershell -ExecutionPolicy Bypass -File tools/verify_quran_offline_pack.ps1
 *
 * After that every normal Sync / Run / assembleDebug is completely local.
 */
