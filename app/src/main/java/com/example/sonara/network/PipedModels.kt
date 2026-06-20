package com.example.sonara.network

/**
 * Data classes for deserializing Piped API JSON responses.
 * The Piped API returns stream metadata including direct audio URLs.
 */

data class PipedStreamResponse(
    val title: String?,
    val uploader: String?,
    val audioStreams: List<PipedAudioStream> = emptyList()
)

data class PipedAudioStream(
    val url: String,
    val bitrate: Int,
    val mimeType: String,
    val codec: String?,
    val format: String?,
    val quality: String?
)
