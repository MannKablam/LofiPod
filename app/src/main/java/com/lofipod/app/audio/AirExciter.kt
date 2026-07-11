package com.lofipod.app.audio

import kotlin.math.tanh

/**
 * Air — a top-octave exciter for that expensive "studio sheen." Splits
 * off everything above [SPLIT_HZ] via a complementary low-pass
 * (`high = x - LP(x)`), passes the high band through a soft tanh shaper
 * (normalized so the small-signal gain is unity), and mixes the result
 * back in. Small signals get a clean ~1-3 dB shelf-like lift; louder
 * highs additionally sprout low-order harmonics — the classic exciter
 * trick that reads as "detail" rather than plain treble boost.
 *
 * Runs INSIDE the chain's 2x oversampling envelope (after Warmth, before
 * the limiter): harmonics of 8-16 kHz content land above the source
 * Nyquist but below the 2x Nyquist, and the downsampler's anti-alias FIR
 * removes them cleanly instead of letting them fold back as grit. The
 * split filter is therefore configured at the 2x rate. Zero added
 * latency.
 *
 * Ordering note: sits downstream of the de-esser, so tamed sibilance is
 * not re-excited at full strength.
 */
class AirExciter {

    /** 0 = off, 1..3 = breath / open / brilliant. */
    @Volatile private var level: Int = 0
    @Volatile private var pendingReset: Boolean = false

    private var channelCount = 0
    private var splitLp: Array<Biquad> = emptyArray()

    private var appliedLevel = -1
    private var mix = 0.0

    fun setLevel(l: Int) {
        val clamped = l.coerceIn(0, 3)
        if (clamped == level) return
        if (level == 0 && clamped > 0) pendingReset = true
        level = clamped
    }

    fun currentLevel(): Int = level

    /** [sampleRate2x] is the OVERSAMPLED rate (2 x source rate). */
    fun configure(sampleRate2x: Int, channelCount: Int) {
        this.channelCount = channelCount
        splitLp = Array(channelCount) { Biquad().apply { setLowpass(sampleRate2x, SPLIT_HZ) } }
        appliedLevel = -1
        reset()
    }

    fun reset() {
        for (b in splitLp) b.reset()
    }

    /** In-place, one 2x-rate frame. Call only when [currentLevel] > 0. */
    fun processFrame(frame: DoubleArray) {
        if (pendingReset) {
            pendingReset = false
            reset()
        }
        val l = level
        if (l != appliedLevel) {
            mix = when (l) {
                1 -> 0.14
                2 -> 0.25
                else -> 0.38
            }
            appliedLevel = l
        }
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            val high = x - splitLp[ch].process(x)
            // tanh(k*h)/k ≈ h for small h (clean lift), saturates for large
            // h (harmonics). INV_K normalizes the small-signal gain to 1.
            frame[ch] = x + mix * tanh(DRIVE_K * high) * INV_K
        }
    }

    companion object {
        /** Air band lower edge. */
        private const val SPLIT_HZ = 7_200f
        private const val DRIVE_K = 2.2
        private const val INV_K = 1.0 / DRIVE_K
    }
}
