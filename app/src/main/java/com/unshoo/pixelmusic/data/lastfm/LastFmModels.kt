package com.unshoo.pixelmusic.data.lastfm

import com.google.gson.annotations.SerializedName

data class LastFmSessionResponse(
    @SerializedName("session") val session: LastFmSession? = null,
    @SerializedName("error") val error: Int? = null,
    @SerializedName("message") val message: String? = null
)

data class LastFmSession(
    @SerializedName("name") val name: String,
    @SerializedName("key") val key: String,
    @SerializedName("subscriber") val subscriber: Int = 0
)

data class LastFmScrobbleResponse(
    @SerializedName("scrobbles") val scrobbles: LastFmScrobblesPayload? = null,
    @SerializedName("error") val error: Int? = null,
    @SerializedName("message") val message: String? = null
)

data class LastFmScrobblesPayload(
    @SerializedName("@attr") val attr: LastFmScrobblesAttr? = null
)

data class LastFmScrobblesAttr(
    @SerializedName("accepted") val accepted: Int = 0,
    @SerializedName("ignored") val ignored: Int = 0
)

data class LastFmNowPlayingResponse(
    @SerializedName("nowplaying") val nowPlaying: Any? = null,
    @SerializedName("error") val error: Int? = null,
    @SerializedName("message") val message: String? = null
)

data class LastFmUserInfoResponse(
    @SerializedName("user") val user: LastFmUser? = null,
    @SerializedName("error") val error: Int? = null,
    @SerializedName("message") val message: String? = null
)

data class LastFmUser(
    @SerializedName("name") val name: String,
    @SerializedName("playcount") val playcount: String? = null
)

data class LastFmErrorResponse(
    @SerializedName("error") val error: Int? = null,
    @SerializedName("message") val message: String? = null
)

data class LastFmAccountState(
    val isConnected: Boolean = false,
    val userName: String? = null,
    val needsReauth: Boolean = false,
    val hasCustomCredentials: Boolean = false
)

data class LastFmProfileStats(
    val playcount: Long? = null
)

sealed interface LastFmSubmitResult {
    data object Success : LastFmSubmitResult
    data object AuthFailed : LastFmSubmitResult
    data object InvalidPayload : LastFmSubmitResult
    data class TransientError(val retryAfterSeconds: Long? = null) : LastFmSubmitResult
}
