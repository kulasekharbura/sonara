package com.example.sonara.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A [MediaSource.Factory] that lazily resolves stream URLs on demand.
 *
 * Queue items are stored as [MediaItem] with only a mediaId (video ID) and metadata.
 * When ExoPlayer prepares an item for playback, this factory intercepts the call,
 * resolves the actual stream URL via [urlResolver], and returns a [ProgressiveMediaSource]
 * backed by the [CacheDataSource.Factory] for progressive caching.
 */
@OptIn(UnstableApi::class)
class LazyMediaSourceFactory(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val urlResolver: suspend (videoId: String) -> String
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        return this
    }

    override fun getSupportedTypes(): IntArray {
        return intArrayOf(C.CONTENT_TYPE_OTHER)
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val videoId = mediaItem.mediaId

        // Resolve the stream URL on IO dispatcher.
        // This is called on ExoPlayer's loading thread during preparation.
        val resolvedUrl = runBlocking(Dispatchers.IO) {
            try {
                urlResolver(videoId)
            } catch (e: Exception) {
                android.util.Log.e("SONARA_LAZY", "URL resolution exception for $videoId: ${e.message}")
                ""
            }
        }

        val uri = if (resolvedUrl.isEmpty()) {
            android.util.Log.e("SONARA_LAZY", "Stream resolution failed for $videoId, using error URI")
            "data:,"
        } else {
            android.util.Log.d("SONARA_LAZY", "Resolved stream for $videoId: ${resolvedUrl.take(60)}...")
            resolvedUrl
        }

        // Rebuild the MediaItem with the resolved URI while preserving original metadata
        // (title, artist, artwork). This ensures the player UI can display track info.
        val resolvedMediaItem = mediaItem.buildUpon()
            .setUri(uri)
            .build()

        return ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(resolvedMediaItem)
    }
}
