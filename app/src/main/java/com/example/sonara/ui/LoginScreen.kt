package com.example.sonara.ui

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sonara.auth.YouTubeAuthManager

@Composable
fun LoginScreen(onLoginComplete: () -> Unit) {
    var loginDone by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!loginDone) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        // Enable cookies
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentUrl = url ?: return
                                // After login, user lands on music.youtube.com
                                if ((currentUrl.contains("music.youtube.com") || currentUrl.contains("youtube.com"))
                                    && !currentUrl.contains("accounts.google.com")
                                    && !currentUrl.contains("signin")) {
                                    // Get cookies from multiple YouTube domains
                                    val cookieManager = CookieManager.getInstance()
                                    val musicCookies = cookieManager.getCookie("https://music.youtube.com") ?: ""
                                    val ytCookies = cookieManager.getCookie("https://www.youtube.com") ?: ""
                                    
                                    // Use whichever has more content
                                    val cookies = if (musicCookies.length > ytCookies.length) musicCookies else ytCookies
                                    
                                    // Check for any auth cookie (SID, HSID, SSID are always set on login)
                                    if (cookies.contains("SID=") || cookies.contains("HSID") || cookies.contains("SAPISID")) {
                                        android.util.Log.d("SONARA_AUTH", "Login cookies captured! Length: ${cookies.length}")
                                        YouTubeAuthManager.saveCookies(cookies)
                                        loginDone = true
                                        onLoginComplete()
                                    }
                                }
                            }
                        }

                        loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F&service=youtube&passive=true&hl=en")
                    }
                }
            )
        }
    }
}
