plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.sonara"
    compileSdk = 36 // Cleaned up the nested release block structure to prevent compilation quirks

    defaultConfig {
        applicationId = "com.example.sonara"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // standard DSL format for turning off minification/optimization structures
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core Dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Firebase & Auth Infrastructure
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.play.services.auth)

    // Compose Platform BOM & UI Components
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Material Design Extended Icons for Spotify layout actions
    implementation(libs.androidx.compose.material.icons.extended)

    // --- SONARA AUDIO PLAYBACK, SESSION, & DOWNLOAD ENGINE ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.datasource)

    // --- ROOM PERSISTENCE (Queue, Liked Songs, Playlists, Recents) ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Testing Dependencies
    testImplementation(libs.junit)
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- SONARA NETWORKING & MEDIA UTILITIES ---
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0") // Jetpack Compose async image loading library

    // --- NewPipeExtractor: v0.26.3 (latest, released June 2026)
    // Includes SABR workaround and signature deobfuscation fixes ---
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")

    // Required so NewPipeExtractor's use of java.time works on minSdk 24 (java.time is API 26+).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}

configurations.all {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-javalite:3.25.1")
        force("com.google.firebase:protolite-well-known-types:18.0.0")
    }
}
