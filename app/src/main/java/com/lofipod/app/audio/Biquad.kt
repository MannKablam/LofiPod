package com.lofipod.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Direct-Form II Transposed biquad filter.
 * One instance per channel per band; coefficients shared across channels per band.
 *
 * Coefficient formulas come from RBJ's audio EQ cookbook (peaking EQ).
 */
class Biquad {
    // Coefficients
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    // State (per channel — created externally)
    private var z1 = 0f; private var z2 = 0f

    fun reset() { z1 = 0f; z2 = 0f }

    /** Configure as a peaking EQ band. */
    fun setPeaking(sampleRate: Int, centerHz: Float, gainDb: Float, q: Float) {
        val a = 10.0.pow((gainDb / 40.0)).toFloat()
        val w0 = 2f * PI.toFloat() * centerHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)

        val b0p = 1f + alpha * a
        val b1p = -2f * cosW0
        val b2p = 1f - alpha * a
        val a0  = 1f + alpha / a
        val a1p = -2f * cosW0
        val a2p = 1f - alpha / a

        b0 = b0p / a0
        b1 = b1p / a0
        b2 = b2p / a0
        a1 = a1p / a0
        a2 = a2p / a0
    }

    /** Process one sample. */
    fun process(sample: Float): Float {
        val out = b0 * sample + z1
        z1 = b1 * sample - a1 * out + z2
        z2 = b2 * sample - a2 * out
        return out
    }
}
