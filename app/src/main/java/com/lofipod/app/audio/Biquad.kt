package com.lofipod.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Direct-Form II Transposed biquad filter.
 * One instance per channel per band; coefficients shared across channels per band.
 *
 * Coefficient formulas come from RBJ's audio EQ cookbook (peaking EQ).
 *
 * **Float64 throughout.** Biquads at low center frequencies (31 / 62 Hz) are
 * numerically marginal in Float32 — the cookbook formulas multiply quantities
 * that cancel to ~1e-7 of full magnitude, and Float32 has only ~7 decimal
 * digits of precision. Result: sub-bass DC offset, frequency-response error
 * at the low extremes. Float64 has ~16 decimal digits and the problem
 * vanishes. CPU cost on modern ARM is identical for scalar Double vs Float
 * arithmetic (the Float case wasn't being SIMD'd anyway); this change is
 * pure precision win, no perf hit.
 *
 * **Denormal flush.** Biquad state (`z1`, `z2`) decays exponentially during
 * silence. At Float64 the values cross into denormal range (~1e-308) after a
 * few hundred ms of zero input, and ARM cores that don't have flush-to-zero
 * mode set in the FPU control register fall off into per-op slow path
 * (~30x slowdown). We don't have that mode set here (it's per-thread and not
 * universally honored on Android), so the safety belt is to manually clamp
 * `z1`/`z2` to zero whenever they fall below the audible threshold (~1e-15
 * in normalized [-1, 1) range, well above denormal range and well below
 * 16-bit quantization noise). One comparison per process() call; immeasurable
 * cost.
 */
class Biquad {
    // Coefficients (Float64)
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    // State (per channel — created externally) (Float64)
    private var z1 = 0.0; private var z2 = 0.0

    fun reset() { z1 = 0.0; z2 = 0.0 }

    /**
     * Configure as a peaking EQ band.
     *
     * **Nyquist guard.** When [centerHz] >= sampleRate/2 (the source's
     * Nyquist limit), the cookbook formulas degenerate: w0 wraps past π
     * so cos/sin go negative or zero, alpha can flip sign, and the
     * resulting biquad's response across the audible band becomes
     * arbitrary (sometimes near-identity, sometimes wildly skewed). Real
     * sources at low sample rates trip this — Kabod packs ship 16 kHz
     * mono (Chanski Leviticus) and 22.05 kHz mono (Still packs); at those
     * rates LofiPod's 16 kHz band sits above Nyquist and the 8 kHz band
     * sits at it. Bypassing those bands as identity keeps the cascade
     * well-behaved on low-rate sources without changing the response on
     * the audible part of the spectrum (you can't EQ what isn't there).
     */
    fun setPeaking(sampleRate: Int, centerHz: Float, gainDb: Float, q: Float) {
        if (centerHz * 2f >= sampleRate.toFloat()) {
            // Identity biquad: pass-through, preserves whatever state z1/z2
            // already hold. Coefficients computed once at companion init
            // wouldn't preserve z1/z2 across calls; setting them inline is
            // correct + cheap.
            b0 = 1.0; b1 = 0.0; b2 = 0.0
            a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centerHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0p = 1.0 + alpha * a
        val b1p = -2.0 * cosW0
        val b2p = 1.0 - alpha * a
        val a0  = 1.0 + alpha / a
        val a1p = -2.0 * cosW0
        val a2p = 1.0 - alpha / a

        b0 = b0p / a0
        b1 = b1p / a0
        b2 = b2p / a0
        a1 = a1p / a0
        a2 = a2p / a0
    }

    /**
     * Configure as a 2nd-order high-pass (RBJ cookbook). Used by the tone-
     * filter stage's low-cut. Butterworth Q (0.707) by default — maximally
     * flat passband, 12 dB/oct slope. Same Nyquist guard as [setPeaking]:
     * a cutoff at/above Nyquist degenerates the math, so bypass as identity
     * (the caller treats "cut everything" as a config error, not a goal).
     */
    fun setHighpass(sampleRate: Int, cutoffHz: Float, q: Float = 0.7071f) {
        if (cutoffHz * 2f >= sampleRate.toFloat()) {
            setIdentity(); return
        }
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0p = (1.0 + cosW0) / 2.0
        val b1p = -(1.0 + cosW0)
        val b2p = (1.0 + cosW0) / 2.0
        val a0  = 1.0 + alpha
        val a1p = -2.0 * cosW0
        val a2p = 1.0 - alpha
        commit(b0p, b1p, b2p, a0, a1p, a2p)
    }

    /** 2nd-order low-pass (RBJ). Tone-filter stage's high-cut. Nyquist-guarded
     *  to identity — a low-pass at/above Nyquist passes everything anyway. */
    fun setLowpass(sampleRate: Int, cutoffHz: Float, q: Float = 0.7071f) {
        if (cutoffHz * 2f >= sampleRate.toFloat()) {
            setIdentity(); return
        }
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0p = (1.0 - cosW0) / 2.0
        val b1p = 1.0 - cosW0
        val b2p = (1.0 - cosW0) / 2.0
        val a0  = 1.0 + alpha
        val a1p = -2.0 * cosW0
        val a2p = 1.0 - alpha
        commit(b0p, b1p, b2p, a0, a1p, a2p)
    }

    /**
     * Low shelf (RBJ, shelf-slope form with S=1 → gentle one-octave-ish
     * transition). Positive [gainDb] lifts everything below [cornerHz];
     * negative dips it. Used in a complementary pair with [setHighShelf]
     * for the tilt control.
     */
    fun setLowShelf(sampleRate: Int, cornerHz: Float, gainDb: Float) {
        if (cornerHz * 2f >= sampleRate.toFloat() || gainDb == 0f) {
            setIdentity(); return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * cornerHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        // S = 1 (standard shelf slope): alpha = sin/2 * sqrt((A + 1/A)(1/S - 1) + 2)
        val alpha = sinW0 / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha

        val b0p = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha)
        val b1p = 2 * a * ((a - 1) - (a + 1) * cosW0)
        val b2p = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha)
        val a0  = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
        val a1p = -2 * ((a - 1) + (a + 1) * cosW0)
        val a2p = (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha
        commit(b0p, b1p, b2p, a0, a1p, a2p)
    }

    /** High shelf (RBJ, S=1). Mirror of [setLowShelf] for the top end. */
    fun setHighShelf(sampleRate: Int, cornerHz: Float, gainDb: Float) {
        if (cornerHz * 2f >= sampleRate.toFloat() || gainDb == 0f) {
            setIdentity(); return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * cornerHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha

        val b0p = a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha)
        val b1p = -2 * a * ((a - 1) + (a + 1) * cosW0)
        val b2p = a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha)
        val a0  = (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha
        val a1p = 2 * ((a - 1) - (a + 1) * cosW0)
        val a2p = (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha
        commit(b0p, b1p, b2p, a0, a1p, a2p)
    }

    /** Identity (pass-through) coefficients; state preserved. */
    fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0
        a1 = 0.0; a2 = 0.0
    }

    /** Normalize by a0 and install. Shared by all the RBJ configure paths. */
    private fun commit(b0p: Double, b1p: Double, b2p: Double, a0: Double, a1p: Double, a2p: Double) {
        b0 = b0p / a0
        b1 = b1p / a0
        b2 = b2p / a0
        a1 = a1p / a0
        a2 = a2p / a0
    }

    /**
     * Copy [other]'s coefficients AND state into this filter. Used by the
     * cross-fade machinery to spawn a fresh "old" filter that picks up
     * exactly where the in-place filter left off, while the in-place filter
     * gets reconfigured with new coefficients. Two filters then run in
     * parallel for the duration of the fade.
     */
    fun copyFrom(other: Biquad) {
        b0 = other.b0; b1 = other.b1; b2 = other.b2
        a1 = other.a1; a2 = other.a2
        z1 = other.z1; z2 = other.z2
    }

    /** Process one sample. */
    fun process(sample: Double): Double {
        val out = b0 * sample + z1
        z1 = b1 * sample - a1 * out + z2
        z2 = b2 * sample - a2 * out
        // Flush to zero when state has decayed below audible. Prevents
        // denormal-range Doubles from triggering ARM FPU's slow path during
        // long silences. 1e-15 is ~6 orders of magnitude below 16-bit
        // quantization noise (~1.5e-5) so it's inaudible; ~290 orders of
        // magnitude above the denormal cliff (~1e-308) so it's effective.
        if (abs(z1) < DENORMAL_CUTOFF) z1 = 0.0
        if (abs(z2) < DENORMAL_CUTOFF) z2 = 0.0
        return out
    }

    companion object {
        private const val DENORMAL_CUTOFF = 1e-15
    }
}
