package com.example.sonara.data.models

data class SearchPlaylistItem(
    val playlistId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val videoCount: Int
)
