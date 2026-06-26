package com.example.sonara.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_songs")
data class RecentSongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playedAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)
