package com.example.sonara

import android.app.Application
import com.example.sonara.network.NewPipeDownloaderImpl
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
        NewPipe.init(
            NewPipeDownloaderImpl.getInstance(),
            Localization("en", "US")
        )
    }
}
