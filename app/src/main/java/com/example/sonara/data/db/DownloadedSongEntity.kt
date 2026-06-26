package com.example.sonara.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_songs")
data class DownloadedSongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val localFilePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)
