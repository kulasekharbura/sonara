package com.example.sonara.network

import retrofit2.http.GET
import retrofit2.http.Url
import retrofit2.http.Query

interface YoutubeApiService {
    @GET
    suspend fun searchTracks(
        @Url dynamicUrl: String,
        @Query("q") searchQuery: String,
        @Query("type") type: String = "video"
    ): List<InvidiousSearchItem>
}