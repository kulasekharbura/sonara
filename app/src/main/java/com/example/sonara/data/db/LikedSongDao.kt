package com.example.sonara.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedSongDao {
    @Query("SELECT * FROM liked_songs WHERE userId = :userId ORDER BY likedAt DESC")
    fun getAllLikedSongs(userId: String): Flow<List<LikedSongEntity>>

    @Query("SELECT videoId FROM liked_songs WHERE userId = :userId")
    fun getAllLikedIds(userId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE videoId = :videoId AND userId = :userId")
    suspend fun delete(videoId: String, userId: String)

    @Query("SELECT COUNT(*) FROM liked_songs WHERE userId = :userId")
    fun getCount(userId: String): Flow<Int>

    @Query("SELECT * FROM liked_songs WHERE videoId = :videoId AND userId = :userId LIMIT 1")
    suspend fun getLikedSong(videoId: String, userId: String): LikedSongEntity?
}
