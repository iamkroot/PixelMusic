package com.unshoo.pixelmusic.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.unshoo.pixelmusic.data.database.ListenBrainzSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for Last.fm integration.
 * Includes per-source scrobble toggles, threshold configuration, and now playing status.
 */
@Singleton
class LastFmPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val SCROBBLE_LOCAL = booleanPreferencesKey("lastfm_scrobble_local")
        val SCROBBLE_NAVIDROME = booleanPreferencesKey("lastfm_scrobble_navidrome")
        val SCROBBLE_JELLYFIN = booleanPreferencesKey("lastfm_scrobble_jellyfin")
        val NOW_PLAYING_ENABLED = booleanPreferencesKey("lastfm_now_playing_enabled")
        val MIN_TRACK_DURATION_SEC = intPreferencesKey("lastfm_min_track_duration_sec")
        val DELAY_PERCENT = intPreferencesKey("lastfm_delay_percent")
        val DELAY_SECONDS = intPreferencesKey("lastfm_delay_seconds")
    }

    val scrobbleLocalFlow: Flow<Boolean> = dataStore.data.map { it[Keys.SCROBBLE_LOCAL] ?: true }
    val scrobbleNavidromeFlow: Flow<Boolean> = dataStore.data.map { it[Keys.SCROBBLE_NAVIDROME] ?: true }
    val scrobbleJellyfinFlow: Flow<Boolean> = dataStore.data.map { it[Keys.SCROBBLE_JELLYFIN] ?: true }
    val nowPlayingEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.NOW_PLAYING_ENABLED] ?: true }

    val minTrackDurationSecFlow: Flow<Int> = dataStore.data.map { it[Keys.MIN_TRACK_DURATION_SEC] ?: DEFAULT_MIN_TRACK_DURATION_SEC }
    val delayPercentFlow: Flow<Int> = dataStore.data.map { it[Keys.DELAY_PERCENT] ?: DEFAULT_DELAY_PERCENT }
    val delaySecondsFlow: Flow<Int> = dataStore.data.map { it[Keys.DELAY_SECONDS] ?: DEFAULT_DELAY_SECONDS }

    suspend fun setScrobbleLocal(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_LOCAL] = enabled }
    }

    suspend fun setScrobbleNavidrome(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_NAVIDROME] = enabled }
    }

    suspend fun setScrobbleJellyfin(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_JELLYFIN] = enabled }
    }

    suspend fun setNowPlayingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOW_PLAYING_ENABLED] = enabled }
    }

    suspend fun setMinTrackDurationSec(seconds: Int) {
        dataStore.edit { it[Keys.MIN_TRACK_DURATION_SEC] = seconds }
    }

    suspend fun setDelayPercent(percent: Int) {
        dataStore.edit { it[Keys.DELAY_PERCENT] = percent }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        dataStore.edit { it[Keys.DELAY_SECONDS] = seconds }
    }

    suspend fun isSourceEnabled(source: String): Boolean {
        val key = when (source) {
            ListenBrainzSource.NAVIDROME -> Keys.SCROBBLE_NAVIDROME
            ListenBrainzSource.JELLYFIN -> Keys.SCROBBLE_JELLYFIN
            else -> Keys.SCROBBLE_LOCAL
        }
        return dataStore.data.first()[key] ?: true
    }

    suspend fun meetsScrobbleThreshold(listenedMs: Long, trackDurationMs: Long): Boolean {
        val prefs = dataStore.data.first()
        val minDurationSec = prefs[Keys.MIN_TRACK_DURATION_SEC] ?: DEFAULT_MIN_TRACK_DURATION_SEC
        val delayPercent = prefs[Keys.DELAY_PERCENT] ?: DEFAULT_DELAY_PERCENT
        val delaySec = prefs[Keys.DELAY_SECONDS] ?: DEFAULT_DELAY_SECONDS

        val minDurationMs = minDurationSec * 1000L
        val maxDurationCapMs = delaySec * 1000L

        // If track duration is known and below the minimum track duration, do not scrobble
        if (trackDurationMs > 0 && trackDurationMs < minDurationMs) {
            return false
        }

        val requiredMs = if (trackDurationMs > 0) {
            minOf(maxDurationCapMs, (trackDurationMs * delayPercent) / 100)
        } else {
            maxDurationCapMs
        }

        return listenedMs >= requiredMs
    }

    companion object {
        const val DEFAULT_MIN_TRACK_DURATION_SEC = 30
        const val DEFAULT_DELAY_PERCENT = 50
        const val DEFAULT_DELAY_SECONDS = 240
    }
}
