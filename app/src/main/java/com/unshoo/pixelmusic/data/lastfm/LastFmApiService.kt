package com.unshoo.pixelmusic.data.lastfm

import retrofit2.Response
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LastFmApiService {

    @FormUrlEncoded
    @POST("2.0/")
    suspend fun getMobileSession(
        @FieldMap params: Map<String, String>,
        @Query("format") format: String = "json"
    ): Response<LastFmSessionResponse>

    @FormUrlEncoded
    @POST("2.0/")
    suspend fun scrobble(
        @FieldMap params: Map<String, String>,
        @Query("format") format: String = "json"
    ): Response<LastFmScrobbleResponse>

    @FormUrlEncoded
    @POST("2.0/")
    suspend fun updateNowPlaying(
        @FieldMap params: Map<String, String>,
        @Query("format") format: String = "json"
    ): Response<LastFmNowPlayingResponse>

    @GET("2.0/")
    suspend fun getUserInfo(
        @Query("method") method: String = "user.getInfo",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json"
    ): Response<LastFmUserInfoResponse>
}
