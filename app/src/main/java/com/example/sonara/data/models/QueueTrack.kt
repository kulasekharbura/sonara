package com.example.sonara.data.models

data class QueueTrack(
    val queueId: String = java.util.UUID.randomUUID().toString(),
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String
)
