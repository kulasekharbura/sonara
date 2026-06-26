package com.example.sonara

import android.app.Application
import com.example.sonara.auth.YouTubeAuthManager
import com.example.sonara.network.NewPipeDownloaderImpl
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Application entry point. We initialise NewPipeExtractor exactly once here, before any UI or
 * playback code runs, so the very first stream extraction already has a configured downloader.
 *
 * [NewPipe.init] is the required bootstrap: without it any call to StreamInfo.getInfo(...) throws
 * because the library has no way to perform HTTP requests.
 */
class SonaraApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize YouTube authentication manager
        YouTubeAuthManager.init(this)

        // CRITICAL: Configure Firestore BEFORE any other code accesses it.
        try {
            // Enable Firestore debug logging to see gRPC stream errors
            FirebaseFirestore.setLoggingEnabled(true)

            val db = FirebaseFirestore.getInstance("default")
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
            db.firestoreSettings = settings
            android.util.Log.d("SONARA_INIT", "Firestore configured: persistence=OFF, database=default")
        } catch (e: Exception) {
            android.util.Log.e("SONARA_INIT", "Failed to configure Firestore settings: ${e.message}")
        }

        NewPipe.init(
            NewPipeDownloaderImpl.getInstance(),
            Localization("en", "US")
        )
    }
}
