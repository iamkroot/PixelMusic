package com.unshoo.pixelmusic.data.listenbrainz

import android.os.SystemClock
import com.unshoo.pixelmusic.data.database.LastFmDao
import com.unshoo.pixelmusic.data.database.LastFmPendingScrobbleEntity
import com.unshoo.pixelmusic.data.database.ListenBrainzDao
import com.unshoo.pixelmusic.data.database.ListenBrainzPendingListenEntity
import com.unshoo.pixelmusic.data.database.ListenBrainzSource
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.data.lastfm.LastFmRepository
import com.unshoo.pixelmusic.data.preferences.LastFmPreferencesRepository
import com.unshoo.pixelmusic.data.preferences.ListenBrainzPreferencesRepository
import com.unshoo.pixelmusic.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges finalized listening sessions to the ListenBrainz and Last.fm submission queues.
 *
 * Fed by [com.unshoo.pixelmusic.presentation.viewmodel.ListeningStatsTracker]: the listen
 * threshold is evaluated synchronously (cheap math, safe inside the tracker's synchronized
 * finalize), everything else — song resolution, toggle check, queue insert — runs on [scope].
 */
@Singleton
class ScrobbleManager @Inject constructor(
    private val musicDao: MusicDao,
    private val listenBrainzDao: ListenBrainzDao,
    private val listenBrainzRepository: ListenBrainzRepository,
    private val listenBrainzPreferences: ListenBrainzPreferencesRepository,
    private val lastFmDao: LastFmDao,
    private val lastFmRepository: LastFmRepository,
    private val lastFmPreferences: LastFmPreferencesRepository,
    @AppScope private val scope: CoroutineScope
) {
    private var lastPlayingNowSongId: String? = null
    private var lastPlayingNowRealtimeMs: Long = 0L

    init {
        // Flush listens/scrobbles left queued by a previous process (e.g. killed while offline).
        scope.launch {
            runCatching {
                if (listenBrainzRepository.isAuthorized() && listenBrainzDao.count() > 0) {
                    listenBrainzRepository.scheduleFlush()
                }
            }.onFailure { Timber.e(it, "Failed to schedule startup ListenBrainz flush") }

            runCatching {
                if (lastFmRepository.isAuthorized() && lastFmDao.count() > 0) {
                    lastFmRepository.scheduleFlush()
                }
            }.onFailure { Timber.e(it, "Failed to schedule startup Last.fm flush") }
        }
    }

    fun onSessionFinalized(
        songId: String,
        startedAtEpochMs: Long,
        listenedMs: Long,
        trackDurationMs: Long
    ) {
        // ListenBrainz queue
        if (listenBrainzRepository.hasToken() && meetsListenThreshold(listenedMs, trackDurationMs)) {
            scope.launch {
                runCatching { enqueueListenBrainz(songId, startedAtEpochMs) }
                    .onFailure { Timber.e(it, "Failed to enqueue ListenBrainz listen for song=%s", songId) }
            }
        }

        // Last.fm queue
        if (lastFmRepository.hasSession()) {
            scope.launch {
                runCatching {
                    if (lastFmPreferences.meetsScrobbleThreshold(listenedMs, trackDurationMs)) {
                        enqueueLastFmScrobble(songId, startedAtEpochMs)
                    }
                }.onFailure { Timber.e(it, "Failed to enqueue Last.fm scrobble for song=%s", songId) }
            }
        }
    }

    fun onPlayingNow(songId: String) {
        val lbAuth = listenBrainzRepository.isAuthorized()
        val lfmAuth = lastFmRepository.isAuthorized()
        if (!lbAuth && !lfmAuth) return

        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (songId == lastPlayingNowSongId && now - lastPlayingNowRealtimeMs < PLAYING_NOW_DEBOUNCE_MS) {
                return
            }
            lastPlayingNowSongId = songId
            lastPlayingNowRealtimeMs = now
        }

        if (lbAuth) {
            scope.launch {
                runCatching {
                    val song = resolveAdmittedSong(songId) ?: return@launch
                    listenBrainzRepository.submitPlayingNow(
                        trackName = song.title,
                        artistName = song.artistName,
                        releaseName = song.albumName.takeIf { it.isNotBlank() },
                        durationMs = song.duration.takeIf { it > 0 },
                        recordingMbid = song.mbRecordingId
                    )
                }.onFailure { Timber.d(it, "ListenBrainz playing-now failed for song=%s", songId) }
            }
        }

        if (lfmAuth) {
            scope.launch {
                runCatching {
                    if (!lastFmPreferences.nowPlayingEnabledFlow.first()) return@launch
                    val song = resolveAdmittedSongForLastFm(songId) ?: return@launch

                    lastFmRepository.submitPlayingNow(
                        trackName = song.title,
                        artistName = song.artistName,
                        albumName = song.albumName.takeIf { it.isNotBlank() },
                        durationMs = song.duration.takeIf { it > 0 },
                        recordingMbid = song.mbRecordingId
                    )
                }.onFailure { Timber.d(it, "Last.fm playing-now failed for song=%s", songId) }
            }
        }
    }

    private suspend fun enqueueListenBrainz(songId: String, startedAtEpochMs: Long) {
        val song = resolveAdmittedSong(songId) ?: return
        val inserted = listenBrainzRepository.enqueueListen(
            ListenBrainzPendingListenEntity(
                listenedAtMs = startedAtEpochMs,
                trackName = song.title,
                artistName = song.artistName,
                releaseName = song.albumName.takeIf { it.isNotBlank() },
                durationMs = song.duration.takeIf { it > 0 },
                recordingMbid = song.mbRecordingId,
                source = ListenBrainzSource.fromSourceType(song.sourceType),
                createdAtMs = System.currentTimeMillis()
            )
        )
        if (inserted && listenBrainzRepository.isAuthorized()) {
            listenBrainzRepository.scheduleFlush()
        }
    }

    private suspend fun enqueueLastFmScrobble(songId: String, startedAtEpochMs: Long) {
        val song = resolveAdmittedSongForLastFm(songId) ?: return

        val inserted = lastFmRepository.enqueueScrobble(
            LastFmPendingScrobbleEntity(
                timestamp = startedAtEpochMs / 1000,
                trackName = song.title,
                artistName = song.artistName,
                albumName = song.albumName.takeIf { it.isNotBlank() },
                durationSeconds = song.duration.takeIf { it > 0 }?.let { it / 1000 },
                recordingMbid = song.mbRecordingId,
                source = ListenBrainzSource.fromSourceType(song.sourceType),
                createdAtMs = System.currentTimeMillis()
            )
        )
        if (inserted && lastFmRepository.isAuthorized()) {
            lastFmRepository.scheduleFlush()
        }
    }

    /**
     * Resolves the song, or null when it no longer exists, lacks the metadata ListenBrainz
     * requires, or its playback source's scrobble toggle is off.
     */
    private suspend fun resolveAdmittedSong(songId: String): SongEntity? {
        val id = songId.toLongOrNull() ?: return null
        val song = musicDao.getSongByIdOnce(id) ?: return null
        if (song.title.isBlank() || song.artistName.isBlank()) return null
        val source = ListenBrainzSource.fromSourceType(song.sourceType)
        if (!listenBrainzPreferences.isSourceEnabled(source)) return null
        return song
    }

    /**
     * Resolves the song, or null when it lacks required metadata or its playback source's
     * Last.fm toggle is off.
     */
    private suspend fun resolveAdmittedSongForLastFm(songId: String): SongEntity? {
        val id = songId.toLongOrNull() ?: return null
        val song = musicDao.getSongByIdOnce(id) ?: return null
        if (song.title.isBlank() || song.artistName.isBlank()) return null
        val source = ListenBrainzSource.fromSourceType(song.sourceType)
        if (!lastFmPreferences.isSourceEnabled(source)) return null
        return song
    }

    companion object {
        private const val PLAYING_NOW_DEBOUNCE_MS = 30_000L
        private val FOUR_MINUTES_MS = TimeUnit.MINUTES.toMillis(4)

        /**
         * ListenBrainz listen rule: 4 minutes or half the track, whichever is lower;
         * plain 4-minute rule when the duration is unknown.
         */
        fun meetsListenThreshold(listenedMs: Long, trackDurationMs: Long): Boolean {
            val required = if (trackDurationMs > 0) {
                minOf(FOUR_MINUTES_MS, trackDurationMs / 2)
            } else {
                FOUR_MINUTES_MS
            }
            return listenedMs >= required
        }
    }
}
