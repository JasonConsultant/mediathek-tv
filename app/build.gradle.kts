plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versionsnummer = Laufnummer des GitHub-Builds (lokal: 1)
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val repoSlug = System.getenv("GITHUB_REPOSITORY") ?: "JasonConsultant/mediathek-tv"

android {
    namespace = "com.example.mediathektv"
    compileSdk = 37

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.example.mediathektv"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "2.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"$repoSlug\"")
        buildConfigField("String", "DEEP_LINK_SCHEME", "\"mediathektv\"")
        manifestPlaceholders["playbackScheme"] = "mediathektv"
    }

    // Signierschlüssel kommt aus den GitHub-Secrets (nie ins Repo!). Lokal ohne Schlüssel: Debug-Signatur.
    val keystorePath = System.getenv("RELEASE_KEYSTORE")
    val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystorePath!!)
            storePassword = keystorePassword
            keyAlias = "mediathek"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8: verkleinern + verschleiern
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
    testOptions.unitTests.isIncludeAndroidResources = true   // Oberflächen-Tests mit Robolectric
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.compose.material:material-icons-extended")   // Symbole der Seitenleiste
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")  // Rückkehr in die App erkennen
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")          // App im Vorder-/Hintergrund (für "Neu")
    implementation("androidx.work:work-runtime-ktx:2.12.0")                // Nachladen im Hintergrund
    implementation("androidx.tvprovider:tvprovider:1.1.0")                // Startbildschirm "Weiterschauen"

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.google.code.gson:gson:2.15.0")   // nur der Streaming-JsonReader
    implementation("io.coil-kt:coil-compose:2.7.0")      // Vorschaubilder
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    testImplementation("junit:junit:4.13.2")
    // Geräte-Test auf dem Android-TV-Emulator (GitHub): echte Fernbedienungstasten, echte Wiedergabe
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    testImplementation("org.json:json:20240303")   // echtes org.json für JVM-Tests
    testImplementation("org.robolectric:robolectric:4.17")        // Oberfläche auf dem JVM durchklicken
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
