package com.example.sonara.network

import retrofit2.http.GET
import retrofit2.http.Path

interface PipedApiService {
    @GET("streams/{videoId}")
    suspend fun getStreams(@Path("videoId") videoId: String): PipedStreamResponse
}
