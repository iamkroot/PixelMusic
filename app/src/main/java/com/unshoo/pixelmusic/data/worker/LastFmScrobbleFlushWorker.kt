package com.unshoo.pixelmusic.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.unshoo.pixelmusic.data.database.LastFmDao
import com.unshoo.pixelmusic.data.database.LastFmPendingScrobbleEntity
import com.unshoo.pixelmusic.data.lastfm.LastFmRepository
import com.unshoo.pixelmusic.data.lastfm.LastFmSubmitResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Drains the Last.fm pending-scrobble queue in batches of up to 50, oldest first.
 */
@HiltWorker
class LastFmScrobbleFlushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val lastFmDao: LastFmDao,
    private val repository: LastFmRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!repository.isAuthorized()) return Result.success()

        repeat(MAX_BATCHES_PER_RUN) {
            val batch = lastFmDao.oldestPending(BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            when (val result = repository.submitScrobbles(batch)) {
                LastFmSubmitResult.Success -> {
                    lastFmDao.deleteByIds(batch.map { it.id })
                }
                LastFmSubmitResult.InvalidPayload -> {
                    if (!dropInvalidScrobbles(batch)) {
                        lastFmDao.incrementAttempts(batch.map { it.id })
                        return Result.retry()
                    }
                }
                LastFmSubmitResult.AuthFailed -> {
                    Timber.w("Last.fm session rejected; pausing scrobble flush")
                    return Result.success()
                }
                is LastFmSubmitResult.TransientError -> {
                    lastFmDao.incrementAttempts(batch.map { it.id })
                    val retryAfterSeconds = result.retryAfterSeconds
                    return if (retryAfterSeconds != null) {
                        repository.scheduleFlushAfter(retryAfterSeconds)
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
            }
        }
        return Result.success()
    }

    /**
     * Resolves individual offending scrobbles with a throttled loop (max 4 req/sec) to comply with
     * Last.fm rate limits. Immediately halts on AuthFailed or TransientError to protect queue data.
     */
    private suspend fun dropInvalidScrobbles(batch: List<LastFmPendingScrobbleEntity>): Boolean {
        var progressed = false
        for ((index, scrobble) in batch.withIndex()) {
            if (index > 0) {
                delay(INDIVIDUAL_RETRY_DELAY_MS)
            }
            when (repository.submitScrobbles(listOf(scrobble))) {
                LastFmSubmitResult.Success -> {
                    lastFmDao.deleteByIds(listOf(scrobble.id))
                    progressed = true
                }
                LastFmSubmitResult.InvalidPayload -> {
                    Timber.w(
                        "Dropping scrobble rejected by Last.fm: %s — %s",
                        scrobble.artistName, scrobble.trackName
                    )
                    lastFmDao.deleteByIds(listOf(scrobble.id))
                    progressed = true
                }
                LastFmSubmitResult.AuthFailed,
                is LastFmSubmitResult.TransientError -> return progressed
            }
        }
        return true
    }

    companion object {
        const val WORK_NAME = "lastfm_scrobble_flush"
        private const val BATCH_SIZE = 50
        private const val MAX_BATCHES_PER_RUN = 5
        private const val INDIVIDUAL_RETRY_DELAY_MS = 250L

        fun request(initialDelaySeconds: Long = 0L): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<LastFmScrobbleFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .apply {
                    if (initialDelaySeconds > 0) {
                        setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                    }
                }
                .build()
        }
    }
}
