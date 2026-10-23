plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versionsnummer kommt vom GitHub-Build (Laufnummer), lokal = 1
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val repoSlug = System.getenv("GITHUB_REPOSITORY") ?: "OWNER/REPO"

android {
    namespace = "com.example.mediathektv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.mediathektv"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"$repoSlug\"")
        buildConfigField("String", "GH_TOKEN", "\"${System.getenv("GH_UPDATE_TOKEN") ?: ""}\"")
    }
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "mediathek-tv"
            keyAlias = "mediathek"
            keyPassword = "mediathek-tv"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true          // R8: Code verschleiern + verkleinern
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
