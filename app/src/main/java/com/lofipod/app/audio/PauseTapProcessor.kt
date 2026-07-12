package com.lofipod.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Passthrough tap that maps audible pauses (silence runs) to media-time
 * positions, powering "skip back to the previous audible pause."
 *
 * Sits FIRST in the audio chain (before the EQ) so it sees the source
 * signal at the source's native frame rate. Sonic (speed) runs downstream,
 * so frame counts here are media-domain regardless of playback speed, and
 * the custom silence-SKIPPING processor also runs downstream, so frames
 * it drops don't perturb our counting.
 *
 * Position mapping: `BaseAudioProcessor` sees only PCM buffers — no
 * timestamps — so media position is reconstructed as
 * `anchor + framesSinceFlush / sampleRate`. The anchor is posted from the
 * player side ([postAnchor]) on seeks, media-item transitions and READY
 * transitions, and adopted on the audio thread at the first buffer after a
 * flush: every flush corresponds to a pipeline restart whose first frame
 * is the position the player just reported. Until a fresh anchor arrives
 * post-flush the tap keeps passing audio through but records nothing —
 * the feature degrades to the plain 15s skip rather than recording wrong
 * positions. Note the tap runs at feed time, ahead of the audible
 * position by up to the sink buffer — harmless here, since skip-back only
 * ever targets pauses behind the playhead.
 *
 * Recorded pauses are absolute media positions for the CURRENT media item,
 * so they survive seeks (the list is only cleared on item transitions, via
 * [clearPauses]). Re-decoding a region after a skip-back re-records its
 * pauses; duplicates are harmless to the max-scan in
 * [previousPauseTargetBefore].
 */
class PauseTapProcessor : BaseAudioProcessor() {

    /** Sensitivity 1 (long, deep pauses only) .. 5 (short gaps too). */
    @Volatile private var sensitivity: Int = DEFAULT_SENSITIVITY

    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0

    private var minSilenceFrames = 0
    private var threshold = 0

    // --- audio-thread state ---
    private var framesSinceFlush = 0L
    private var quietRunFrames = 0L
    /** False = a flush happened and no fresh anchor has been adopted yet. */
    private var anchored = false
    private var anchorMediaMs = 0L
    private var adoptedAnchorGen = 0L
    /** Reusable per-frame scratch so the copy doesn't re-read the buffer. */
    private var frameScratch = ShortArray(0)

    // --- anchor mailbox (player thread posts, audio thread adopts) ---
    @Volatile private var postedAnchorMs = 0L
    @Volatile private var postedAnchorGen = 0L

    private class Pause(val startMs: Long, val endMs: Long)

    /** Immutable public view of a recorded pause, media-time ms. */
    data class PauseSpan(val startMs: Long, val endMs: Long)

    private val lock = Any()
    private val pauses = ArrayDeque<Pause>()

    /**
     * Snapshot of every recorded pause for the CURRENT media item —
     * unordered, possibly containing duplicates (re-decoded regions
     * re-record; harmless for rendering). Only covers regions the decoder
     * has visited this session, so scrubber ticks appear progressively.
     * Cheap copy under the lock; call at UI cadence, never per frame.
     */
    fun snapshotPauses(): List<PauseSpan> =
        synchronized(lock) { pauses.map { PauseSpan(it.startMs, it.endMs) } }

    fun setSensitivity(s: Int) {
        val clamped = s.coerceIn(1, 5)
        if (clamped != sensitivity) {
            sensitivity = clamped
            if (sampleRate > 0) recomputeParams()
        }
    }

    fun currentSensitivity(): Int = sensitivity

    /**
     * Post the authoritative media position of the next pipeline restart.
     * Call from the player's application thread on seek discontinuities,
     * media-item transitions and READY transitions. Adopted only while the
     * audio thread is awaiting an anchor (i.e. right after a flush) and
     * only if newer than the last adopted post, so stale anchors from a
     * previous restart can't be re-adopted for a later one.
     */
    fun postAnchor(mediaPositionMs: Long) {
        postedAnchorMs = mediaPositionMs.coerceAtLeast(0L)
        postedAnchorGen += 1  // single-writer (application thread)
    }

    /** Drop all recorded pauses — call on media-item transitions. */
    fun clearPauses() {
        synchronized(lock) { pauses.clear() }
    }

    /**
     * Seek target for "skip back to the previous audible pause": the tail
     * of the most recent recorded pause that ended at least [guardMs]
     * before [positionMs] (the guard makes repeated taps walk back through
     * successive pauses instead of re-finding the same one). Null when
     * nothing qualifies — caller should fall back to a plain skip.
     */
    fun previousPauseTargetBefore(positionMs: Long, guardMs: Long = 400L): Long? {
        // Snapshot under the lock, scan outside — keeps the audio thread's
        // recordPause from waiting on a main-thread scan of the ring.
        val snapshot = synchronized(lock) { pauses.toList() }
        val cutoff = positionMs - guardMs
        var best: Pause? = null
        for (p in snapshot) {
            if (p.endMs <= cutoff && (best == null || p.endMs > best.endMs)) best = p
        }
        val b = best ?: return null
        // Land inside the pause just before speech resumes, so the next
        // phrase starts cleanly without replaying the whole gap.
        return (b.endMs - PRE_ROLL_MS).coerceAtLeast(b.startMs)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerFrame = 2 * channelCount
        recomputeParams()
        return inputAudioFormat
    }

    override fun onFlush() {
        framesSinceFlush = 0L
        quietRunFrames = 0L
        anchored = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Adopt a freshly posted anchor for this post-flush stream. The
        // anchor is the media position of frame 0 since the flush.
        if (!anchored) {
            val gen = postedAnchorGen
            if (gen != adoptedAnchorGen) {
                anchorMediaMs = postedAnchorMs
                adoptedAnchorGen = gen
                anchored = true
            }
        }

        val src = inputBuffer.order(ByteOrder.nativeOrder())
        // Per-short passthrough copy — deliberately NOT a bulk put; see the
        // Media3 1.4.1 ERROR_CODE_FAILED_RUNTIME_CHECK note in
        // SilenceSkippingProcessor.queueInput about stacked bulk transfers.
        val out = replaceOutputBuffer(src.remaining()).order(ByteOrder.nativeOrder())

        if (channelCount == 0) {
            while (src.hasRemaining()) out.putShort(src.short)
            out.flip()
            return
        }
        if (frameScratch.size < channelCount) frameScratch = ShortArray(channelCount)

        val totalFrames = src.remaining() / bytesPerFrame
        for (frame in 0 until totalFrames) {
            // Single pass per frame: read each sample once into the scratch
            // (peak-scanning as we go), then write the scratch out — no
            // position() rewind, no second bounds-checked buffer read on
            // the realtime audio thread.
            var peak = 0
            for (ch in 0 until channelCount) {
                val s = src.short
                frameScratch[ch] = s
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            for (ch in 0 until channelCount) out.putShort(frameScratch[ch])

            if (peak < threshold) {
                quietRunFrames++
            } else {
                if (anchored && quietRunFrames >= minSilenceFrames) {
                    val endFrame = framesSinceFlush + frame
                    val startFrame = endFrame - quietRunFrames
                    recordPause(
                        startMs = anchorMediaMs + startFrame * 1000L / sampleRate,
                        endMs = anchorMediaMs + endFrame * 1000L / sampleRate,
                    )
                }
                quietRunFrames = 0L
            }
        }
        framesSinceFlush += totalFrames

        // Trailing partial frame (shouldn't happen with well-formed input,
        // but don't leave bytes stranded).
        while (src.hasRemaining()) out.putShort(src.short)
        out.flip()
    }

    private fun recordPause(startMs: Long, endMs: Long) {
        if (endMs <= 0L) return
        synchronized(lock) {
            pauses.addLast(Pause(startMs, endMs))
            while (pauses.size > MAX_PAUSES) pauses.removeFirst()
        }
    }

    private fun recomputeParams() {
        minSilenceFrames = sampleRate * minSilenceMsFor(sensitivity) / 1000
        threshold = thresholdFor(sensitivity)
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 3
        private const val MAX_PAUSES = 512
        private const val PRE_ROLL_MS = 200L

        /**
         * Min-silence duration per sensitivity step. Public so the
         * sensitivity dialog can label levels from the same table the
         * detector runs on — tuning here updates the UI copy for free.
         */
        fun minSilenceMsFor(sensitivity: Int): Int = when (sensitivity.coerceIn(1, 5)) {
            1 -> 1_300
            2 -> 950
            3 -> 700
            4 -> 480
            else -> 320
        }

        /**
         * Amplitude ceiling per step, in 16-bit PCM units (1024 ≈ 3% FS,
         * same scale as SilenceSkippingProcessor). Higher sensitivity =
         * shallower gaps count as pauses.
         */
        private fun thresholdFor(sensitivity: Int): Int = when (sensitivity.coerceIn(1, 5)) {
            1 -> 640
            2 -> 832
            3 -> 1_024
            4 -> 1_408
            else -> 1_792
        }
    }
}
