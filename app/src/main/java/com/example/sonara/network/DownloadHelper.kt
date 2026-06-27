package com.example.sonara.network

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads audio files using OkHttp instead of Android's DownloadManager.
 * This is necessary because YouTube's CDN (googlevideo.com) rejects requests
 * from DownloadManager due to missing/incorrect headers.
 */
object DownloadHelper {

    private const val TAG = "SONARA_DOWNLOAD"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Downloads a file using OkHttp with proper headers.
     * Must be called from a coroutine (suspend function).
     * Reports progress via onProgress callback on the main thread.
     */
    suspend fun downloadWithStatus(
        context: Context,
        url: String,
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String,
        onProgress: (Int) -> Unit,
        showToasts: Boolean = true
    ) {
        if (url.isEmpty()) {
            if (showToasts) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cannot download: stream URL not found", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val safeTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Track" }
        val safeArtist = artist.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Artist" }
        val fileName = "${safeTitle}_${safeArtist}_$videoId.m4a"
        val tempFileName = "$fileName.tmp"

        if (showToasts) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Starting download: $title", Toast.LENGTH_SHORT).show()
            }
        }

        withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // Ensure Sonara folder exists
                val sonaraDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Sonara"
                )
                if (!sonaraDir.exists()) sonaraDir.mkdirs()

                val outputFile = File(sonaraDir, fileName)
                tempFile = File(sonaraDir, tempFileName)

                Log.d(TAG, "Downloading: $title → ${tempFile.absolutePath}")
                Log.d(TAG, "URL: ${url.take(100)}...")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.youtube.com/")
                    .header("Origin", "https://www.youtube.com")
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Download HTTP error: ${response.code} for $title")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: HTTP ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    response.close()
                    return@withContext
                }

                val body = response.body ?: run {
                    Log.e(TAG, "Download failed: empty response body for $title")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: empty response", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val contentLength = body.contentLength()
                Log.d(TAG, "Content-Length: $contentLength bytes for $title")

                var totalBytesRead = 0L
                val buffer = ByteArray(8192)

                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { inputStream ->
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val progress = (totalBytesRead * 100 / contentLength).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                // Rename temp to final
                if (tempFile.renameTo(outputFile)) {
                    Log.i(TAG, "Download complete and renamed: $title (${totalBytesRead / 1024} KB)")
                    // Final progress = 100
                    withContext(Dispatchers.Main) {
                        onProgress(100)
                    }
                } else {
                    Log.e(TAG, "Failed to rename temp file for $title")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: could not save file", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download exception for $title: ${e.javaClass.simpleName}: ${e.message}")
                tempFile?.let {
                    if (it.exists()) {
                        it.delete()
                        Log.d(TAG, "Deleted partial temp file after error: ${it.name}")
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun removeDownload(context: Context, videoId: String, title: String, artist: String) {
        val safeTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Track" }
        val safeArtist = artist.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Artist" }
        val fileName = "${safeTitle}_${safeArtist}_$videoId.m4a"
        val tempFileName = "$fileName.tmp"
        val sonaraDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Sonara"
        )
        
        val file = File(sonaraDir, fileName)
        if (file.exists()) {
            if (file.delete()) {
                Log.i(TAG, "Deleted file: ${file.absolutePath}")
            } else {
                Log.w(TAG, "Failed to delete file: ${file.absolutePath}")
            }
        }

        val tempFile = File(sonaraDir, tempFileName)
        if (tempFile.exists()) {
            tempFile.delete()
        }

        // Also try to delete the old format file just in case
        val oldFileName = "$safeTitle.m4a"
        val oldFile = File(sonaraDir, oldFileName)
        if (oldFile.exists()) oldFile.delete()
    }

    fun isFileDownloaded(videoId: String, title: String, artist: String): Boolean {
        val safeTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Track" }
        val safeArtist = artist.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Artist" }
        val fileName = "${safeTitle}_${safeArtist}_$videoId.m4a"
        val sonaraDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Sonara"
        )
        val file = File(sonaraDir, fileName)
        if (file.exists()) return true

        // Check old format for compatibility
        val oldFileName = "$safeTitle.m4a"
        return File(sonaraDir, oldFileName).exists()
    }
}
