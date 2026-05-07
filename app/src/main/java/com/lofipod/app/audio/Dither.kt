package com.lofipod.app.audio

import java.util.Random

/**
 * Triangular-PDF (TPDF) dither generator for the Double → int16 truncation
 * stage at the end of the audio chain. Standard mastering practice.
 *
 * **Why dither at all.** Naive truncation (`(x * 32767).toInt()`) introduces
 * quantization error that's *correlated* with the signal — the error
 * spectrum has harmonics at multiples of the signal frequency, audible as
 * "digital crunch" or harshness on quiet passages. Adding low-level random
 * noise *before* truncation decorrelates the quantization error, converting
 * harmonic distortion into broadband noise (which sounds like analog tape
 * hiss — far less objectionable, and below the audible threshold for any
 * normal listening level).
 *
 * **Why TPDF specifically.** Three options are common:
 *  - **RPDF** (rectangular PDF, single uniform sample): simplest, but the
 *    noise variance is signal-dependent (modulation noise) — the noise
 *    floor "breathes" with the signal. Audible artifact.
 *  - **TPDF** (triangular PDF, sum of two uniforms): noise variance is
 *    signal-INDEPENDENT (the math works out — see Lipshitz/Wannamaker/
 *    Vanderkooy 1992). Standard for 16-bit. What we use.
 *  - **Noise-shaped** (TPDF + feedback to push noise to high frequencies):
 *    pushes the noise floor below the perceptual threshold curve. Overkill
 *    for podcasts; reserved for 16-bit master deliveries of music.
 *
 * **Amplitude.** Peak ±1 LSB, RMS ~0.408 LSB. In normalized [-1, 1) signal
 * space, that's ±1/32767 ≈ ±3.05e-5 peak.
 *
 * **Threading.** Each instance carries its own [Random]; not thread-safe.
 * Construct one per processor and call [next] on the audio thread only.
 */
class Dither(seed: Long = System.nanoTime()) {
    private val rng = Random(seed)

    /**
     * One TPDF sample, scaled to ±1 LSB at 16-bit. Add to your normalized
     * [-1, 1) Float64 signal *before* the (* 32767).toInt() truncation.
     * Returns a value in approximately (-LSB, +LSB) with peak density at 0.
     */
    fun next(): Double {
        // (rand_a - rand_b) where each is uniform [0, 1) gives a triangular
        // distribution in (-1, 1) with peak density at 0. Scaling by LSB
        // puts it in the right range for the truncation we're about to do.
        return (rng.nextDouble() - rng.nextDouble()) * LSB_NORMALIZED
    }

    companion object {
        /** One 16-bit LSB expressed in normalized [-1, 1) signal space. */
        private const val LSB_NORMALIZED = 1.0 / 32767.0
    }
}
