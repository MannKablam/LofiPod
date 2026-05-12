package com.lofipod.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Look-ahead brick-wall limiter for the final output stage of the audio chain.
 * Replaces the tanh soft-clipper that lived in [EqAudioProcessor] before
 * Phase A5 of the audiophile DSP rebuild.
 *
 * **Why a real limiter, not tanh.** tanh is a memoryless waveshaper: every
 * sample where |x| > knee gets distorted by a fixed nonlinearity, generating
 * odd-order harmonics. Below the knee it's transparent; above, it's audibly
 * gritty. For an audiophile chain, distortion-by-design is a non-starter. A
 * look-ahead limiter holds peaks below threshold WITHOUT changing the harmonic
 * spectrum — it just turns the gain down briefly when the signal would clip,
 * then smoothly back up. The result is a clean signal that never exceeds the
 * threshold, with no added harmonics.
 *
 * **Architecture.**
 *   - [lookAheadSamples]-sample circular delay line, per channel
 *   - linked-stereo peak detector: max(|L|, |R|) per frame
 *   - sliding-window peak max via a monotonic deque (O(1) amortized push/pop);
 *     bit-exact same windowed-max as a brute-force scan, but without the
 *     ~440-compare-per-frame busywork that the prior brute-force version
 *     was costing the audio thread on bass-boosted content
 *   - target gain = clamp via threshold + soft knee in dB domain
 *   - envelope follower with 1 ms attack, 50 ms release smooths gain control
 *   - apply smoothed gain to the delayed signal
 *
 * **Linked stereo.** Gain reduction is computed from max(|L|, |R|) across the
 * window and applied IDENTICALLY to both channels. Per-channel GR would shift
 * the stereo image — a transient on one channel only would briefly push that
 * channel "back" in the field, audible as a pumping image. Linked GR
 * preserves spatial stability.
 *
 * **EOS handling.** The LA delay line buffers [lookAheadSamples] frames before
 * any non-priming output emerges. After EOS, [lookAheadSamples] frames of real
 * audio are still stuck in the buffer and must be drained, padding the
 * detector input with zeros. [EqAudioProcessor.onQueueEndOfStream] calls
 * [drainFrame] [lookAheadSamples] times to flush these. Without this, the last
 * 5 ms of every track would be silently eaten.
 *
 * **Threshold + soft knee.** -1 dBFS threshold (≈0.891 linear) leaves 1 dB of
 * headroom for downstream truncation noise. 3 dB knee width: peaks below
 * (threshold - 1.5 dB) get no reduction; peaks above (threshold + 1.5 dB) are
 * hard-limited; peaks in between get progressively reduced via a quadratic
 * curve. The knee softens the onset of compression so the transition into
 * limiting is inaudible — hard-knee limiters are characterized by an audible
 * "click" at the threshold crossing.
 *
 * **Latency.** [lookAheadSamples] samples (~5 ms at 44.1k). The first
 * [lookAheadSamples] output frames after stream start are zero — this is the
 * delay line priming itself. Inaudible at podcast playback contexts.
 *
 * **Threading.** Single-instance, audio-thread only. [lastReductionDb] is
 * @Volatile so the UI thread can read it for a future GR meter.
 */
class Limiter {
    private var sampleRate = 0
    private var channelCount = 0
    private var lookAheadSamples = 0

    // Per-channel circular delay line. Holds the last [lookAheadSamples] input
    // frames; reading from delayWritePos before writing gives a sample written
    // [lookAheadSamples] frames ago (or zero, during priming).
    private var delayLine: Array<DoubleArray> = emptyArray()
    private var delayWritePos = 0

    // ---- Sliding-window-max (linked-stereo peak) via monotonic deque ----
    //
    // Each entry holds (sampleIndex, peakValue) for one frame's
    // max(|x_ch|). The deque stays monotonically *non-increasing* from
    // head -> tail in [dequeValues]: any entry whose value is smaller
    // than a newer arrival is popped from the back, because as long as
    // the newer (and larger) entry is in the window, the smaller one can
    // never be the windowed max again. The head is therefore always the
    // current windowed max, and we expire it from the front when its
    // sample index falls outside [currentIndex - lookAheadSamples + 1,
    // currentIndex]. O(1) amortized peak detection — replaces the
    // earlier brute-force scan over the whole window.
    private var dequeValues: DoubleArray = DoubleArray(0)
    private var dequeIndices: LongArray = LongArray(0)
    /** Index of the front (= current windowed max). */
    private var dequeHead = 0
    /** One past the back (= insertion point). */
    private var dequeTail = 0
    /** Number of entries currently in the deque. */
    private var dequeSize = 0
    /** Monotonic frame counter, advanced once per processFrame/drainFrame call. */
    private var sampleCounter = 0L

    // Envelope follower state. Linear gain in (0, 1]: 1 = no reduction.
    // currentGain is what we multiply the delayed signal by; it's smoothed
    // toward [computeTargetGain]'s output via attack/release one-pole.
    private var currentGain = 1.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0

    /**
     * Most recent gain reduction in dB. 0 dB = no reduction, negative =
     * attenuated. Read by [EqAudioProcessor] to gate the TPDF dither stage:
     * dither only makes sense when the limiter has actually modified the
     * signal away from bit-identical (otherwise we'd be adding ~1 LSB of
     * noise to a passthrough signal, a noise-floor regression).
     */
    @Volatile
    var lastReductionDb: Double = 0.0
        private set

    /**
     * Number of frames the EqAudioProcessor must drain at end-of-stream to
     * flush the LA delay line. Equals [lookAheadSamples] once configured.
     */
    val drainFrameCount: Int
        get() = lookAheadSamples

    /** Brick-wall threshold in dBFS, exposed for diagnostics. */
    val thresholdDbfs: Double
        get() = THRESHOLD_DB

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        // 5 ms LA window. Long enough to ramp gain down across snare/plosive
        // transients with the 1 ms envelope attack (5τ ≈ 99.3% to target);
        // short enough that the latency is sub-perceptual (5 ms < neural
        // sound-localization JND for casual listening).
        lookAheadSamples = (sampleRate * LOOK_AHEAD_SECONDS).toInt().coerceAtLeast(1)
        delayLine = Array(channelCount) { DoubleArray(lookAheadSamples) }
        // Deque holds at most [lookAheadSamples] entries — one per frame in the
        // window. Pre-allocated; no allocation in the hot loop.
        dequeValues = DoubleArray(lookAheadSamples)
        dequeIndices = LongArray(lookAheadSamples)
        // One-pole filter coefficients: tau = 1/(sr * timeConstantSec).
        attackCoef = 1.0 - exp(-1.0 / (sampleRate * ATTACK_SECONDS))
        releaseCoef = 1.0 - exp(-1.0 / (sampleRate * RELEASE_SECONDS))
        reset()
    }

    fun reset() {
        for (ch in delayLine) ch.fill(0.0)
        delayWritePos = 0
        currentGain = 1.0
        lastReductionDb = 0.0
        // Clear the deque. The arrays themselves don't need to be zeroed —
        // dequeSize == 0 means none of their contents are reachable.
        dequeHead = 0
        dequeTail = 0
        dequeSize = 0
        sampleCounter = 0L
    }

    /**
     * Process one input frame: push it into the delay line + detector, read
     * the [lookAheadSamples]-frame-old delayed sample, apply current envelope
     * gain, write to [output]. Both [input] and [output] must have length
     * == channelCount.
     *
     * The first [lookAheadSamples] output frames of every fresh stream/flush
     * are zero (the delay line reads from initially-zeroed slots before any
     * real audio has aged into them). Caller may emit them as silence; they
     * cost ~5 ms of leading silence, inaudible.
     */
    fun processFrame(input: DoubleArray, output: DoubleArray) {
        // Linked-stereo peak: max absolute across all channels in this frame.
        // This is what the detector sees for windowing purposes — applying
        // the same gain to all channels preserves the stereo image.
        var framePeak = 0.0
        for (ch in 0 until channelCount) {
            val a = abs(input[ch])
            if (a > framePeak) framePeak = a
        }

        // Advance the windowed-max deque. Bit-exact same result as the prior
        // O(N) scan; just amortizes peak retrieval to O(1) so the audio thread
        // doesn't spend ~440 compares per frame on a 5 ms LA at 88.2k.
        val windowedMax = pushPeakAndQueryMax(framePeak)

        val targetGain = computeTargetGain(windowedMax)

        // One-pole envelope follower. Attack when gain is dropping (peak
        // arriving), release when rising (peak leaving). Asymmetric so the
        // limiter clamps fast but recovers slowly — slow recovery prevents
        // pumping artifacts on sustained loud passages where peaks come and
        // go within the release window.
        val coef = if (targetGain < currentGain) attackCoef else releaseCoef
        currentGain += coef * (targetGain - currentGain)

        // Update GR meter / dither gate. Snap to 0 dB just above 1.0 so the
        // dither gate doesn't flicker on negligible reductions.
        lastReductionDb = if (currentGain >= UNITY_GAIN_EPSILON) 0.0
                          else 20.0 * log10(currentGain.coerceAtLeast(LOG_FLOOR))

        // Read delayed sample BEFORE writing new input — the slot at
        // delayWritePos currently holds the input from [lookAheadSamples]
        // frames ago (or zero, during priming).
        for (ch in 0 until channelCount) {
            output[ch] = delayLine[ch][delayWritePos] * currentGain
        }
        // Write new input over the slot we just read.
        for (ch in 0 until channelCount) {
            delayLine[ch][delayWritePos] = input[ch]
        }
        delayWritePos = (delayWritePos + 1) % lookAheadSamples
    }

    /**
     * Drain one frame at end-of-stream. Equivalent to [processFrame] with a
     * zero input — flushes the delay line so the last [lookAheadSamples]
     * frames of real audio actually emerge as output. Caller invokes this
     * [drainFrameCount] times after [EqAudioProcessor.onQueueEndOfStream].
     */
    fun drainFrame(output: DoubleArray) {
        // Zero-input fall-through: peak detector sees no new energy. The deque
        // still ages out old peaks via expireFront, so the windowed max
        // smoothly walks down to zero as the LA buffer empties.
        val windowedMax = pushPeakAndQueryMax(0.0)

        val targetGain = computeTargetGain(windowedMax)
        val coef = if (targetGain < currentGain) attackCoef else releaseCoef
        currentGain += coef * (targetGain - currentGain)
        lastReductionDb = if (currentGain >= UNITY_GAIN_EPSILON) 0.0
                          else 20.0 * log10(currentGain.coerceAtLeast(LOG_FLOOR))

        for (ch in 0 until channelCount) {
            output[ch] = delayLine[ch][delayWritePos] * currentGain
        }
        // Write zero so subsequent drains see clean state if drained more
        // times than expected. Defensive; normal flow drains exactly
        // [drainFrameCount] frames.
        for (ch in 0 until channelCount) {
            delayLine[ch][delayWritePos] = 0.0
        }
        delayWritePos = (delayWritePos + 1) % lookAheadSamples
    }

    /**
     * Advance the sliding-window-max deque by one frame: expire any front
     * entries whose sample index has fallen out of the [lookAheadSamples]-frame
     * window, pop back entries whose value is `<= peak` (they can't be max
     * while the new arrival is in the window), then push the new (index,
     * peak) pair. Returns the windowed max after the update — i.e. the
     * largest absolute peak across the [lookAheadSamples] most recent frames.
     *
     * O(1) amortized: every entry is pushed once and popped once. No
     * allocation; arrays are pre-sized to [lookAheadSamples] in [configure],
     * which is the worst-case deque size (the window itself).
     */
    private fun pushPeakAndQueryMax(peak: Double): Double {
        val current = sampleCounter
        sampleCounter++

        val n = lookAheadSamples
        val cutoff = current - n + 1  // oldest index still inside the window

        // Expire front entries that have aged out of the window.
        while (dequeSize > 0 && dequeIndices[dequeHead] < cutoff) {
            dequeHead = (dequeHead + 1) % n
            dequeSize--
        }

        // Pop back entries dominated by the new value.
        while (dequeSize > 0) {
            val backIdx = (dequeTail - 1 + n) % n
            if (dequeValues[backIdx] <= peak) {
                dequeTail = backIdx
                dequeSize--
            } else break
        }

        // Push (current, peak) at the back.
        dequeValues[dequeTail] = peak
        dequeIndices[dequeTail] = current
        dequeTail = (dequeTail + 1) % n
        dequeSize++

        // Front is the windowed max (deque is non-increasing head → tail).
        return dequeValues[dequeHead]
    }

    /**
     * Linear gain that, when applied, brings [peak] to or below the threshold,
     * with a soft quadratic knee around the threshold.
     *
     * dB-domain math:
     *   peak_dB ≤ knee_lower → gain_dB = 0   (no reduction)
     *   peak_dB ≥ knee_upper → gain_dB = T - peak_dB   (hard limit, slope -1)
     *   in between           → quadratic blend: gain_dB = -(peak_dB - knee_lower)² / (2 * W)
     *
     * The quadratic gives gain_dB = 0 at the lower knee (smooth onset) and
     * matches slope -1 at the upper knee (smooth handoff to hard-limit
     * region). At peak == 0 we short-circuit to avoid log(0).
     */
    private fun computeTargetGain(peak: Double): Double {
        if (peak <= 0.0) return 1.0
        val peakDb = 20.0 * log10(peak)
        val gainDb = when {
            peakDb <= KNEE_LOWER_DB -> 0.0
            peakDb >= KNEE_UPPER_DB -> THRESHOLD_DB - peakDb
            else -> {
                val excess = peakDb - KNEE_LOWER_DB
                -(excess * excess) / (2.0 * KNEE_WIDTH_DB)
            }
        }
        return 10.0.pow(gainDb / 20.0)
    }

    companion object {
        private const val LOOK_AHEAD_SECONDS = 0.005   // 5 ms
        private const val ATTACK_SECONDS = 0.001       // 1 ms (5τ ≈ LA)
        private const val RELEASE_SECONDS = 0.050      // 50 ms (musical recovery)
        private const val THRESHOLD_DB = -1.0          // -1 dBFS
        private const val KNEE_WIDTH_DB = 3.0          // 3 dB total knee width
        private const val KNEE_LOWER_DB = THRESHOLD_DB - KNEE_WIDTH_DB / 2.0  // -2.5 dB
        private const val KNEE_UPPER_DB = THRESHOLD_DB + KNEE_WIDTH_DB / 2.0  // +0.5 dB
        private const val UNITY_GAIN_EPSILON = 0.999999
        private const val LOG_FLOOR = 1e-6  // -120 dB; clamps log10 at silence
    }
}
