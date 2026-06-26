package com.example.sonara.data

import com.example.sonara.data.db.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Handles synchronization between local Room database and Firebase Firestore.
 * Implements "Phase 2: Offline-First Cloud Sync" from the Master Plan.
 */
class CloudSyncManager(private val database: SonaraDatabase) {

    private val firestore = Firebase.firestore
    private val auth = Firebase.auth
    private val likedSongDao = database.likedSongDao()
    private val playlistDao = database.playlistDao()

    /**
     * Pulls data from Firestore and merges it into the local database.
     * Called on app startup or after successful login.
     */
    suspend fun syncFromCloud() = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext

        try {
            // 1. Sync Liked Songs
            val likedSongsSnapshot = firestore.collection("users")
                .document(userId)
                .collection("liked_songs")
                .get()
                .await()

            likedSongsSnapshot.documents.forEach { doc ->
                val song = LikedSongEntity(
                    videoId = doc.id,
                    title = doc.getString("title") ?: "",
                    artist = doc.getString("artist") ?: "",
                    thumbnailUrl = doc.getString("thumbnailUrl") ?: "",
                    likedAt = doc.getLong("likedAt") ?: System.currentTimeMillis()
                )
                likedSongDao.insert(song)
            }

            // 2. Sync Playlists
            val playlistsSnapshot = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .get()
                .await()

            playlistsSnapshot.documents.forEach { doc ->
                val playlistName = doc.getString("name") ?: "Unnamed Playlist"
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                val remoteId = doc.id

                // We need to map remote IDs to local IDs or use a stable UUID.
                // For simplicity, we check if a playlist with this name exists locally.
                val localPlaylist = playlistDao.getAllPlaylists().first().find { it.name == playlistName }
                val playlistId = localPlaylist?.id ?: playlistDao.createPlaylist(
                    PlaylistEntity(name = playlistName, createdAt = createdAt)
                )

                // Sync songs within this playlist
                val songsSnapshot = firestore.collection("users")
                    .document(userId)
                    .collection("playlists")
                    .document(remoteId)
                    .collection("songs")
                    .get()
                    .await()

                songsSnapshot.documents.forEach { songDoc ->
                    val song = PlaylistSongEntity(
                        playlistId = playlistId,
                        videoId = songDoc.id,
                        title = songDoc.getString("title") ?: "",
                        artist = songDoc.getString("artist") ?: "",
                        thumbnailUrl = songDoc.getString("thumbnailUrl") ?: "",
                        addedAt = songDoc.getLong("addedAt") ?: System.currentTimeMillis(),
                        orderIndex = songDoc.getLong("orderIndex")?.toInt() ?: 0
                    )
                    playlistDao.addSongToPlaylist(song)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SONARA_SYNC", "Failed to sync from cloud: ${e.message}")
        }
    }

    /**
     * Pushes a single liked song to Firestore.
     */
    suspend fun pushLikedSong(song: LikedSongEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext
        val data = hashMapOf(
            "title" to song.title,
            "artist" to song.artist,
            "thumbnailUrl" to song.thumbnailUrl,
            "likedAt" to song.likedAt
        )
        firestore.collection("users")
            .document(userId)
            .collection("liked_songs")
            .document(song.videoId)
            .set(data)
            .await()
    }

    /**
     * Removes a liked song from Firestore.
     */
    suspend fun removeLikedSong(videoId: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext
        firestore.collection("users")
            .document(userId)
            .collection("liked_songs")
            .document(videoId)
            .delete()
            .await()
    }

    /**
     * Pushes a playlist and its metadata to Firestore.
     */
    suspend fun pushPlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext
        val data = hashMapOf(
            "name" to playlist.name,
            "createdAt" to playlist.createdAt
        )
        // Using name as ID for simplicity in this draft, ideally use a stable UUID
        firestore.collection("users")
            .document(userId)
            .collection("playlists")
            .document(playlist.name)
            .set(data)
            .await()
    }

    suspend fun pushSongToPlaylist(playlistName: String, song: PlaylistSongEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext
        val data = hashMapOf(
            "title" to song.title,
            "artist" to song.artist,
            "thumbnailUrl" to song.thumbnailUrl,
            "addedAt" to song.addedAt,
            "orderIndex" to song.orderIndex
        )
        firestore.collection("users")
            .document(userId)
            .collection("playlists")
            .document(playlistName)
            .collection("songs")
            .document(song.videoId)
            .set(data)
            .await()
    }

    suspend fun removeSongFromPlaylist(playlistName: String, videoId: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: return@withContext
        firestore.collection("users")
            .document(userId)
            .collection("playlists")
            .document(playlistName)
            .collection("songs")
            .document(videoId)
            .delete()
            .await()
    }
}
