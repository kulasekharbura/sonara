package com.example.sonara.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.data.MusicRepository
import com.example.sonara.network.YoutubeSearchItem
import com.example.sonara.network.VideoIdId
import com.example.sonara.network.SnippetData
import com.example.sonara.network.ThumbnailGroup
import com.example.sonara.network.ThumbDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    val isPlaying: StateFlow<Boolean> = repository.isPlaying
    val currentMediaItem: StateFlow<MediaItem?> = repository.currentMediaItem

    val currentPosition: StateFlow<Long> = repository.currentPosition
    val duration: StateFlow<Long> = repository.duration

    private val _searchResults = MutableStateFlow<List<YoutubeSearchItem>>(emptyList())
    val searchResults: StateFlow<List<YoutubeSearchItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

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

    fun performSearch(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
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

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}