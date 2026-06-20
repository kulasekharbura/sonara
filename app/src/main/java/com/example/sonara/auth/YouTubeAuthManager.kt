package com.example.sonara.auth

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

object YouTubeAuthManager {
    private const val PREFS_NAME = "sonara_youtube_auth"
    private const val KEY_COOKIES = "youtube_cookies"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(): Boolean {
        val cookies = getCookies()
        return cookies.isNotEmpty() && (cookies.contains("SAPISID") || cookies.contains("SID=") || cookies.contains("HSID"))
    }

    fun saveCookies(cookies: String) {
        prefs?.edit()?.putString(KEY_COOKIES, cookies)?.apply()
    }

    fun getCookies(): String {
        return prefs?.getString(KEY_COOKIES, "") ?: ""
    }

    fun clearCookies() {
        prefs?.edit()?.remove(KEY_COOKIES)?.apply()
    }

    /**
     * Generates SAPISIDHASH for authorization header.
     * Format: SAPISIDHASH {unix_time}_{sha1(unix_time + " " + SAPISID + " " + origin)}
     */
    fun generateAuthHeader(origin: String = "https://music.youtube.com"): String? {
        val cookies = getCookies()
        val sapisid = extractCookieValue(cookies, "SAPISID") ?: return null
        val time = System.currentTimeMillis() / 1000
        val input = "$time $sapisid $origin"
        val hash = sha1(input)
        return "SAPISIDHASH ${time}_$hash"
    }

    private fun extractCookieValue(cookies: String, name: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("$name=") }
            ?.substringAfter("$name=")
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
