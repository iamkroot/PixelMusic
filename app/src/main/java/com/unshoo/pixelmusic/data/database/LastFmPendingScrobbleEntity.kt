package com.unshoo.pixelmusic.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A scrobble awaiting submission to Last.fm.
 *
 * Rows are deleted on successful submission or permanent rejection.
 * Metadata is snapshotted at enqueue time.
 */
@Entity(
    tableName = "lastfm_pending_scrobbles",
    indices = [Index(value = ["timestamp"])]
)
data class LastFmPendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Epoch timestamp in seconds when the track started playing (Last.fm timestamp semantics). */
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "track_name") val trackName: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long? = null,
    @ColumnInfo(name = "recording_mbid") val recordingMbid: String? = null,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long
)
