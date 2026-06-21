package com.example.sonara.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSongDao {
    @Query("SELECT * FROM recent_songs ORDER BY playedAt DESC LIMIT 50")
    fun getRecentSongs(): Flow<List<RecentSongEntity>>

    @Query("SELECT * FROM recent_songs ORDER BY playedAt DESC LIMIT 10")
    fun getRecentSongsForHome(): Flow<List<RecentSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: RecentSongEntity)

    @Query("DELETE FROM recent_songs WHERE videoId NOT IN (SELECT videoId FROM recent_songs ORDER BY playedAt DESC LIMIT 50)")
    suspend fun trimToMax()
}
