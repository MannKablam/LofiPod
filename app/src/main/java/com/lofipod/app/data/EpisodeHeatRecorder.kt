package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeHeatEntity
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Write-side aggregator for the replay heatmap. PlaybackService's 10s
 * save ticker calls [tick] with the current playhead; every bucket the
 * playhead CROSSED since the previous tick gets +1 and the row is
 * upserted. Accrual is RANGE-based, not a point sample at the tick
 * position, and half-open in bucket space (the bucket the playhead now
 * sits in belongs to the next interval) — so one front-to-back pass
 * leaves every crossed bucket at exactly 1 whatever the playback speed
 * or episode length, a re-listened stretch sits at 2, a third pass at 3.
 * That invariant is what the render side's statistics assume (see
 * StructuredScrubber's buildHeatStops).
 *
 * The point sampling this replaced (one +1 per tick into the single
 * ~0.5%-wide bucket under the playhead) beat against the bucket grid:
 * a 30s re-listen deposited 3 ticks that usually landed BETWEEN the
 * first pass's ticks, so no bucket ever reached the renderer's ≥2
 * replay threshold and the map stayed blank.
 *
 * Interval integrity: an interval only accrues when it plausibly IS
 * continuous listening — positive span no wider than [tick]'s
 * maxSpanMs (the caller's tick interval × playback speed plus
 * headroom). Seeks are handled by [rebase]: the player posts every
 * position discontinuity so the next interval starts at the landing
 * position; without it the post-seek tick would either bridge the
 * jumped-over region into a phantom listen (small forward skip) or
 * discard a real one.
 *
 * The in-memory bucket array is cached per guid so steady-state is one
 * small row write per tick, no read.
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

    /** Playhead at the previous tick; -1 = no interval armed yet. */
    private var lastPosMs = -1L

    /** Rebase posted for an episode that isn't [currentGuid] yet (item
     *  transitions race the next tick); adopted when its guid arrives. */
    private var pendingRebaseGuid: String? = null
    private var pendingRebasePosMs = -1L

    /**
     * Call from an IO context. Accrues +1 to the buckets covered by
     * `[previous tick position, positionMs)` when that interval is a
     * plausible continuous listen — positive and no wider than
     * [maxSpanMs] — then re-arms at [positionMs]. Skips accrual (but
     * still re-arms) otherwise. Skips entirely when duration is unknown.
     */
    suspend fun tick(
        guid: String,
        positionMs: Long,
        durationMs: Long,
        maxSpanMs: Long,
    ) = mutex.withLock {
        if (durationMs <= 0) return@withLock
        if (guid != currentGuid) {
            buckets = db.episodeHeatDao().get(guid)
                ?.let { decode(it.bucketsCsv) }
                ?: IntArray(EpisodeHeatEntity.BUCKET_COUNT)
            currentGuid = guid
            lastPosMs = if (guid == pendingRebaseGuid) pendingRebasePosMs else -1L
            pendingRebaseGuid = null
        }
        val prev = lastPosMs
        lastPosMs = positionMs
        if (prev < 0 || positionMs <= prev || positionMs - prev > maxSpanMs) return@withLock
        // Half-open [bucketOf(prev), bucketOf(pos)). An interval that
        // starts and ends inside one bucket accrues nothing — the tick
        // that eventually crosses the boundary accrues it — so long
        // episodes (bucket wider than the tick interval) still land
        // exactly one count per bucket per pass.
        val from = bucketIndex(prev, durationMs)
        val until = bucketIndex(positionMs, durationMs)
        if (from >= until) return@withLock
        for (b in from until until) buckets[b] = min(buckets[b] + 1, MAX_COUNT)
        db.episodeHeatDao().upsert(
            EpisodeHeatEntity(
                guid = guid,
                bucketsCsv = encode(buckets),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Position discontinuity (seek, skip, item transition, restore):
     * the interval in flight is dead; the next one starts at
     * [positionMs] of [guid]. Cheap — no DB touch. When [guid] isn't
     * the cached episode yet the rebase is parked and adopted by the
     * first tick that switches to it.
     */
    suspend fun rebase(guid: String, positionMs: Long) = mutex.withLock {
        if (guid == currentGuid) {
            lastPosMs = positionMs
        } else {
            pendingRebaseGuid = guid
            pendingRebasePosMs = positionMs
        }
    }

    private fun bucketIndex(positionMs: Long, durationMs: Long): Int =
        (positionMs * EpisodeHeatEntity.BUCKET_COUNT / durationMs)
            .toInt()
            .coerceIn(0, EpisodeHeatEntity.BUCKET_COUNT - 1)

    companion object {
        /** Saturation cap: keeps rows bounded; 9999 passes over one
         *  bucket is far beyond meaningful signal. */
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
