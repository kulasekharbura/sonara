package com.example.sonara.data

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.example.sonara.playback.PlaybackService
import com.example.sonara.network.RetrofitClient
import com.example.sonara.network.InvidiousSearchItem
import com.example.sonara.data.models.QueueTrack
import com.example.sonara.data.models.RepeatMode
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import com.example.sonara.data.db.SonaraDatabase
import com.example.sonara.data.db.LikedSongDao
import com.example.sonara.data.db.LikedSongEntity
import com.example.sonara.data.db.PlaylistDao
import com.example.sonara.data.db.PlaylistEntity
import com.example.sonara.data.db.PlaylistSongEntity
import com.example.sonara.data.db.RecentSongDao
import com.example.sonara.data.db.RecentSongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    // Room database and DAOs
    private val database = SonaraDatabase.getDatabase(context)
    private val likedSongDao: LikedSongDao = database.likedSongDao()
    private val playlistDao: PlaylistDao = database.playlistDao()
    private val recentSongDao: RecentSongDao = database.recentSongDao()

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

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _queueItems = MutableStateFlow<List<QueueTrack>>(emptyList())
    val queueItems: StateFlow<List<QueueTrack>> = _queueItems.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _likedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val likedSongIds: StateFlow<Set<String>> = _likedSongIds.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Main)

    /**
     * In-memory cache for resolved stream URLs.
     * Key: videoId, Value: Pair(url, timestampMs)
     * URLs expire after 30 minutes (YouTube stream URLs typically valid for ~6 hours).
     */
    private val urlCache = mutableMapOf<String, Pair<String, Long>>()
    private val URL_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    init {
        initializeController()
        startProgressTicker()
        // Collect liked song IDs from Room database
        repositoryScope.launch {
            likedSongDao.getAllLikedIds().collect { ids ->
                _likedSongIds.value = ids.toSet()
            }
        }
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
                _currentQueueIndex.value = mediaController?.currentMediaItemIndex ?: 0
                // Record to recent history
                mediaItem?.let { item ->
                    val metadata = item.mediaMetadata
                    repositoryScope.launch {
                        recordRecentPlay(
                            videoId = item.mediaId,
                            title = metadata.title?.toString() ?: "",
                            artist = metadata.artist?.toString() ?: "",
                            thumbnailUrl = metadata.artworkUri?.toString() ?: ""
                        )
                    }
                }
                // Preload next track's URL so it's ready when this track ends
                preloadNextTrack()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                rebuildQueueItems()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleEnabled.value = shuffleModeEnabled
            }

            override fun onPlayerError(error: PlaybackException) {
                val controller = mediaController ?: return
                val currentIndex = controller.currentMediaItemIndex
                val itemCount = controller.mediaItemCount

                android.util.Log.e(
                    "SONARA_PLAYBACK",
                    "Playback error at index $currentIndex: ${error.message}"
                )

                // If there are more tracks in the queue, skip to the next one.
                // Otherwise, stop playback gracefully.
                if (currentIndex < itemCount - 1) {
                    android.util.Log.d("SONARA_PLAYBACK", "Skipping to next track after error")
                    controller.seekToNext()
                    controller.prepare()
                    controller.play()
                } else {
                    android.util.Log.d("SONARA_PLAYBACK", "No more tracks after error, stopping playback")
                    controller.stop()
                }
            }
        })

        _isPlaying.value = mediaController?.isPlaying == true
        _currentMediaItem.value = mediaController?.currentMediaItem
        _currentQueueIndex.value = mediaController?.currentMediaItemIndex ?: 0
        rebuildQueueItems()
    }

    private fun rebuildQueueItems() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        val items = mutableListOf<QueueTrack>()
        for (i in 0 until count) {
            val mediaItem = controller.getMediaItemAt(i)
            val metadata = mediaItem.mediaMetadata
            items.add(
                QueueTrack(
                    videoId = mediaItem.mediaId,
                    title = metadata.title?.toString() ?: "",
                    artist = metadata.artist?.toString() ?: "",
                    thumbnailUrl = metadata.artworkUri?.toString() ?: ""
                )
            )
        }
        _queueItems.value = items
    }

    private fun preloadNextTrack() {
        val controller = mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        val nextIndex = currentIndex + 1
        if (nextIndex >= controller.mediaItemCount) return

        val nextItem = controller.getMediaItemAt(nextIndex)
        val nextUri = nextItem.localConfiguration?.uri
        // Only preload if the next item still has a placeholder URI
        if (nextUri != null && nextUri.scheme == "sonara") {
            repositoryScope.launch {
                val videoId = nextItem.mediaId
                if (videoId.isNotEmpty()) {
                    val resolvedUrl = fetchAudioStreamLink(videoId)
                    if (resolvedUrl.isNotEmpty()) {
                        val resolved = nextItem.buildUpon().setUri(resolvedUrl).build()
                        try {
                            controller.replaceMediaItem(nextIndex, resolved)
                        } catch (e: Exception) {
                            android.util.Log.w("SONARA_PRELOAD", "Could not preload track at $nextIndex: ${e.message}")
                        }
                    }
                }
            }
        }
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

    fun setQueue(items: List<MediaItem>, startIndex: Int) {
        val controller = mediaController ?: return
        // Resolve the starting track first for immediate playback.
        // Remaining tracks are resolved on-demand when they become the current track
        // via onMediaItemTransition preloading.
        repositoryScope.launch {
            val mutableItems = items.toMutableList()
            if (startIndex in items.indices) {
                val startItem = items[startIndex]
                val videoId = startItem.mediaId
                if (videoId.isNotEmpty()) {
                    val resolvedUrl = fetchAudioStreamLink(videoId)
                    if (resolvedUrl.isNotEmpty()) {
                        mutableItems[startIndex] = startItem.buildUpon().setUri(resolvedUrl).build()
                    }
                }
            }
            controller.setMediaItems(mutableItems, startIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun addToQueue(item: MediaItem) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            setQueue(listOf(item), 0)
        } else {
            // Resolve the URL in the background, then add with real URI
            repositoryScope.launch {
                val videoId = item.mediaId
                val resolvedItem = if (videoId.isNotEmpty()) {
                    val url = fetchAudioStreamLink(videoId)
                    if (url.isNotEmpty()) item.buildUpon().setUri(url).build() else item
                } else {
                    item
                }
                controller.addMediaItem(resolvedItem)
            }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        mediaController?.moveMediaItem(fromIndex, toIndex)
    }

    suspend fun searchWithDynamicUrl(url: String, query: String): List<InvidiousSearchItem> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.searchTracks(dynamicUrl = url, searchQuery = query)
    }

    suspend fun searchPlaylistsWithDynamicUrl(url: String, query: String): List<com.example.sonara.network.InvidiousPlaylistSearchItem> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.searchPlaylists(dynamicUrl = url, searchQuery = query)
    }

    /**
     * Resolves a playable audio stream URL for the given YouTube video ID.
     *
     * Checks in-memory cache first (instant). Falls back to NewPipeExtractor (~3s).
     * Caches successful results for 30 minutes.
     *
     * Returns the stream URL, or empty string on failure.
     * Never throws exceptions to the caller.
     */
    suspend fun fetchAudioStreamLink(videoId: String): String = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cached = getCachedUrl(videoId)
            if (cached != null) {
                android.util.Log.d("SONARA_CORE", "Cache hit for $videoId.")
                return@withContext cached
            }

            android.util.Log.d("SONARA_CORE", "Resolving stream for $videoId...")

            val url = tryNewPipe(videoId)
            if (!url.isNullOrEmpty()) {
                cacheUrl(videoId, url)
                android.util.Log.d("SONARA_CORE", "Resolved and cached stream for $videoId.")
                return@withContext url
            }

            android.util.Log.e("SONARA_CORE", "Stream resolution failed for $videoId.")
            ""
        } catch (e: Exception) {
            android.util.Log.e("SONARA_CORE", "Unexpected error in fetchAudioStreamLink for $videoId: ${e.message}")
            ""
        }
    }

    private fun getCachedUrl(videoId: String): String? {
        val entry = urlCache[videoId] ?: return null
        val (url, timestamp) = entry
        return if (System.currentTimeMillis() - timestamp < URL_CACHE_TTL_MS) url
        else {
            urlCache.remove(videoId)
            null
        }
    }

    private fun cacheUrl(videoId: String, url: String) {
        urlCache[videoId] = Pair(url, System.currentTimeMillis())
    }

    /**
     * Resolves a stream URL via NewPipeExtractor.
     * Filters to progressive HTTP audio streams and selects the highest bitrate.
     * Returns the URL if successful, null otherwise.
     */
    private suspend fun tryNewPipe(videoId: String): String? {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
            val bestAudio = streamInfo.audioStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxByOrNull { it.averageBitrate }
            val resolved = bestAudio?.content
            if (!resolved.isNullOrEmpty()) {
                android.util.Log.d("SONARA_CORE", "NewPipe resolved stream for $videoId (${bestAudio.averageBitrate} bps).")
                resolved
            } else {
                android.util.Log.w("SONARA_CORE", "NewPipe found no valid audio streams for $videoId.")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("SONARA_CORE", "NewPipe failed for $videoId: ${e.message}")
            null
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

    fun setRepeatMode(mode: RepeatMode) {
        val playerMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        mediaController?.repeatMode = playerMode
        _repeatMode.value = mode
    }

    fun setShuffleEnabled(enabled: Boolean) {
        mediaController?.shuffleModeEnabled = enabled
        _shuffleEnabled.value = enabled
    }

    // --- Liked Songs Operations ---

    suspend fun likeSong(videoId: String, title: String, artist: String, thumbnailUrl: String) {
        likedSongDao.insert(LikedSongEntity(videoId, title, artist, thumbnailUrl))
    }

    suspend fun unlikeSong(videoId: String) {
        likedSongDao.delete(videoId)
    }

    fun getLikedSongs(): Flow<List<LikedSongEntity>> {
        return likedSongDao.getAllLikedSongs()
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

    // --- Recent Songs ---

    suspend fun recordRecentPlay(videoId: String, title: String, artist: String, thumbnailUrl: String) {
        recentSongDao.upsert(
            RecentSongEntity(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                playedAt = System.currentTimeMillis()
            )
        )
        recentSongDao.trimToMax()
    }

    fun getRecentSongs(): Flow<List<RecentSongEntity>> {
        return recentSongDao.getRecentSongs()
    }

    fun getRecentSongsForHome(): Flow<List<RecentSongEntity>> {
        return recentSongDao.getRecentSongsForHome()
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }

    // --- Playlist Operations ---

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.createPlaylist(PlaylistEntity(name = name))
    }

    suspend fun addSongToPlaylist(
        playlistId: Long,
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String
    ): Boolean {
        if (playlistDao.songExistsInPlaylist(playlistId, videoId) > 0) {
            return false
        }
        val currentCount = playlistDao.getSongCount(playlistId).first()
        playlistDao.addSongToPlaylist(
            PlaylistSongEntity(
                playlistId = playlistId,
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                orderIndex = currentCount
            )
        )
        return true
    }

    fun getPlaylistSongs(playlistId: Long): Flow<List<PlaylistSongEntity>> {
        return playlistDao.getSongsForPlaylist(playlistId)
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return playlistDao.getAllPlaylists()
    }
}