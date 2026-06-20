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
import com.example.sonara.network.RetrofitClient
import com.example.sonara.network.InvidiousSearchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
     * UNIFIED EXTRACTION PROTOCOL:
     * Attempts native device-level extraction via internal client API endpoints.
     * Integrates automatic multi-engine fallbacks to clear datacenter/residential blocks seamlessly.
     */
    suspend fun fetchAudioStreamLink(videoId: String): String = withContext(Dispatchers.IO) {
        var directUrl = ""

        // --- CORE LOGIC: INTERNAL INNER-TUNE FOOTPRINT WITH SIGNED VISITOR METRICS ---
        try {
            val targetUrl = "https://music.youtube.com/youtubei/v1/player"
            val url = URL(targetUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "com.google.android.apps.youtube.music/6.45.52 (Linux; U; Android 14; en_US)")

            // CRITICAL HEADER SIGNATURES REQUIRED BY YOUTUBEI PLATFORMS
            connection.setRequestProperty("X-Goog-Api-Format-Version", "2")
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.doOutput = true

            // Enhanced context object containing validation structures
            val jsonPayload = """
                {
                  "videoId": "$videoId",
                  "context": {
                    "client": {
                      "clientName": "ANDROID_MUSIC",
                      "clientVersion": "6.45.52",
                      "hl": "en",
                      "gl": "US",
                      "osName": "Android",
                      "osVersion": "14",
                      "androidSdkVersion": 34,
                      "visitorData": "Cgt2cWc4N2p0bk1xaykpeeCg"
                    },
                    "user": {
                      "lockedSafetyMode": false
                    }
                  },
                  "playbackContext": {
                    "contentPlaybackContext": {
                      "signatureTimestamp": 20000 
                    }
                  }
                }
            """.trimIndent()

            connection.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(charset("utf-8")))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)

                if (jsonResponse.has("streamingData")) {
                    val streamingData = jsonResponse.getJSONObject("streamingData")
                    if (streamingData.has("adaptiveFormats")) {
                        val adaptiveFormats = streamingData.getJSONArray("adaptiveFormats")
                        var highestBitrate = 0

                        for (i in 0 until adaptiveFormats.length()) {
                            val format = adaptiveFormats.getJSONObject(i)
                            val mimeType = format.optString("mimeType", "")

                            if (mimeType.contains("audio/")) {
                                val bitrate = format.optInt("bitrate", 0)
                                val streamUrl = format.optString("url", "")

                                if (streamUrl.isNotEmpty() && bitrate > highestBitrate) {
                                    highestBitrate = bitrate
                                    directUrl = streamUrl
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SONARA_CORE", "Native internal pipeline exception occurred.", e)
        }

        // --- SECONDARY ENGINE FAILOVER: ENHANCED COBALT CLOAKED PAYLOADS ---
        if (directUrl.isEmpty()) {
            android.util.Log.w("SONARA_CORE", "Native parser failed or throttled. Engaging secondary network mirror pipeline...")
            val backupGateways = listOf("https://api.cobalt.tools/api/json", "https://co.wuk.sh/api/json")
            val cleanUrl = "https://www.youtube.com/watch?v=$videoId"

            for (gateway in backupGateways) {
                try {
                    val url = URL(gateway)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "application/json")

                    // Cloaking headers to emulate browser security boundaries
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    connection.setRequestProperty("Origin", "https://cobalt.tools")
                    connection.setRequestProperty("Referer", "https://cobalt.tools/")
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.doOutput = true

                    // Modern Cobalt API signature structure
                    val payload = JSONObject().apply {
                        put("url", cleanUrl)
                        put("downloadMode", "audio")
                        put("audioFormat", "mp3")
                        put("audioBitrate", "128")
                    }

                    connection.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        val resolvedUrl = JSONObject(responseText).optString("url")
                        if (resolvedUrl.isNotEmpty()) {
                            directUrl = resolvedUrl
                            break
                        }
                    }
                } catch (err: Exception) {
                    android.util.Log.w("SONARA_CORE", "Gateway mirror exception layout: ${err.message}")
                }
            }
        }

        // --- ULTIMATE RESILIENT SAFEGUARD ---
        if (directUrl.isEmpty()) {
            android.util.Log.w("SONARA_CORE", "Network gateways saturated. Defaulting to private Render stream link layout container.")
            directUrl = "https://sonara-backend-0zx5.onrender.com/api/stream?id=$videoId"
        }

        android.util.Log.d("SONARA_CORE", "Handing final validated stream asset URL down to ExoPlayer channel: $directUrl")
        directUrl
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