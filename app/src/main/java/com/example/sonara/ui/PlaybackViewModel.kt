package com.example.sonara.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.data.MusicRepository
import com.example.sonara.data.db.*
import com.example.sonara.data.models.QueueTrack
import com.example.sonara.data.models.RepeatMode
import com.example.sonara.data.models.SearchPlaylistItem
import com.example.sonara.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val auth = com.google.firebase.auth.FirebaseAuth.getInstance()

    private val _userId = MutableStateFlow(auth.currentUser?.uid ?: "anonymous")
    val userId: StateFlow<String> = _userId.asStateFlow()

    val isPlaying: StateFlow<Boolean> = repository.isPlaying
    val currentMediaItem: StateFlow<MediaItem?> = repository.currentMediaItem
    val currentPosition: StateFlow<Long> = repository.currentPosition
    val duration: StateFlow<Long> = repository.duration

    val repeatMode: StateFlow<RepeatMode> = repository.repeatMode
    val shuffleEnabled: StateFlow<Boolean> = repository.shuffleEnabled
    val queueItems: StateFlow<List<QueueTrack>> = repository.queueItems
    val currentQueueIndex: StateFlow<Int> = repository.currentQueueIndex
    val likedSongIds: StateFlow<Set<String>> = repository.likedSongIds

    val customPlaylists: StateFlow<List<PlaylistEntity>> = userId.flatMapLatest {
        repository.getAllPlaylists()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSongs: StateFlow<List<RecentSongEntity>> = userId.flatMapLatest {
        repository.getRecentSongs()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSongsForHome: StateFlow<List<RecentSongEntity>> = userId.flatMapLatest {
        repository.getRecentSongsForHome()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSearches: StateFlow<List<SearchHistoryEntity>> = userId.flatMapLatest {
        repository.getRecentSearches()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchPlaylists = MutableStateFlow<List<SearchPlaylistItem>>(emptyList())
    val searchPlaylists: StateFlow<List<SearchPlaylistItem>> = _searchPlaylists.asStateFlow()

    private val _searchResults = MutableStateFlow<List<YoutubeSearchItem>>(emptyList())
    val searchResults: StateFlow<List<YoutubeSearchItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid ?: "anonymous"
            if (_userId.value != uid) {
                _userId.value = uid
                repository.performCloudSync()
            }
        }
        repository.performCloudSync()
    }

    private val invidiousInstances = listOf(
        "https://yewtu.be/api/v1/",
        "https://inv.thepixora.com/api/v1/",
        "https://invidious.nerdvpn.de/api/v1/",
        "https://yt.artemislena.eu/api/v1/",
        "https://invidious.f5.si/api/v1/"
    )

    fun playPause() = repository.playPause()
    fun skipToNext() = repository.skipToNext()
    fun skipToPrevious() = repository.skipToPrevious()
    fun seekTo(positionMs: Long) = repository.seekTo(positionMs)

    fun playQueueFromSearch(results: List<YoutubeSearchItem>, startIndex: Int) {
        val mediaItems = results.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.videoId ?: "")
                .setUri("sonara://resolve/${item.id.videoId ?: ""}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.snippet.title)
                        .setArtist(item.snippet.channelTitle)
                        .setArtworkUri(item.snippet.thumbnails.high.url.toUri())
                        .build()
                )
                .build()
        }
        if (mediaItems.isNotEmpty()) repository.setQueue(mediaItems, startIndex)
    }

    fun toggleRepeatMode() = repository.setRepeatMode(
        when (repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    )

    fun toggleShuffle() = repository.setShuffleEnabled(!shuffleEnabled.value)

    fun addToQueue(item: YoutubeSearchItem) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id.videoId ?: "")
            .setUri("sonara://resolve/${item.id.videoId ?: ""}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.snippet.title)
                    .setArtist(item.snippet.channelTitle)
                    .setArtworkUri(item.snippet.thumbnails.high.url.toUri())
                    .build()
            )
            .build()
        repository.addToQueue(mediaItem)
    }

    fun addToQueue(videoId: String, title: String, artist: String, thumbnailUrl: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri("sonara://resolve/$videoId")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(thumbnailUrl.toUri())
                    .build()
            )
            .build()
        repository.addToQueue(mediaItem)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = repository.moveQueueItem(fromIndex, toIndex)

    fun performDebouncedSearch(query: String) {
        searchJob?.cancel()
        if (query.length < 3) {
            _searchResults.value = emptyList()
            _searchPlaylists.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(400)
            _isSearching.value = true
            // NOTE: Search history is NOT recorded here. It's recorded only when user
            // actually interacts with a result (see playAudioStream, playQueueFromSearch).
            
            var success = false
            for (instance in invidiousInstances) {
                try {
                    val searchUrl = "${instance}search"
                    val results = repository.searchWithDynamicUrl(searchUrl, query)
                    val playlists = repository.searchPlaylistsWithDynamicUrl(searchUrl, query)
                    
                    val mappedResults = results.filter { !it.videoId.isNullOrEmpty() }.map { item ->
                        YoutubeSearchItem(
                            id = VideoIdId(item.videoId),
                            snippet = SnippetData(
                                title = item.title,
                                channelTitle = item.author,
                                thumbnails = ThumbnailGroup(
                                    default = ThumbDetails(item.videoThumbnails.firstOrNull()?.url ?: ""),
                                    high = ThumbDetails(item.videoThumbnails.lastOrNull()?.url ?: "")
                                )
                            )
                        )
                    }

                    val mappedPlaylists = playlists.map { item ->
                        SearchPlaylistItem(
                            playlistId = item.playlistId,
                            title = item.title,
                            channelName = item.author,
                            thumbnailUrl = item.playlistThumbnail ?: "",
                            videoCount = item.videoCount
                        )
                    }

                    _searchResults.value = mappedResults
                    _searchPlaylists.value = mappedPlaylists
                    success = true
                    break
                } catch (e: Exception) {
                    android.util.Log.w("SONARA_SEARCH", "Instance $instance failed: ${e.message}")
                }
            }
            if (!success) {
                _searchResults.value = emptyList()
                _searchPlaylists.value = emptyList()
            }
            _isSearching.value = false
        }
    }

    fun recordSearchQuery(query: String) {
        if (query.length >= 3) {
            viewModelScope.launch { repository.recordSearch(query) }
        }
    }

    fun playAudioStream(streamUrlOrId: String, title: String, artist: String, thumbnailUrl: String) {
        repository.playStream(streamUrlOrId, title, artist, thumbnailUrl)
    }

    fun toggleLike(videoId: String, title: String, artist: String, thumbnailUrl: String) {
        viewModelScope.launch {
            if (likedSongIds.value.contains(videoId)) {
                repository.unlikeSong(videoId)
            } else {
                repository.likeSong(videoId, title, artist, thumbnailUrl)
            }
        }
    }

    fun isLiked(videoId: String): Boolean = likedSongIds.value.contains(videoId)

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch { repository.renamePlaylist(playlistId, name) }
    }

    fun reorderPlaylistSongs(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { repository.reorderPlaylistSongs(playlistId, fromIndex, toIndex) }
    }

    fun addToPlaylist(playlistId: Long, videoId: String, title: String, artist: String, thumbnailUrl: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = repository.addSongToPlaylist(playlistId, videoId, title, artist, thumbnailUrl)
            onResult(added)
        }
    }

    fun getLikedSongs(): Flow<List<LikedSongEntity>> = repository.getLikedSongs()
    fun getPlaylistSongs(playlistId: Long): Flow<List<PlaylistSongEntity>> = repository.getPlaylistSongs(playlistId)
    fun getPlaylist(playlistId: Long): Flow<PlaylistEntity?> = repository.getPlaylistById(playlistId)

    fun removeSongFromPlaylist(playlistId: Long, videoId: String) {
        viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, videoId) }
    }

    fun removeFromQueue(videoId: String) = repository.removeFromQueue(videoId)

    val downloadedSongs: StateFlow<List<DownloadedSongEntity>> = userId.flatMapLatest {
        repository.getAllDownloadedSongs()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun isDownloaded(videoId: String): Flow<Boolean> = repository.isDownloaded(videoId)

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    fun downloadSong(context: android.content.Context, videoId: String, title: String, artist: String, thumbnailUrl: String) {
        viewModelScope.launch {
            // Prevent duplicate downloads
            val alreadyDownloaded = repository.isDownloaded(videoId).first()
            if (alreadyDownloaded) {
                android.widget.Toast.makeText(context, "Already downloaded", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Also prevent re-triggering while download is in progress
            if (_downloadProgress.value.containsKey(videoId)) {
                android.widget.Toast.makeText(context, "Download already in progress", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val url = repository.fetchAudioStreamLink(videoId)
            if (url.isNotEmpty()) {
                val safeTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Track" }
                _downloadProgress.update { it + (videoId to 0) }
                com.example.sonara.network.DownloadHelper.downloadWithStatus(
                    context = context, url = url, title = title, artist = artist,
                    thumbnailUrl = thumbnailUrl,
                    onProgress = { progress ->
                        _downloadProgress.update { it + (videoId to progress) }
                        if (progress == 100) {
                            viewModelScope.launch {
                                repository.insertDownloadedSong(
                                    DownloadedSongEntity(
                                        videoId = videoId,
                                        title = title,
                                        artist = artist,
                                        thumbnailUrl = thumbnailUrl,
                                        localFilePath = "Sonara/$safeTitle.m4a",
                                        userId = userId.value
                                    )
                                )
                            }
                        }
                    }
                )
            } else {
                android.util.Log.e("SONARA_DOWNLOAD", "Stream resolution failed for $videoId — cannot download")
                android.widget.Toast.makeText(context, "Failed to resolve stream for download", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun downloadAll(context: android.content.Context, songs: List<com.example.sonara.ui.SongDisplayItem>) {
        if (songs.isEmpty()) return
        android.widget.Toast.makeText(context, "Downloading playlist (${songs.size} songs)", android.widget.Toast.LENGTH_SHORT).show()

        // Launch each song download as a separate coroutine (all in parallel)
        songs.forEach { song ->
            viewModelScope.launch(Dispatchers.IO) {
                val alreadyDownloaded = repository.isDownloaded(song.videoId).first()
                if (alreadyDownloaded || _downloadProgress.value.containsKey(song.videoId)) return@launch

                val url = repository.fetchAudioStreamLink(song.videoId)
                if (url.isNotEmpty()) {
                    val safeTitle = song.title.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Track" }
                    _downloadProgress.update { it + (song.videoId to 0) }
                    com.example.sonara.network.DownloadHelper.downloadWithStatus(
                        context = context, url = url, title = song.title, artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl, showToasts = false,
                        onProgress = { progress ->
                            _downloadProgress.update { it + (song.videoId to progress) }
                            if (progress == 100) {
                                viewModelScope.launch {
                                    repository.insertDownloadedSong(
                                        DownloadedSongEntity(
                                            videoId = song.videoId,
                                            title = song.title,
                                            artist = song.artist,
                                            thumbnailUrl = song.thumbnailUrl,
                                            localFilePath = "Sonara/$safeTitle.m4a",
                                            userId = userId.value
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    fun deleteDownloadedSong(context: android.content.Context, videoId: String, title: String) {
        viewModelScope.launch {
            repository.deleteDownloadedSong(videoId)
            com.example.sonara.network.DownloadHelper.removeDownload(context, title)
            _downloadProgress.update { it - videoId }
        }
    }

    fun syncDownloadsWithFolder(context: android.content.Context) {
        viewModelScope.launch {
            val dbSongs = repository.getAllDownloadedSongs().first()
            
            // Remove DB entries whose files no longer exist
            dbSongs.forEach { song ->
                if (!com.example.sonara.network.DownloadHelper.isFileDownloaded(song.title)) {
                    repository.deleteDownloadedSong(song.videoId)
                }
            }

            // Scan folder and add entries for files not in DB (after reinstall)
            val sonaraDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "Sonara"
            )
            if (sonaraDir.exists() && sonaraDir.isDirectory) {
                // Refresh DB list after cleanup
                val currentDbSongs = repository.getAllDownloadedSongs().first()
                val trackedFiles = currentDbSongs.map { song ->
                    song.title.filter { c -> c.isLetterOrDigit() || c == ' ' }.lowercase()
                }.toSet()

                sonaraDir.listFiles()?.filter { it.extension == "m4a" }?.forEach { file ->
                    val fileTitle = file.nameWithoutExtension.lowercase()
                    // Only add if NOT already tracked (compare sanitized lowercase names)
                    if (fileTitle !in trackedFiles) {
                        repository.insertDownloadedSong(
                            com.example.sonara.data.db.DownloadedSongEntity(
                                videoId = "local_${file.name.hashCode()}",
                                title = file.nameWithoutExtension,
                                artist = "Unknown",
                                thumbnailUrl = "",
                                localFilePath = "Sonara/${file.name}",
                                userId = userId.value
                            )
                        )
                    }
                }
            }
        }
    }

    fun performCloudSync() = repository.performCloudSync()

    fun playFromLikedSongs(startIndex: Int) {
        viewModelScope.launch {
            val liked = repository.getLikedSongs().first()
            val mediaItems = liked.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.videoId)
                    .setUri("sonara://resolve/${song.videoId}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(song.thumbnailUrl.toUri())
                            .build()
                    )
                    .build()
            }
            if (mediaItems.isNotEmpty()) repository.setQueue(mediaItems, startIndex)
        }
    }

    fun playFromPlaylist(playlistId: Long, startIndex: Int) {
        viewModelScope.launch {
            val songs = repository.getPlaylistSongs(playlistId).first()
            val mediaItems = songs.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.videoId)
                    .setUri("sonara://resolve/${song.videoId}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(song.thumbnailUrl.toUri())
                            .build()
                    )
                    .build()
            }
            if (mediaItems.isNotEmpty()) repository.setQueue(mediaItems, startIndex)
        }
    }

    fun playFromRecent(startIndex: Int) {
        viewModelScope.launch {
            val recent = repository.getRecentSongs().first()
            val mediaItems = recent.map { song ->
                MediaItem.Builder()
                    .setMediaId(song.videoId)
                    .setUri("sonara://resolve/${song.videoId}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(song.thumbnailUrl.toUri())
                            .build()
                    )
                    .build()
            }
            if (mediaItems.isNotEmpty()) repository.setQueue(mediaItems, startIndex)
        }
    }

    fun playFromDownloads(startIndex: Int) {
        viewModelScope.launch {
            val downloads = repository.getAllDownloadedSongs().first()
            val mediaItems = downloads.map { song ->
                // Use local file path for downloaded songs — no streaming needed
                val localFile = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    song.localFilePath
                )
                val uri = if (localFile.exists()) {
                    localFile.toUri().toString()
                } else {
                    // Fallback to stream if file is missing
                    "sonara://resolve/${song.videoId}"
                }
                MediaItem.Builder()
                    .setMediaId(song.videoId)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(song.thumbnailUrl.toUri())
                            .build()
                    )
                    .build()
            }
            if (mediaItems.isNotEmpty()) repository.setQueue(mediaItems, startIndex)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}
