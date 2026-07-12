package com.lofipod.app.audio

import kotlin.math.exp
import kotlin.math.ln
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

    /**
     * Activity meter: decayed peak of the excitement term actually added to
     * the output (linear amplitude, linked across channels) — 0.0 when idle,
     * rising with top-octave program energy. Plain field written per sample
     * in the 2x hot loop (a volatile write there would be a needless fence);
     * EqAudioProcessor reads it on the same audio thread once per buffer and
     * mirrors it into [AudioChainTelemetry.airActivity] for the UI.
     */
    private var activityEnv = 0.0
    private var activityDecay = 1.0

    /** Audio-thread read of the activity envelope (see [activityEnv]). */
    fun currentActivityLin(): Double = activityEnv

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
        // ~0.4 s half-life on the activity envelope so brief hits stay
        // visible at the UI's 250 ms poll without smearing into a constant.
        activityDecay = if (sampleRate2x > 0) {
            exp(-ln(2.0) / (sampleRate2x * ACTIVITY_HALFLIFE_SEC))
        } else 1.0
        appliedLevel = -1
        reset()
    }

    fun reset() {
        for (b in splitLp) b.reset()
        activityEnv = 0.0
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
        var addedPeak = 0.0
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            val high = x - splitLp[ch].process(x)
            // tanh(k*h)/k ≈ h for small h (clean lift), saturates for large
            // h (harmonics). INV_K normalizes the small-signal gain to 1.
            val added = mix * tanh(DRIVE_K * high) * INV_K
            frame[ch] = x + added
            val a = if (added >= 0) added else -added
            if (a > addedPeak) addedPeak = a
        }
        // Activity: peak-follow the added excitement term, linked across
        // channels — ONE decay step per 2x frame (a per-channel step would
        // compound, halving the half-life on stereo).
        val act = activityEnv
        activityEnv = if (addedPeak > act) addedPeak else act * activityDecay
    }

    companion object {
        /** Air band lower edge. */
        private const val SPLIT_HZ = 7_200f
        private const val DRIVE_K = 2.2
        private const val INV_K = 1.0 / DRIVE_K
        private const val ACTIVITY_HALFLIFE_SEC = 0.4
    }
}
