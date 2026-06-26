package com.example.sonara.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val likedAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)
