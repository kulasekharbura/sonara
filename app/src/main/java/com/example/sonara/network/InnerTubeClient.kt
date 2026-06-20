package com.example.sonara.network

import com.example.sonara.auth.YouTubeAuthManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Calls YouTube's internal /youtubei/v1/player endpoint to get stream URLs.
 *
 * When authenticated (after user login via WebView), uses the WEB_REMIX client
 * which matches the browser cookie context. This is the approach used by
 * InnerTune/OuterTune/SimpMusic for authenticated playback.
 */
object InnerTubeClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

    /**
     * Fetches a playable audio stream URL for the given video ID.
     * Uses WEB_REMIX client with authentication cookies from the WebView login.
     */
    fun getStreamUrl(videoId: String): String? {
        val isAuthenticated = YouTubeAuthManager.isLoggedIn()
        android.util.Log.d("SONARA_CORE", "InnerTube: authenticated=$isAuthenticated, trying WEB_REMIX for $videoId")

        // WEB_REMIX is the YouTube Music web client - matches browser cookie context
        val url = tryClient(
            videoId = videoId,
            body = createWebRemixBody(videoId),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            origin = "https://music.youtube.com",
            referer = "https://music.youtube.com/"
        )
        if (url != null) {
            android.util.Log.d("SONARA_CORE", "InnerTube WEB_REMIX resolved stream for $videoId (url length=${url.length})")
            return url
        }

        // Fallback: try regular WEB client with youtube.com
        val webUrl = tryClient(
            videoId = videoId,
            body = createWebBody(videoId),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/"
        )
        if (webUrl != null) {
            android.util.Log.d("SONARA_CORE", "InnerTube WEB resolved stream for $videoId")
            return webUrl
        }

        android.util.Log.w("SONARA_CORE", "InnerTube: all clients failed for $videoId")
        return null
    }

    private fun tryClient(videoId: String, body: String, userAgent: String, origin: String, referer: String): String? {
        try {
            val requestBuilder = Request.Builder()
                .url(PLAYER_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-Goog-Api-Format-Version", "2")
                .addHeader("X-YouTube-Client-Name", "67") // WEB_REMIX = 67
                .addHeader("X-YouTube-Client-Version", "1.20241023.01.00")
                .addHeader("Origin", origin)
                .addHeader("Referer", referer)
                .post(body.toRequestBody("application/json".toMediaType()))

            // Add auth cookies and SAPISIDHASH
            val cookies = YouTubeAuthManager.getCookies()
            if (cookies.isNotEmpty()) {
                requestBuilder.addHeader("Cookie", cookies)
                YouTubeAuthManager.generateAuthHeader(origin)?.let { auth ->
                    requestBuilder.addHeader("Authorization", auth)
                    android.util.Log.d("SONARA_CORE", "InnerTube: sending auth header for $origin")
                } ?: run {
                    android.util.Log.w("SONARA_CORE", "InnerTube: no SAPISID found in cookies, auth header skipped")
                }
            } else {
                android.util.Log.w("SONARA_CORE", "InnerTube: no cookies available")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null

            if (response.code != 200) {
                android.util.Log.w("SONARA_CORE", "InnerTube HTTP ${response.code} for $videoId")
                return null
            }

            val playerResponse = gson.fromJson(responseBody, InnerTubePlayerResponse::class.java)

            if (playerResponse.playabilityStatus?.status != "OK") {
                android.util.Log.w("SONARA_CORE", "InnerTube status: ${playerResponse.playabilityStatus?.status} reason: ${playerResponse.playabilityStatus?.reason} for $videoId")
                return null
            }

            // Look for audio streams with direct URLs
            val bestAudio = playerResponse.streamingData?.adaptiveFormats
                ?.filter { it.mimeType?.startsWith("audio/") == true && !it.url.isNullOrEmpty() }
                ?.maxByOrNull { it.bitrate ?: 0 }

            if (bestAudio?.url != null) {
                android.util.Log.d("SONARA_CORE", "InnerTube found direct audio URL (bitrate=${bestAudio.bitrate})")
                return bestAudio.url
            }

            // If no direct URLs, check if there are signatureCipher formats (need NewPipe to decode)
            val ciphered = playerResponse.streamingData?.adaptiveFormats
                ?.filter { it.mimeType?.startsWith("audio/") == true && !it.signatureCipher.isNullOrEmpty() }
            if (!ciphered.isNullOrEmpty()) {
                android.util.Log.w("SONARA_CORE", "InnerTube: ${ciphered.size} audio streams found but all have signatureCipher (need decoding)")
            } else {
                android.util.Log.w("SONARA_CORE", "InnerTube: no audio streams at all in response")
            }

            return null
        } catch (e: Exception) {
            android.util.Log.w("SONARA_CORE", "InnerTube client exception for $videoId: ${e.message}")
            return null
        }
    }

    private fun createWebRemixBody(videoId: String): String {
        return """
        {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20241023.01.00",
                    "hl": "en",
                    "gl": "US",
                    "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                },
                "user": {
                    "lockedSafetyMode": false
                }
            },
            "videoId": "$videoId",
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 20073
                }
            },
            "contentCheckOk": true,
            "racyCheckOk": true
        }
        """.trimIndent()
    }

    private fun createWebBody(videoId: String): String {
        return """
        {
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": "2.20241023.01.00",
                    "hl": "en",
                    "gl": "US",
                    "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                },
                "user": {
                    "lockedSafetyMode": false
                }
            },
            "videoId": "$videoId",
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 20073
                }
            },
            "contentCheckOk": true,
            "racyCheckOk": true
        }
        """.trimIndent()
    }
}

// Response models for Inner Tube player API
data class InnerTubePlayerResponse(
    val playabilityStatus: PlayabilityStatus?,
    val streamingData: StreamingData?
)

data class PlayabilityStatus(
    val status: String?,
    val reason: String?
)

data class StreamingData(
    val adaptiveFormats: List<AdaptiveFormat>?,
    val formats: List<AdaptiveFormat>?
)

data class AdaptiveFormat(
    val url: String?,
    val signatureCipher: String?,
    val mimeType: String?,
    val bitrate: Int?,
    @SerializedName("averageBitrate") val averageBitrate: Int?,
    val contentLength: String?,
    val quality: String?,
    val audioQuality: String?,
    val audioSampleRate: String?
)
