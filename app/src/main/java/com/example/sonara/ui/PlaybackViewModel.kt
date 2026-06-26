package com.example.sonara.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.data.MusicRepository
import com.example.sonara.data.db.LikedSongEntity
import com.example.sonara.data.db.PlaylistEntity
import com.example.sonara.data.db.PlaylistSongEntity
import com.example.sonara.data.db.RecentSongEntity
import com.example.sonara.data.db.SearchHistoryEntity
import com.example.sonara.data.models.QueueTrack
import com.example.sonara.data.models.RepeatMode
import com.example.sonara.data.models.SearchPlaylistItem
import com.example.sonara.network.YoutubeSearchItem
import com.example.sonara.network.VideoIdId
import com.example.sonara.network.SnippetData
import com.example.sonara.network.ThumbnailGroup
import com.example.sonara.network.ThumbDetails
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    val isPlaying: StateFlow<Boolean> = repository.isPlaying
    val currentMediaItem: StateFlow<MediaItem?> = repository.currentMediaItem

    val currentPosition: StateFlow<Long> = repository.currentPosition
    val duration: StateFlow<Long> = repository.duration

    // Queue & Modes
    val repeatMode: StateFlow<RepeatMode> = repository.repeatMode
    val shuffleEnabled: StateFlow<Boolean> = repository.shuffleEnabled
    val queueItems: StateFlow<List<QueueTrack>> = repository.queueItems
    val currentQueueIndex: StateFlow<Int> = repository.currentQueueIndex

    // Library
    val likedSongIds: StateFlow<Set<String>> = repository.likedSongIds

    private val _customPlaylists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val customPlaylists: StateFlow<List<PlaylistEntity>> = _customPlaylists.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<RecentSongEntity>>(emptyList())
    val recentSongs: StateFlow<List<RecentSongEntity>> = _recentSongs.asStateFlow()

    private val _recentSongsForHome = MutableStateFlow<List<RecentSongEntity>>(emptyList())
    val recentSongsForHome: StateFlow<List<RecentSongEntity>> = _recentSongsForHome.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    val recentSearches: StateFlow<List<SearchHistoryEntity>> = _recentSearches.asStateFlow()

    // Search playlists
    private val _searchPlaylists = MutableStateFlow<List<SearchPlaylistItem>>(emptyList())
    val searchPlaylists: StateFlow<List<SearchPlaylistItem>> = _searchPlaylists.asStateFlow()

    private val _searchResults = MutableStateFlow<List<YoutubeSearchItem>>(emptyList())
    val searchResults: StateFlow<List<YoutubeSearchItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllPlaylists().collect { _customPlaylists.value = it }
        }
        viewModelScope.launch {
            repository.getRecentSongs().collect { _recentSongs.value = it }
        }
        viewModelScope.launch {
            repository.getRecentSongsForHome().collect { _recentSongsForHome.value = it }
        }
        viewModelScope.launch {
            repository.getRecentSearches().collect { _recentSearches.value = it }
        }
        // Trigger initial cloud sync for authenticated users
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
        repository.setQueue(mediaItems, startIndex)
    }

    fun toggleRepeatMode() {
        val newMode = when (repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        repository.setRepeatMode(newMode)
    }

    fun toggleShuffle() {
        repository.setShuffleEnabled(!shuffleEnabled.value)
    }

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

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        repository.moveQueueItem(fromIndex, toIndex)
    }

    fun performDebouncedSearch(query: String) {
        searchJob?.cancel()

        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            _searchPlaylists.value = emptyList()
            return
        }

        if (query.length < 3) {
            _searchResults.value = emptyList()
            _searchPlaylists.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch {
            delay(400)
            _isSearching.value = true
            var searchSuccess = false

            for (endpointUrl in invidiousInstances) {
                try {
                    val fullSearchUrl = "${endpointUrl}search"

                    // Fetch video results
                    val results = repository.searchWithDynamicUrl(fullSearchUrl, query)

                    if (results.isNotEmpty()) {
                        val mappedResults = results.filter { !it.videoId.isNullOrEmpty() }.map { item ->
                            val absoluteThumbnailUrl = "https://img.youtube.com/vi/${item.videoId}/hqdefault.jpg"

                            YoutubeSearchItem(
                                id = VideoIdId(videoId = item.videoId),
                                snippet = SnippetData(
                                    title = item.title,
                                    channelTitle = item.author,
                                    thumbnails = ThumbnailGroup(
                                        default = ThumbDetails(url = absoluteThumbnailUrl),
                                        high = ThumbDetails(url = absoluteThumbnailUrl)
                                    )
                                )
                            )
                        }

                        _searchResults.value = mappedResults
                        searchSuccess = true

                        // Fetch playlist results (capped at 5)
                        try {
                            val playlistResults = repository.searchPlaylistsWithDynamicUrl(fullSearchUrl, query)
                            _searchPlaylists.value = playlistResults.take(5).map { playlist ->
                                SearchPlaylistItem(
                                    playlistId = playlist.playlistId,
                                    title = playlist.title,
                                    channelName = playlist.author,
                                    thumbnailUrl = playlist.playlistThumbnail ?: "",
                                    videoCount = playlist.videoCount
                                )
                            }
                        } catch (e: Exception) {
                            _searchPlaylists.value = emptyList()
                            e.printStackTrace()
                        }

                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (!searchSuccess) {
                _searchResults.value = emptyList()
                _searchPlaylists.value = emptyList()
            }
            _isSearching.value = false
        }
    }

    fun performSearch(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            repository.recordSearch(query)
            _isSearching.value = true
            var searchSuccess = false

            for (endpointUrl in invidiousInstances) {
                try {
                    val fullSearchUrl = "${endpointUrl}search"
                    val results = repository.searchWithDynamicUrl(fullSearchUrl, query)

                    if (results.isNotEmpty()) {
                        val mappedResults = results.filter { !it.videoId.isNullOrEmpty() }.map { item ->
                            val absoluteThumbnailUrl = "https://img.youtube.com/vi/${item.videoId}/hqdefault.jpg"

                            YoutubeSearchItem(
                                id = VideoIdId(videoId = item.videoId),
                                snippet = SnippetData(
                                    title = item.title,
                                    channelTitle = item.author,
                                    thumbnails = ThumbnailGroup(
                                        default = ThumbDetails(url = absoluteThumbnailUrl),
                                        high = ThumbDetails(url = absoluteThumbnailUrl)
                                    )
                                )
                            )
                        }

                        _searchResults.value = mappedResults
                        searchSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (!searchSuccess) _searchResults.value = emptyList()
            _isSearching.value = false
        }
    }

    /**
     * CLEANED UP PLAYBACK INITIATION:
     * Parses the video ID safely and forwards it directly to the repository's native
     * player pipeline layout without requesting third-party array lookups.
     */
    fun playAudioStream(streamUrlOrId: String, title: String, artist: String, thumbnailUrl: String) {
        try {
            val videoId = if (streamUrlOrId.contains("v=")) {
                streamUrlOrId.substringAfter("v=").substringBefore("&")
            } else {
                streamUrlOrId
            }

            // Directly invoking the repository. The repo handles background threading internally now!
            repository.playStream(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Library Actions ---

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

    fun addToPlaylist(playlistId: Long, videoId: String, title: String, artist: String, thumbnailUrl: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = repository.addSongToPlaylist(playlistId, videoId, title, artist, thumbnailUrl)
            onResult(added)
        }
    }

    fun getLikedSongs(): Flow<List<LikedSongEntity>> = repository.getLikedSongs()

    fun getPlaylistSongs(playlistId: Long): Flow<List<PlaylistSongEntity>> = repository.getPlaylistSongs(playlistId)

    fun performCloudSync() {
        repository.performCloudSync()
    }

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

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}