package com.example.sonara.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSongDao {
    @Query("SELECT * FROM recent_songs WHERE userId = :userId ORDER BY playedAt DESC LIMIT 20")
    fun getRecentSongs(userId: String): Flow<List<RecentSongEntity>>

    @Query("SELECT * FROM recent_songs WHERE userId = :userId ORDER BY playedAt DESC LIMIT 10")
    fun getRecentSongsForHome(userId: String): Flow<List<RecentSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: RecentSongEntity)

    @Query("DELETE FROM recent_songs WHERE userId = :userId AND videoId NOT IN (SELECT videoId FROM recent_songs WHERE userId = :userId ORDER BY playedAt DESC LIMIT 20)")
    suspend fun trimToMax(userId: String)
}
