package com.example.sonara.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed implementation of NewPipeExtractor's abstract [Downloader].
 *
 * NewPipeExtractor is platform-agnostic: it does not ship an HTTP client. It asks us to perform
 * every network request (fetching the watch page, the player JS used to decipher signatures, the
 * youtubei player endpoint, etc.) through this single [execute] method. By centralising all traffic
 * here we get a consistent User-Agent and timeout policy, and NewPipe handles the hard part:
 * running YouTube's player JavaScript to solve the signature and `n` throttling ciphers so the
 * stream URLs it returns are actually playable.
 */
class NewPipeDownloaderImpl private constructor(
    builder: OkHttpClient.Builder
) : Downloader() {

    private val client: OkHttpClient = builder
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        // GET/HEAD must pass a null body; POST sends the raw bytes NewPipe gives us.
        val requestBody = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        // Apply NewPipe-supplied headers. A header can legitimately have multiple values
        // (e.g. Set-Cookie), so add each value rather than overwriting.
        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        // YouTube returns 429 when it wants a captcha solved. Surface it so the extractor
        // (and our app) can react instead of treating the captcha HTML as real data.
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBodyToReturn,
            latestUrl
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        @Volatile
        private var instance: NewPipeDownloaderImpl? = null

        /**
         * Creates (or returns the existing) singleton downloader. Pass an existing
         * [OkHttpClient.Builder] to reuse interceptors/cache if you have one.
         */
        fun getInstance(
            builder: OkHttpClient.Builder = OkHttpClient.Builder()
        ): NewPipeDownloaderImpl =
            instance ?: synchronized(this) {
                instance ?: NewPipeDownloaderImpl(builder).also { instance = it }
            }
    }
}
