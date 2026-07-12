package com.lofipod.app.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * Warmth — tube-style soft saturation. A gently-driven asymmetric
 * waveshaper (`tanh(drive * (x + even*x^2)) / drive`) blends low-order
 * harmonics into the signal: the even term adds the 2nd-harmonic "tube"
 * body, the tanh curve contributes the odd-order "tape" rounding, and
 * the dry/wet mix keeps it a seasoning rather than a fuzz.
 *
 * Normalization is SLOPE-based (divide by drive): `tanh(d*x)/d ≈ x` for
 * small x, so nominal program level — speech sits 10-20 dB below peak,
 * squarely in the small-signal region — passes at unity and only peaks
 * get progressively rounded. Peak normalization (`/tanh(d)`) was the
 * original design and was wrong: its small-signal gain is `d/tanh(d)`,
 * a +2 to +11 dB loudness boost by level that parked the downstream
 * limiter in constant gain reduction. Loudness-neutral body, softened
 * peaks is the tube behavior the stage is named for.
 *
 * Aliasing: waveshaping spreads the spectrum, so this stage runs INSIDE
 * the chain's existing 2x oversampling envelope (between the upsampler and
 * the limiter — see EqAudioProcessor). Harmonics land below the 2x Nyquist
 * and everything above the original band is removed by the downsampler's
 * anti-alias FIR. Zero added latency, no extra oversampler needed.
 *
 * The x^2 term rectifies, which introduces a DC offset that varies with
 * program level; a per-channel [DcBlocker] (8 Hz, configured at the 2x
 * rate) sits on the wet path so that offset never reaches the mix.
 *
 * Configure with the 2x sample rate. All state audio-thread-owned;
 * [setLevel] is UI-safe (volatile + audio-thread-applied reset).
 */
class Saturator {

    /** 0 = off, 1..3 = subtle / warm / hot. */
    @Volatile private var level: Int = 0
    @Volatile private var pendingReset: Boolean = false

    private var channelCount = 0
    private var dcTraps: Array<DcBlocker> = emptyArray()

    /**
     * Activity meter: decayed peak of |wet - dry| actually mixed into the
     * output (linear amplitude, linked across channels). This is the
     * magnitude of the change the stage is making to the signal — 0.0 when
     * idle or bypassed, rising as program peaks push into the tanh curve.
     *
     * Plain field, NOT volatile: it's written per sample inside the 2x hot
     * loop, where a volatile write per frame would be a needless fence.
     * EqAudioProcessor reads it on the same audio thread once per buffer
     * and mirrors it into [AudioChainTelemetry.warmthActivity] for the UI.
     */
    private var activityEnv = 0.0
    private var activityDecay = 1.0

    /** Audio-thread read of the activity envelope (see [activityEnv]). */
    fun currentActivityLin(): Double = activityEnv

    private var appliedLevel = -1
    private var drive = 1.0
    private var invDrive = 1.0
    private var even = 0.0
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
        dcTraps = Array(channelCount) { DcBlocker().apply { configure(sampleRate2x, 8.0f) } }
        // ~0.4 s half-life on the activity envelope so brief hits stay
        // visible at the UI's 250 ms poll without smearing into a constant.
        activityDecay = if (sampleRate2x > 0) {
            exp(-ln(2.0) / (sampleRate2x * ACTIVITY_HALFLIFE_SEC))
        } else 1.0
        appliedLevel = -1
        reset()
    }

    fun reset() {
        for (t in dcTraps) t.reset()
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
            val (d, b, m) = when (l) {
                1 -> Triple(1.6, 0.05, 0.40)
                2 -> Triple(2.6, 0.09, 0.65)
                else -> Triple(4.0, 0.14, 0.90)
            }
            drive = d
            invDrive = 1.0 / d
            even = b
            mix = m
            appliedLevel = l
        }
        var deltaPeak = 0.0
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            var wet = tanh(drive * (x + even * x * x)) * invDrive
            wet = dcTraps[ch].process(wet)
            val delta = (wet - x) * mix
            frame[ch] = x + delta
            val a = if (delta >= 0) delta else -delta
            if (a > deltaPeak) deltaPeak = a
        }
        // Activity: peak-follow the applied wet-dry delta, linked across
        // channels — ONE decay step per 2x frame (a per-channel step would
        // compound, halving the half-life on stereo).
        val act = activityEnv
        activityEnv = if (deltaPeak > act) deltaPeak else act * activityDecay
    }

    companion object {
        private const val ACTIVITY_HALFLIFE_SEC = 0.4
    }
}
