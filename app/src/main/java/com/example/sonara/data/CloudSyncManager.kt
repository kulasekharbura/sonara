package com.example.sonara.data

import android.util.Log
import com.example.sonara.data.db.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Handles synchronization between local Room database and Firebase Firestore.
 *
 * WRITE STRATEGY: Uses Tasks.await() on Dispatchers.IO for all write operations.
 * This forces a blocking wait on a background thread until the server confirms
 * the write, bypassing the Firestore SDK's gRPC WriteStream which can silently
 * fail on some devices/networks.
 */
class CloudSyncManager(private val database: SonaraDatabase) {

    private val firestore = FirebaseFirestore.getInstance("default")
    private val auth = Firebase.auth
    private val likedSongDao = database.likedSongDao()
    private val playlistDao = database.playlistDao()

    private val TAG = "SONARA_SYNC"

    /**
     * Pulls data from Firestore and merges it into the local database.
     */
    suspend fun syncFromCloud() = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "syncFromCloud aborted: No user authenticated.")
            return@withContext
        }

        try {
            Log.d(TAG, "Starting cloud pull for user: $userId")

            // Ensure the user document exists so it's visible in Firebase Console
            val userDoc = hashMapOf(
                "lastSyncAt" to System.currentTimeMillis(),
                "email" to (auth.currentUser?.email ?: "")
            )
            firestore.collection("users").document(userId).set(userDoc, com.google.firebase.firestore.SetOptions.merge()).await()

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
                    likedAt = doc.getLong("likedAt") ?: System.currentTimeMillis(),
                    userId = userId
                )
                likedSongDao.insert(song)
            }

            val playlistsSnapshot = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .get()
                .await()

            playlistsSnapshot.documents.forEach { doc ->
                val playlistName = doc.getString("name") ?: "Unnamed Playlist"
                val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                val remoteId = doc.id

                val localPlaylists = playlistDao.getAllPlaylists(userId).first()
                val existingPlaylist = localPlaylists.find { it.remoteId == remoteId || it.name == playlistName }

                val localId = existingPlaylist?.id ?: playlistDao.createPlaylist(
                    PlaylistEntity(name = playlistName, createdAt = createdAt, remoteId = remoteId, userId = userId)
                )

                val songsSnapshot = firestore.collection("users")
                    .document(userId)
                    .collection("playlists")
                    .document(remoteId)
                    .collection("songs")
                    .get()
                    .await()

                songsSnapshot.documents.forEach { songDoc ->
                    val pSong = PlaylistSongEntity(
                        playlistId = localId,
                        videoId = songDoc.id,
                        title = songDoc.getString("title") ?: "",
                        artist = songDoc.getString("artist") ?: "",
                        thumbnailUrl = songDoc.getString("thumbnailUrl") ?: "",
                        addedAt = songDoc.getLong("addedAt") ?: System.currentTimeMillis(),
                        orderIndex = songDoc.getLong("orderIndex")?.toInt() ?: 0
                    )
                    playlistDao.addSongToPlaylist(pSong)
                }
            }
            Log.d(TAG, "Cloud pull completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during syncFromCloud: ${e.message}", e)
        }
    }

    suspend fun pushLikedSong(song: LikedSongEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "pushLikedSong aborted: No user authenticated.")
            return@withContext
        }

        try {
            val data = hashMapOf(
                "title" to song.title,
                "artist" to song.artist,
                "thumbnailUrl" to song.thumbnailUrl,
                "likedAt" to song.likedAt
            )
            val task = firestore.collection("users")
                .document(userId)
                .collection("liked_songs")
                .document(song.videoId)
                .set(data)

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER WRITE OK (pushLikedSong): ${song.title}")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER WRITE FAILED (pushLikedSong): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun removeLikedSong(videoId: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "removeLikedSong aborted: No user authenticated.")
            return@withContext
        }

        try {
            val task = firestore.collection("users")
                .document(userId)
                .collection("liked_songs")
                .document(videoId)
                .delete()

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER DELETE OK (removeLikedSong): $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER DELETE FAILED (removeLikedSong): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun pushPlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "pushPlaylist aborted: No user authenticated.")
            return@withContext
        }

        val docId = playlist.remoteId.ifEmpty {
            Log.e(TAG, "pushPlaylist aborted: empty remoteId for ${playlist.name}")
            return@withContext
        }

        try {
            val data = hashMapOf(
                "name" to playlist.name,
                "createdAt" to playlist.createdAt
            )
            val task = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .document(docId)
                .set(data)

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER WRITE OK (pushPlaylist): ${playlist.name}")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER WRITE FAILED (pushPlaylist): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun pushSongToPlaylist(playlistRemoteId: String, song: PlaylistSongEntity) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "pushSongToPlaylist aborted: No user authenticated.")
            return@withContext
        }

        if (playlistRemoteId.isEmpty()) {
            Log.e(TAG, "pushSongToPlaylist aborted: empty playlistRemoteId")
            return@withContext
        }

        try {
            val data = hashMapOf(
                "title" to song.title,
                "artist" to song.artist,
                "thumbnailUrl" to song.thumbnailUrl,
                "addedAt" to song.addedAt,
                "orderIndex" to song.orderIndex
            )
            val task = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .document(playlistRemoteId)
                .collection("songs")
                .document(song.videoId)
                .set(data)

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER WRITE OK (pushSongToPlaylist): ${song.title} → $playlistRemoteId")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER WRITE FAILED (pushSongToPlaylist): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun removeSongFromPlaylist(playlistRemoteId: String, videoId: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "removeSongFromPlaylist aborted: No user authenticated.")
            return@withContext
        }

        try {
            val task = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .document(playlistRemoteId)
                .collection("songs")
                .document(videoId)
                .delete()

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER DELETE OK (removeSongFromPlaylist): $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER DELETE FAILED (removeSongFromPlaylist): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun removePlaylist(playlistRemoteId: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "removePlaylist aborted: No user authenticated.")
            return@withContext
        }

        if (playlistRemoteId.isEmpty()) {
            Log.e(TAG, "removePlaylist aborted: empty playlistRemoteId")
            return@withContext
        }

        try {
            // First delete all songs in the playlist subcollection
            val songsSnapshot = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .document(playlistRemoteId)
                .collection("songs")
                .get()

            val songs = com.google.android.gms.tasks.Tasks.await(songsSnapshot, 30, java.util.concurrent.TimeUnit.SECONDS)
            for (doc in songs.documents) {
                val deleteTask = doc.reference.delete()
                com.google.android.gms.tasks.Tasks.await(deleteTask, 10, java.util.concurrent.TimeUnit.SECONDS)
            }

            // Then delete the playlist document itself
            val task = firestore.collection("users")
                .document(userId)
                .collection("playlists")
                .document(playlistRemoteId)
                .delete()

            com.google.android.gms.tasks.Tasks.await(task, 30, java.util.concurrent.TimeUnit.SECONDS)
            Log.d(TAG, "SERVER DELETE OK (removePlaylist): $playlistRemoteId")
        } catch (e: Exception) {
            Log.e(TAG, "SERVER DELETE FAILED (removePlaylist): ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
