package com.example.sonara.data

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.example.sonara.data.db.*
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
import com.example.sonara.data.db.SearchHistoryDao
import com.example.sonara.data.db.SearchHistoryEntity
import com.example.sonara.data.CloudSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MusicRepository(private val context: Context) {

    // Room database and DAOs
    private val database = SonaraDatabase.getDatabase(context)
    private val likedSongDao: LikedSongDao = database.likedSongDao()
    private val playlistDao: PlaylistDao = database.playlistDao()
    private val recentSongDao: RecentSongDao = database.recentSongDao()
    private val searchHistoryDao: SearchHistoryDao = database.searchHistoryDao()
    private val downloadedSongDao: DownloadedSongDao = database.downloadedSongDao()

    private val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
    private fun getUserId(): String = auth.currentUser?.uid ?: "anonymous"

    private val syncManager = CloudSyncManager(database)

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

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _queueItems = MutableStateFlow<List<QueueTrack>>(emptyList())
    val queueItems: StateFlow<List<QueueTrack>> = _queueItems.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _likedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val likedSongIds: StateFlow<Set<String>> = _likedSongIds.asStateFlow()

    // SupervisorJob ensures that one background failure will NOT kill the scope for subsequent operations like cloud sync
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        
        // Reactively collect liked IDs whenever the user changes
        repositoryScope.launch {
            callbackFlow {
                val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { 
                    trySend(it.currentUser?.uid ?: "anonymous")
                }
                auth.addAuthStateListener(listener)
                awaitClose { auth.removeAuthStateListener(listener) }
            }.collectLatest { uid ->
                // Flow of Flow: collect the inner flow from Room
                likedSongDao.getAllLikedIds(uid).collect { ids ->
                    _likedSongIds.value = ids.toSet()
                    // Sync with PlaybackService for notification UI
                    com.example.sonara.playback.PlaybackService.globalLikedIds = ids.toSet()
                }
            }
        }
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            setupControllerListener()

            // Set the like callback so notification heart button works
            PlaybackService.likeCallback = { videoId, title, artist, thumbnailUrl ->
                repositoryScope.launch {
                    if (likedSongIds.value.contains(videoId)) {
                        unlikeSong(videoId)
                    } else {
                        likeSong(videoId, title, artist, thumbnailUrl)
                    }
                }
            }
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
                    Player.REPEAT_MODE_OFF -> RepeatMode.OFF
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
        
        // Ensure initial repeat mode is set and synced
        val initialMode = mediaController?.repeatMode ?: Player.REPEAT_MODE_ALL
        mediaController?.repeatMode = initialMode
        _repeatMode.value = when (initialMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }

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
        val nextIndex = controller.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return

        val nextItem = controller.getMediaItemAt(nextIndex)
        val nextUri = nextItem.localConfiguration?.uri
        if (nextUri != null && (nextUri.scheme == "sonara" || nextUri.toString().isEmpty())) {
            repositoryScope.launch {
                val videoId = nextItem.mediaId
                if (videoId.isNotEmpty()) {
                    val resolvedUrl = fetchAudioStreamLink(videoId)
                    if (resolvedUrl.isNotEmpty()) {
                        val resolved = nextItem.buildUpon().setUri(resolvedUrl).build()
                        try {
                            if (controller.mediaItemCount > nextIndex &&
                                controller.getMediaItemAt(nextIndex).mediaId == videoId) {
                                controller.replaceMediaItem(nextIndex, resolved)
                                android.util.Log.d("SONARA_JIT", "Successfully preloaded next track: $videoId")
                            }
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
            
            // Re-apply current repeat mode to new queue
            controller.repeatMode = when (_repeatMode.value) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
            
            controller.prepare()
            controller.play()
        }
    }

    fun addToQueue(item: MediaItem) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            setQueue(listOf(item), 0)
        } else {
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

    fun removeFromQueue(videoId: String) {
        mediaController?.let { controller ->
            for (i in 0 until controller.mediaItemCount) {
                if (controller.getMediaItemAt(i).mediaId == videoId) {
                    controller.removeMediaItem(i)
                    break
                }
            }
        }
    }

    suspend fun searchWithDynamicUrl(url: String, query: String): List<InvidiousSearchItem> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.searchTracks(dynamicUrl = url, searchQuery = query)
    }

    suspend fun searchPlaylistsWithDynamicUrl(url: String, query: String): List<com.example.sonara.network.InvidiousPlaylistSearchItem> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.searchPlaylists(dynamicUrl = url, searchQuery = query)
    }

    suspend fun fetchAudioStreamLink(videoId: String): String = withContext(Dispatchers.IO) {
        try {
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
        val entity = LikedSongEntity(videoId, title, artist, thumbnailUrl, userId = getUserId())
        likedSongDao.insert(entity)
        android.util.Log.d("SONARA_SYNC", "Local like saved. Launching cloud push for: $videoId")

        repositoryScope.launch {
            try {
                syncManager.pushLikedSong(entity)
            } catch (e: Exception) {
                android.util.Log.e("SONARA_SYNC", "Cloud push exception for like: ${e.message}")
            }
        }
    }

    suspend fun unlikeSong(videoId: String) {
        likedSongDao.delete(videoId, getUserId())
        android.util.Log.d("SONARA_SYNC", "Local unlike saved. Launching cloud remove for: $videoId")

        repositoryScope.launch {
            try {
                syncManager.removeLikedSong(videoId)
            } catch (e: Exception) {
                android.util.Log.e("SONARA_SYNC", "Cloud remove exception for unlike: ${e.message}")
            }
        }
    }

    fun getLikedSongs(): Flow<List<LikedSongEntity>> {
        return likedSongDao.getAllLikedSongs(getUserId())
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
                    
                    // Re-apply current repeat mode to new item
                    repeatMode = when (_repeatMode.value) {
                        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    }

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
                playedAt = System.currentTimeMillis(),
                userId = getUserId()
            )
        )
        recentSongDao.trimToMax(getUserId())
    }

    fun getRecentSongs(): Flow<List<RecentSongEntity>> {
        return recentSongDao.getRecentSongs(getUserId())
    }

    fun getRecentSongsForHome(): Flow<List<RecentSongEntity>> {
        return recentSongDao.getRecentSongsForHome(getUserId())
    }

    // --- Search History ---

    suspend fun recordSearch(query: String) {
        if (query.isBlank()) return
        searchHistoryDao.insertSearch(SearchHistoryEntity(query = query, userId = getUserId()))
        searchHistoryDao.trimToMax(getUserId())
    }

    fun getRecentSearches(): Flow<List<SearchHistoryEntity>> {
        return searchHistoryDao.getRecentSearches(getUserId())
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }

    // --- Playlist Operations ---

    suspend fun createPlaylist(name: String): Long {
        val remoteId = UUID.randomUUID().toString()
        val newPlaylist = PlaylistEntity(name = name, remoteId = remoteId, userId = getUserId())
        val id = playlistDao.createPlaylist(newPlaylist)
        android.util.Log.d("SONARA_SYNC", "Local playlist created: $name (userId=${getUserId()})")

        repositoryScope.launch {
            try {
                syncManager.pushPlaylist(newPlaylist.copy(id = id))
            } catch (e: Exception) {
                android.util.Log.e("SONARA_SYNC", "Cloud push exception for playlist: ${e.message}")
            }
        }
        return id
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
        val songEntity = PlaylistSongEntity(
            playlistId = playlistId,
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            orderIndex = currentCount
        )
        playlistDao.addSongToPlaylist(songEntity)
        android.util.Log.d("SONARA_SYNC", "Local playlist song saved: $videoId → playlistId=$playlistId")

        // Background push network call
        repositoryScope.launch {
            android.util.Log.d("SONARA_SYNC", "Cloud push coroutine STARTED for playlist song: $videoId")
            try {
                val playlist = playlistDao.getAllPlaylists(getUserId()).first().find { it.id == playlistId }
                android.util.Log.d("SONARA_SYNC", "Found playlist for push: ${playlist?.name}, remoteId='${playlist?.remoteId}'")
                playlist?.let {
                    val syncTargetId = it.remoteId.ifEmpty { it.name }
                    if (syncTargetId.isNotEmpty()) {
                        syncManager.pushSongToPlaylist(syncTargetId, songEntity)
                        android.util.Log.d("SONARA_SYNC", "Successfully pushed playlist song to cloud: $videoId → playlist=$syncTargetId")
                    } else {
                        android.util.Log.e("SONARA_SYNC", "Cannot push song: playlist has no remoteId or name")
                    }
                } ?: android.util.Log.e("SONARA_SYNC", "Playlist not found for id=$playlistId")
            } catch (e: Exception) {
                android.util.Log.e("SONARA_SYNC", "Cloud push exception for playlist song: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return true
    }

    fun getPlaylistSongs(playlistId: Long): Flow<List<PlaylistSongEntity>> {
        return playlistDao.getSongsForPlaylist(playlistId)
    }

    fun getPlaylistById(playlistId: Long): Flow<PlaylistEntity?> {
        return playlistDao.getPlaylistById(playlistId)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, videoId: String) {
        playlistDao.removeSongFromPlaylist(playlistId, videoId)
        // Optionally update cloud sync here if needed
        repositoryScope.launch {
            try {
                val playlist = playlistDao.getAllPlaylists(getUserId()).first().find { it.id == playlistId }
                playlist?.let {
                    val syncTargetId = it.remoteId.ifEmpty { it.name }
                    if (syncTargetId.isNotEmpty()) {
                        syncManager.removeSongFromPlaylist(syncTargetId, videoId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SONARA_SYNC", "Failed to sync song removal: ${e.message}")
            }
        }
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return playlistDao.getAllPlaylists(getUserId())
    }

    suspend fun deletePlaylist(playlistId: Long) {
        // Get the playlist's remoteId before deleting locally
        val playlists = playlistDao.getAllPlaylists(getUserId()).first()
        val playlist = playlists.find { it.id == playlistId }
        val remoteId = playlist?.remoteId ?: ""

        // Delete locally
        playlistDao.deletePlaylist(playlistId)

        // Delete from cloud
        if (remoteId.isNotEmpty()) {
            repositoryScope.launch {
                try {
                    syncManager.removePlaylist(remoteId)
                    android.util.Log.d("SONARA_SYNC", "Successfully deleted playlist from cloud: $remoteId")
                } catch (e: Exception) {
                    android.util.Log.e("SONARA_SYNC", "Cloud delete failed for playlist: ${e.message}")
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        val playlists = playlistDao.getAllPlaylists(getUserId()).first()
        val playlist = playlists.find { it.id == playlistId }
        playlist?.let {
            val updated = it.copy(name = newName)
            playlistDao.updatePlaylist(updated)
            repositoryScope.launch {
                try {
                    syncManager.pushPlaylist(updated)
                } catch (e: Exception) {
                    android.util.Log.e("SONARA_SYNC", "Failed to sync rename: ${e.message}")
                }
            }
        }
    }

    suspend fun reorderPlaylistSongs(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val songs = playlistDao.getSongsForPlaylist(playlistId).first().toMutableList()
        if (fromIndex in songs.indices && toIndex in songs.indices) {
            val song = songs.removeAt(fromIndex)
            songs.add(toIndex, song)
            
            // Re-assign order indices
            val updatedSongs = songs.mapIndexed { index, entity ->
                entity.copy(orderIndex = index)
            }
            playlistDao.updatePlaylistSongs(updatedSongs)
        }
    }

    fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>> = downloadedSongDao.getAllDownloadedSongs(getUserId())

    suspend fun insertDownloadedSong(song: DownloadedSongEntity) = downloadedSongDao.insert(song.copy(userId = getUserId()))

    suspend fun deleteDownloadedSong(videoId: String) = downloadedSongDao.delete(videoId, getUserId())

    fun isDownloaded(videoId: String): Flow<Boolean> = downloadedSongDao.isDownloaded(videoId, getUserId())

    /**
     * Attempts to find a thumbnail URL for a song by its videoId.
     * Searches in liked songs, playlists, and recent history.
     */
    suspend fun findThumbnailUrl(videoId: String): String = withContext(Dispatchers.IO) {
        val uid = getUserId()
        
        // Check liked songs
        likedSongDao.getLikedSong(videoId, uid)?.thumbnailUrl?.let { if (it.isNotEmpty()) return@withContext it }
        
        // Check playlists
        playlistDao.getSongAcrossPlaylists(videoId, uid)?.thumbnailUrl?.let { if (it.isNotEmpty()) return@withContext it }
        
        // Check recent songs
        recentSongDao.getRecentSong(videoId, uid)?.thumbnailUrl?.let { if (it.isNotEmpty()) return@withContext it }
        
        ""
    }

    fun performCloudSync() {
        repositoryScope.launch {
            syncManager.syncFromCloud()
        }
    }
}