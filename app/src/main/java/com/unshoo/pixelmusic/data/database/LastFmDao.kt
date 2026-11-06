package com.unshoo.pixelmusic.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LastFmDao {

    @Insert
    suspend fun insert(scrobble: LastFmPendingScrobbleEntity): Long

    /** Oldest-first so flush preserves listen order. Last.fm supports up to 50 items per batch. */
    @Query("SELECT * FROM lastfm_pending_scrobbles ORDER BY timestamp ASC LIMIT :limit")
    suspend fun oldestPending(limit: Int): List<LastFmPendingScrobbleEntity>

    @Query("DELETE FROM lastfm_pending_scrobbles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE lastfm_pending_scrobbles SET attempts = attempts + 1 WHERE id IN (:ids)")
    suspend fun incrementAttempts(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM lastfm_pending_scrobbles")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM lastfm_pending_scrobbles")
    fun countFlow(): Flow<Int>

    /** Drops oldest rows beyond queue cap so offline table cannot grow unbounded. */
    @Query(
        """
        DELETE FROM lastfm_pending_scrobbles
        WHERE id IN (
            SELECT id FROM lastfm_pending_scrobbles
            ORDER BY timestamp ASC
            LIMIT :overflow
        )
        """
    )
    suspend fun deleteOldest(overflow: Int)

    @Query("DELETE FROM lastfm_pending_scrobbles")
    suspend fun clear()
}
