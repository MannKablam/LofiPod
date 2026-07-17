package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached read-ahead scan for one episode: full-file audible-pause spans
 * plus a downsampled waveform envelope, produced offline by
 * [com.lofipod.app.audio.EpisodeAudioAnalyzer] and served to the player
 * UI through [com.lofipod.app.data.EpisodeAnalysisRepository]. Rows are
 * written only for scans that ran to completion — a half-decoded file
 * never leaves a partial row behind.
 *
 * The envelope departs from the CSV house style (cf. [EpisodeHeatEntity])
 * on purpose: at 2000 buckets a CSV would triple the row size for a
 * payload nobody inspects in a sqlite shell. One unsigned byte per bucket
 * (0..255 quantization of max-|amplitude|, normalized to the file's
 * global peak) keeps every row a flat 2 KB. Pause spans stay CSV — they
 * are few, and `start:end` pairs read fine in a shell.
 *
 * Derived analytics — excluded from Backup.kt's curated export set,
 * like episode_heat: losing rows just means episodes rescan lazily.
 */
@Entity(tableName = "episode_analysis")
data class EpisodeAnalysisEntity(
    @PrimaryKey val guid: String,
    /** Duration the decode measured (last decoded frame), ms. Envelope
     *  buckets divide this evenly; trust it over the feed's declared
     *  duration, which VBR headers routinely get wrong. */
    val durationMs: Long,
    /** [com.lofipod.app.audio.PauseTapProcessor] sensitivity (1..5) the
     *  pause spans were computed at. A row scanned at a different
     *  sensitivity than the user's current setting is stale; the
     *  repository rescans rather than serving disagreeing marks. */
    val sensitivity: Int,
    /** CSV of `startMs:endMs` pairs, chronological. Empty string when
     *  the scan found no qualifying pauses. */
    val pausesCsv: String,
    /** Exactly EpisodeAudioAnalyzer.ENVELOPE_BUCKETS bytes; each an
     *  unsigned 0..255 amplitude sample as described above. */
    val envelope: ByteArray,
    val updatedAt: Long,
) {
    // A ByteArray field makes the generated data-class equals compare the
    // blob by reference — value semantics in appearance, identity in
    // behavior. Spell out content equality so Flow-level deduplication
    // (or any future comparison) does what it looks like it does.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodeAnalysisEntity) return false
        return guid == other.guid &&
            durationMs == other.durationMs &&
            sensitivity == other.sensitivity &&
            pausesCsv == other.pausesCsv &&
            envelope.contentEquals(other.envelope) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = guid.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + sensitivity
        result = 31 * result + pausesCsv.hashCode()
        result = 31 * result + envelope.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
