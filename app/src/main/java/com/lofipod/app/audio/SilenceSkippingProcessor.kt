package com.lofipod.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Silence-skipping audio processor with runtime-tunable aggressiveness.
 *
 * Why custom (not Media3's `SilenceSkippingAudioProcessor`): Media3's processor
 * takes its parameters at construction time and offers no setter, so changing
 * aggressiveness at runtime would mean rebuilding the audio sink — and the
 * EQ/preset UX language calls for staged levels (off / L1 / L2 / L3) the user
 * can flip between like an EQ preset.
 *
 * Algorithm:
 *   - Scan 16-bit PCM frames; per frame, compute peak abs amplitude across
 *     channels.
 *   - When peak stays below the level's threshold for at least
 *     `paddingFrames + minSilenceFrames` consecutive frames, start dropping
 *     subsequent silent frames. This means the FIRST `paddingFrames` of every
 *     pause always plays through (so transitions don't sound abrupt) and
 *     pauses shorter than `minSilenceFrames` aren't compressed at all.
 *   - On the first loud frame, reset state and emit normally.
 *
 * Runs after the EQ in the audio chain — silence-detect against the
 * EQ-treated signal so a heavy bass cut doesn't accidentally hide low-end
 * room rumble that we'd otherwise classify as silence.
 */
class SilenceSkippingProcessor : BaseAudioProcessor() {

    /** 0 = off (passthrough), 1..3 = stages from gentle to aggressive. */
    @Volatile private var level: Int = 0

    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0

    private data class Params(
        val minSilenceFrames: Int,
        val paddingFrames: Int,
        val threshold: Int,
    )

    private var params = Params(0, 0, 0)
    private var silentFrameCount = 0

    fun setLevel(l: Int) {
        val clamped = l.coerceIn(0, 3)
        if (clamped != level) {
            level = clamped
            // Recompute params if we know the sample rate. Reset run-counter
            // so an in-flight silence run doesn't carry stale framing.
            if (sampleRate > 0) {
                params = paramsFor(sampleRate, clamped)
                silentFrameCount = 0
            }
        }
    }

    fun currentLevel(): Int = level

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerFrame = 2 * channelCount
        params = paramsFor(sampleRate, level)
        return inputAudioFormat
    }

    override fun onFlush() {
        silentFrameCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val l = level
        // Off → straight passthrough. Cheap fast-path so users who never turn
        // it on pay zero per-sample cost.
        if (l == 0 || channelCount == 0) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            silentFrameCount = 0
            return
        }

        val totalFrames = inputBuffer.remaining() / bytesPerFrame
        if (totalFrames == 0) return

        val src = inputBuffer.order(ByteOrder.nativeOrder())
        // Worst case = all frames pass through; allocate that.
        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())

        val emitWhile = params.paddingFrames + params.minSilenceFrames
        val threshold = params.threshold

        for (frame in 0 until totalFrames) {
            val frameStart = src.position()
            // Read all channels to find peak abs amplitude in this frame.
            // Doing this advances src by bytesPerFrame.
            var peak = 0
            for (ch in 0 until channelCount) {
                val s = abs(src.short.toInt())
                if (s > peak) peak = s
            }

            val isQuiet = peak < threshold

            if (isQuiet) {
                silentFrameCount++
                if (silentFrameCount <= emitWhile) {
                    // Still in the head-padding + min-silence window — emit
                    // unchanged. Pauses shorter than min-silence pass through
                    // entirely; longer ones get truncated only after this
                    // window elapses.
                    src.position(frameStart)
                    for (ch in 0 until channelCount) out.putShort(src.short)
                }
                // else: drop the frame (don't write to out; src already
                // advanced past it during the peak-read loop).
            } else {
                // Loud frame ends the silent run. Emit normally.
                silentFrameCount = 0
                src.position(frameStart)
                for (ch in 0 until channelCount) out.putShort(src.short)
            }
        }

        out.flip()
    }

    companion object {
        /**
         * Per-level params, tuned for podcast voice content.
         * - L1 (gentle):     only catches dead-air pauses; conversational gaps
         *                    pass through.
         * - L2 (standard):   typical pauses get tightened; transitions stay
         *                    natural.
         * - L3 (aggressive): tight conversational gaps, higher amplitude
         *                    threshold so even quiet breaths can count as
         *                    silence.
         *
         * Threshold is in 16-bit signed PCM units — 1024 ≈ 3% of full scale,
         * 2048 ≈ 6%.
         */
        private fun paramsFor(sampleRate: Int, level: Int): Params {
            val (msMin, msPad, thr) = when (level) {
                1 -> Triple(800, 200, 1024)
                2 -> Triple(400, 100, 1024)
                3 -> Triple(200, 50, 2048)
                else -> Triple(0, 0, 0)
            }
            return Params(
                minSilenceFrames = sampleRate * msMin / 1000,
                paddingFrames = sampleRate * msPad / 1000,
                threshold = thr,
            )
        }
    }
}
