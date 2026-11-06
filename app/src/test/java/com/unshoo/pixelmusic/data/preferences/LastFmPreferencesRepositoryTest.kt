package com.unshoo.pixelmusic.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class LastFmPreferencesRepositoryTest {

    @Test
    fun `default values and threshold calculation match Last_fm specifications`() = runTest {
        val tempDir = Files.createTempDirectory("lastfm-preferences-test")
        try {
            val repository = LastFmPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("lastfm_prefs.preferences_pb").toFile() }
                )
            )

            // Defaults: all sources true, now playing true, 30s min, 50%, 240s max
            assertTrue(repository.scrobbleLocalFlow.first())
            assertTrue(repository.scrobbleNavidromeFlow.first())
            assertTrue(repository.scrobbleJellyfinFlow.first())
            assertTrue(repository.nowPlayingEnabledFlow.first())
            assertEquals(30, repository.minTrackDurationSecFlow.first())
            assertEquals(50, repository.delayPercentFlow.first())
            assertEquals(240, repository.delaySecondsFlow.first())

            // Tracks under 30 seconds should never scrobble
            assertFalse(repository.meetsScrobbleThreshold(listenedMs = 25_000L, trackDurationMs = 25_000L))

            // 3-minute track (180,000ms) requires 50% = 90,000ms
            assertFalse(repository.meetsScrobbleThreshold(listenedMs = 89_999L, trackDurationMs = 180_000L))
            assertTrue(repository.meetsScrobbleThreshold(listenedMs = 90_000L, trackDurationMs = 180_000L))

            // 10-minute track (600,000ms) capped at 240,000ms (4 minutes)
            assertFalse(repository.meetsScrobbleThreshold(listenedMs = 239_999L, trackDurationMs = 600_000L))
            assertTrue(repository.meetsScrobbleThreshold(listenedMs = 240_000L, trackDurationMs = 600_000L))

            // Custom configuration persists and changes threshold
            repository.setDelayPercent(75)
            assertEquals(75, repository.delayPercentFlow.first())
            // With 75% on a 200,000ms track -> requires 150,000ms
            assertFalse(repository.meetsScrobbleThreshold(listenedMs = 140_000L, trackDurationMs = 200_000L))
            assertTrue(repository.meetsScrobbleThreshold(listenedMs = 150_000L, trackDurationMs = 200_000L))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
