package com.example.sonara.network

// --- NEW QUOTA-FREE NETWORK SCHEMAS ---
data class InvidiousSearchItem(
    val title: String,
    val videoId: String,
    val author: String,
    val videoThumbnails: List<InvidiousThumbnail>
)

data class InvidiousThumbnail(
    val quality: String,
    val url: String
)

// --- COMPATIBILITY MAPPING WRAPPERS ---
// This simulates your original Google API layouts inside the network package
// so that MainScreen.kt and your layout adapters continue working without errors.
data class YoutubeSearchItem(
    val id: VideoIdId,
    val snippet: SnippetData
)

data class VideoIdId(
    val videoId: String?
)

data class SnippetData(
    val title: String,
    val channelTitle: String,
    val thumbnails: ThumbnailGroup
)

data class ThumbnailGroup(
    val default: ThumbDetails,
    val high: ThumbDetails
)

data class ThumbDetails(
    val url: String
)
data class InvidiousVideoDetails(
    val adaptiveFormats: List<InvidiousFormat>?
)

data class InvidiousFormat(
    val type: String, // e.g., "audio/webm" or "audio/mp4"
    val url: String,
    val bitrate: String?
)