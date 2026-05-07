package com.lofipod.app.audio

import kotlin.math.PI
import kotlin.math.abs

/**
 * Single-pole high-pass filter that removes any DC offset (0 Hz energy) from
 * the input. Standard "DC blocker" implementation:
 *
 *   y[n] = x[n] - x[n-1] + R * y[n-1]
 *
 * R sits just inside the unit circle and controls the corner frequency. We
 * compute R from a target cutoff:
 *
 *   R = 1 - (2 * PI * fc / fs)
 *
 * For fc = 5 Hz at 44.1 kHz, R ≈ 0.99929 — a near-flat high-pass that
 * removes only the lowest few Hz of energy. Audible content (down to ~20 Hz)
 * passes through with imperceptible attenuation.
 *
 * **Why optional and off by default.** Most professionally-mastered podcasts
 * have no DC offset; running this filter on them is a no-op that only adds
 * a tiny amount of phase shift below 20 Hz. But low-bitrate MP3s and badly-
 * encoded sermons sometimes have measurable DC, which then gets amplified
 * by the EQ + master gain and silently steals headroom from the limiter.
 * Users who notice should be able to flip this on; users who don't should
 * pay zero CPU.
 *
 * **Per-channel state.** Each channel needs its own (x_prev, y_prev) — they
 * filter independently. Construct one instance per channel and call [reset]
 * on flush.
 *
 * **Float64.** Matches the rest of the audiophile chain. State decay
 * eventually reaches denormal range during silence; the same flush-to-zero
 * trick from [Biquad] applies here.
 */
class DcBlocker {
    private var xPrev = 0.0
    private var yPrev = 0.0
    private var r = 0.0

    /** Configure for the given sample rate. Re-call on format change. */
    fun configure(sampleRate: Int, cutoffHz: Float = DEFAULT_CUTOFF_HZ) {
        r = 1.0 - (2.0 * PI * cutoffHz / sampleRate)
    }

    fun reset() {
        xPrev = 0.0
        yPrev = 0.0
    }

    fun process(sample: Double): Double {
        val y = sample - xPrev + r * yPrev
        xPrev = sample
        yPrev = if (abs(y) < DENORMAL_CUTOFF) 0.0 else y
        return y
    }

    companion object {
        const val DEFAULT_CUTOFF_HZ = 5.0f
        private const val DENORMAL_CUTOFF = 1e-15
    }
}
