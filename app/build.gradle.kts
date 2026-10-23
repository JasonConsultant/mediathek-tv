plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versionsnummer = Laufnummer des GitHub-Builds (lokal: 1)
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val repoSlug = System.getenv("GITHUB_REPOSITORY") ?: "JasonConsultant/mediathek-tv"

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
    }

    // Fester Schlüssel, damit Updates über bestehende Installationen gehen
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
            isMinifyEnabled = true          // R8: verkleinern + verschleiern
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // echtes org.json für JVM-Tests
}
