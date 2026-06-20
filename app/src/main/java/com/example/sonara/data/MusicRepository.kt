package com.example.sonara.data

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.example.sonara.playback.PlaybackService
import com.example.sonara.network.InnerTubeClient
import com.example.sonara.network.PipedClient
import com.example.sonara.network.RetrofitClient
import com.example.sonara.network.InvidiousSearchItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem: StateFlow<MediaItem?> = _currentMediaItem.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Main)

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks/",
        "https://pipedapi.leptons.xyz/",
        "https://pipedapi.nosebs.ru/",
        "https://pipedapi-libre.kavin.rocks/",
        "https://piped-api.privacy.com.de/",
        "https://pipedapi.adminforge.de/",
        "https://api.piped.yt/",
        "https://pipedapi.drgns.space/"
    )

    init {
        initializeController()
        startProgressTicker()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            setupControllerListener()
        }, MoreExecutors.directExecutor())
    }

    private fun setupControllerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItem.value = mediaItem
            }
        })

        _isPlaying.value = mediaController?.isPlaying == true
        _currentMediaItem.value = mediaController?.currentMediaItem
    }

    private fun startProgressTicker() {
        repositoryScope.launch {
            while (true) {
                try {
                    val controller = mediaController
                    if (controller != null && controller.isPlaying) {
                        _currentPosition.value = controller.currentPosition
                        _duration.value = controller.duration.coerceAtLeast(0L)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(500)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    suspend fun searchWithDynamicUrl(url: String, query: String): List<InvidiousSearchItem> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.searchTracks(dynamicUrl = url, searchQuery = query)
    }

    /**
     * Piped-first stream resolution with NewPipeExtractor fallback.
     *
     * Iterates through public Piped API instances to fetch a ready-to-play audio stream URL.
     * Each instance is tried in order; on success the best audio stream is selected via
     * AudioStreamSelector. On failure (network error, empty streams), the next instance is tried.
     *
     * If all Piped instances fail, NewPipeExtractor is used as a last-resort fallback.
     * If all methods fail, returns empty string. Never throws exceptions to the caller.
     */
    suspend fun fetchAudioStreamLink(videoId: String): String = withContext(Dispatchers.IO) {
        try {
            // Phase 0: YouTube Inner Tube API (most reliable - direct from YouTube)
            android.util.Log.d("SONARA_CORE", "Starting stream resolution for $videoId...")
            android.util.Log.d("SONARA_CORE", "Auth status: isLoggedIn=${com.example.sonara.auth.YouTubeAuthManager.isLoggedIn()}, cookieLength=${com.example.sonara.auth.YouTubeAuthManager.getCookies().length}")
            try {
                val innerTubeUrl = InnerTubeClient.getStreamUrl(videoId)
                if (!innerTubeUrl.isNullOrEmpty()) {
                    android.util.Log.d("SONARA_CORE", "InnerTube resolved audio stream for $videoId (url length=${innerTubeUrl.length}).")
                    return@withContext innerTubeUrl
                } else {
                    android.util.Log.w("SONARA_CORE", "InnerTube returned null/empty for $videoId. Trying Piped...")
                }
            } catch (e: Exception) {
                android.util.Log.w("SONARA_CORE", "InnerTube failed for $videoId: ${e.message}")
            }

            // Phase 1: Try all Piped instances
            for (instance in pipedInstances) {
                try {
                    val service = PipedClient.createService(instance)
                    val response = service.getStreams(videoId)
                    val bestStream = AudioStreamSelector.selectBest(response.audioStreams)

                    if (bestStream != null) {
                        android.util.Log.d(
                            "SONARA_CORE",
                            "Piped resolved audio stream from $instance " +
                                "(${bestStream.bitrate} bps, ${bestStream.codec ?: "unknown"})."
                        )
                        return@withContext bestStream.url
                    } else {
                        android.util.Log.w(
                            "SONARA_CORE",
                            "Piped instance $instance returned no valid audio streams for $videoId."
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "SONARA_CORE",
                        "Piped instance $instance failed for $videoId: ${e.message}"
                    )
                }
            }

            // Phase 2: NewPipeExtractor fallback
            try {
                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
                val bestAudio = streamInfo.audioStreams
                    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxByOrNull { it.averageBitrate }
                val resolved = bestAudio?.content
                if (!resolved.isNullOrEmpty()) {
                    android.util.Log.d("SONARA_CORE", "NewPipe resolved audio stream (${bestAudio.averageBitrate} bps).")
                    return@withContext resolved
                }
            } catch (e: Exception) {
                android.util.Log.e("SONARA_CORE", "NewPipe fallback failed for $videoId: ${e.message}")
            }

            // All methods failed
            android.util.Log.e("SONARA_CORE", "All stream resolution methods failed for $videoId.")
            ""
        } catch (e: Exception) {
            android.util.Log.e("SONARA_CORE", "Unexpected error in fetchAudioStreamLink for $videoId: ${e.message}")
            ""
        }
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipToNext() {
        mediaController?.seekToNext()
    }

    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    @OptIn(UnstableApi::class)
    fun playStream(videoId: String, title: String, artist: String, thumbnailUrl: String) {
        repositoryScope.launch {
            val resolvedStreamUrl = fetchAudioStreamLink(videoId)
            val controller = mediaController

            if (controller != null && resolvedStreamUrl.isNotEmpty()) {
                val mediaItem = MediaItem.Builder()
                    .setMediaId(videoId)
                    .setUri(resolvedStreamUrl)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .setArtworkUri(thumbnailUrl.toUri())
                            .build()
                    )
                    .build()

                controller.apply {
                    stop()
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
            } else {
                android.util.Log.e("SONARA_PLAYBACK", "Cannot execute stream layout: mediaController missing or empty targets.")
            }
        }
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}