package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeHeatEntity
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Write-side aggregator for the replay heatmap. PlaybackService's 10s
 * save ticker calls [tick] with the current playhead; the bucket under it
 * gets +1 and the row is upserted. The in-memory bucket array is cached
 * per guid so steady-state is one small row write per tick, no read.
 *
 * Concurrency: each saveCurrent launches a FRESH coroutine on the
 * multi-threaded IO pool, so consecutive ticks CAN overlap when a DB
 * write stalls past the 10s interval. The mutex serializes them — an
 * overlapping pair straddling an episode transition could otherwise
 * write one episode's buckets into the other's row.
 */
class EpisodeHeatRecorder(private val db: AppDatabase) {

    private val mutex = Mutex()
    private var currentGuid: String? = null
    private var buckets: IntArray = IntArray(EpisodeHeatEntity.BUCKET_COUNT)

    /** Call from an IO context. Skips when duration is unknown. */
    suspend fun tick(guid: String, positionMs: Long, durationMs: Long) = mutex.withLock {
        if (durationMs <= 0) return@withLock
        if (guid != currentGuid) {
            buckets = db.episodeHeatDao().get(guid)
                ?.let { decode(it.bucketsCsv) }
                ?: IntArray(EpisodeHeatEntity.BUCKET_COUNT)
            currentGuid = guid
        }
        val idx = (positionMs * EpisodeHeatEntity.BUCKET_COUNT / durationMs)
            .toInt()
            .coerceIn(0, EpisodeHeatEntity.BUCKET_COUNT - 1)
        buckets[idx] = min(buckets[idx] + 1, MAX_COUNT)
        db.episodeHeatDao().upsert(
            EpisodeHeatEntity(
                guid = guid,
                bucketsCsv = encode(buckets),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        /** Saturation cap: keeps rows bounded; 9999 ticks ≈ 27 hours of
         *  replaying one bucket — far beyond meaningful signal. */
        private const val MAX_COUNT = 9_999

        /** Tolerant decode: wrong length or junk → zeroed array. */
        fun decode(csv: String): IntArray {
            val parts = csv.split(',')
            if (parts.size != EpisodeHeatEntity.BUCKET_COUNT) {
                return IntArray(EpisodeHeatEntity.BUCKET_COUNT)
            }
            return IntArray(EpisodeHeatEntity.BUCKET_COUNT) { i ->
                parts[i].toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
        }

        fun encode(buckets: IntArray): String = buckets.joinToString(",")
    }
}
