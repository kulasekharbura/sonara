package com.example.sonara.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LikedSongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        RecentSongEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SonaraDatabase : RoomDatabase() {

    abstract fun likedSongDao(): LikedSongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentSongDao(): RecentSongDao

    companion object {
        @Volatile
        private var INSTANCE: SonaraDatabase? = null

        fun getDatabase(context: Context): SonaraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SonaraDatabase::class.java,
                    "sonara_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
