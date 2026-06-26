package com.example.sonara.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs WHERE userId = :userId ORDER BY downloadedAt DESC")
    fun getAllDownloadedSongs(userId: String): Flow<List<DownloadedSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE videoId = :videoId AND userId = :userId")
    suspend fun delete(videoId: String, userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_songs WHERE videoId = :videoId AND userId = :userId)")
    fun isDownloaded(videoId: String, userId: String): Flow<Boolean>

    @Query("SELECT * FROM downloaded_songs WHERE videoId = :videoId AND userId = :userId")
    suspend fun getDownloadedSong(videoId: String, userId: String): DownloadedSongEntity?
}
