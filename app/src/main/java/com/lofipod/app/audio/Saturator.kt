package com.lofipod.app.audio

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
        appliedLevel = -1
        reset()
    }

    fun reset() {
        for (t in dcTraps) t.reset()
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
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            var wet = tanh(drive * (x + even * x * x)) * invDrive
            wet = dcTraps[ch].process(wet)
            frame[ch] = x + (wet - x) * mix
        }
    }
}
