plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    // Testing Dependencies
    testImplementation(libs.junit)
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

    // --- FIXED: Switched "teamNewPipe" to strictly lowercase "teamnewpipe" ---
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.24.4")

    // Required so NewPipeExtractor's use of java.time works on minSdk 24 (java.time is API 26+).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // --- CRITICAL ADDITION: Native JSON Engine footprint for MusicRepository parsing logic ---
    implementation("org.json:json:20240303")
}