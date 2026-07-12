package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-episode listen-intensity histogram powering the structured
 * scrubber's replay heatmap. [BUCKET_COUNT] equal-width position buckets
 * across the episode; PlaybackService's 10s save ticker increments the
 * bucket under the playhead, so re-listened passages accumulate heat.
 *
 * Stored as CSV TEXT (house style — cf. eqBandsCsvOverride) rather than a
 * BLOB: ~600 bytes/row, debuggable in a sqlite shell. Counts saturate at
 * 9999 so the row can't grow. Derived analytics — deliberately excluded
 * from Backup.kt's curated export set.
 */
@Entity(tableName = "episode_heat")
data class EpisodeHeatEntity(
    @PrimaryKey val guid: String,
    /** CSV of exactly [BUCKET_COUNT] non-negative ints. */
    val bucketsCsv: String,
    val updatedAt: Long,
) {
    companion object {
        const val BUCKET_COUNT = 200
    }
}
