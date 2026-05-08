package com.lofipod.app.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2x polyphase oversampler that wraps the look-ahead limiter. Phase A6 of the
 * audiophile DSP rebuild.
 *
 * **Why oversample only the limiter.** The biquad EQ chain is linear — it can
 * only attenuate / boost existing frequency content; it cannot create new
 * harmonics. The look-ahead limiter, however, is a time-varying gain element.
 * Multiplying a signal by a rapidly-changing gain envelope is mathematically a
 * convolution in the frequency domain, which spreads the signal's spectrum.
 * Components above the original Nyquist that would have been silently bandlimit-
 * ed away can fold back into the audible range as aliasing. Running the limiter
 * at 2x sample rate pushes the alias products above the original Nyquist where
 * the downsample low-pass filter removes them.
 *
 * **Architecture.**
 *   - One Kaiser-windowed sinc FIR (length [FIR_TAPS], cutoff at original
 *     Nyquist, β=9 → ~90 dB stopband)
 *   - Polyphase decomposition for the 2x upsampler (taps split into even/odd
 *     phases; each phase computed from 1x-rate input samples)
 *   - Polyphase 2x downsampler (single delay line at 2x rate, full FIR
 *     evaluated once per 1x-rate output)
 *   - Per-channel delay lines; gain scaling baked into coefficients
 *
 * **Filter design.**
 *   - 128 taps + Kaiser β=9 gives ~90 dB stopband and a ~0.045-wide transition
 *     band in normalized [0, 0.5] coordinates. With cutoff at 0.25 (=
 *     original Nyquist), the transition spans roughly 20..24 kHz at 44.1k
 *     input rate. Audio above ~20 kHz starts rolling off; above ~24 kHz it's
 *     in deep stopband. Below 20 kHz the response is flat to ~0.001 dB ripple
 *     — transparent for any listener including golden-ear audiophiles on
 *     music with cymbal / brass / airy-synthesis high-frequency content. The
 *     128-tap design replaced an earlier 64-tap version that had a wider
 *     transition (~18..26 kHz) and put the roll-off into the audible band
 *     for the most sensitive listeners. CPU cost for the upgrade is
 *     negligible on any modern phone (~11 M extra MACs/sec at 44.1k stereo).
 *   - Linear phase (FIR is symmetric → no phase distortion)
 *   - Group delay = (N-1)/2 = 63.5 samples at 2x rate = ~32 samples at 1x for
 *     up + 32 for down = 64 samples = ~1.4 ms total. Combined with the
 *     limiter's 5 ms LA, total chain latency is ~6.4 ms — still inaudible.
 *
 * **Polyphase math.**
 *   Upsampling: y[2n] = Σ h[2k] · x[n-k] (even phase)
 *               y[2n+1] = Σ h[2k+1] · x[n-k] (odd phase)
 *               Output is scaled by 2 to recover unity gain (zero-stuffing
 *               halves the spectral amplitude at passband frequencies).
 *   Downsampling: y[n] = Σ h[k] · x_2x[2n-k] (single FIR convolution at 2x
 *               rate, sampled at 1x rate). No amplitude scaling needed.
 *
 * **Threading.** Audio-thread only. Single-instance per audio chain.
 */
class Oversampler {
    private var sampleRate = 0
    private var channelCount = 0

    // FIR coefficients: low-pass at the original Nyquist (= 0.25 in the 2x-rate
    // filter's normalized [0, 0.5] coordinates). Kaiser-windowed sinc,
    // normalized so sum(taps) = 1 (DC gain = 1). Same coefficients used for
    // both up and down stages (the filter is symmetric in purpose — anti-
    // imaging on the way up, anti-aliasing on the way down).
    private val taps: DoubleArray = generateLowpass(FIR_TAPS, FIR_CUTOFF, FIR_KAISER_BETA)

    // Per-channel input delay line for the upsampler. Holds the last [TAPS/2]
    // 1x-rate input samples; the polyphase split lets us produce 2 output
    // samples (at 2x rate) from this many 1x history samples.
    private var upDelay: Array<DoubleArray> = emptyArray()
    private var upPos = 0

    // Per-channel input delay line for the downsampler. Holds the last [TAPS]
    // 2x-rate input samples; full FIR convolution at 2x rate, sampled once
    // per 1x output.
    private var downDelay: Array<DoubleArray> = emptyArray()
    private var downPos = 0

    /**
     * Total chain delay (up + down) in 1x-rate sample frames. Add to the
     * limiter's 1x-equivalent LA delay to compute the EOS drain length.
     */
    val totalDelayFrames1x: Int
        get() = (FIR_TAPS - 1) / 2  // (N-1)/2 at 2x = (N-1)/4 at 1x, ×2 for both stages = (N-1)/2 at 1x

    /** Number of FIR taps per stage, exposed for diagnostics. */
    val firTaps: Int
        get() = FIR_TAPS

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        upDelay = Array(channelCount) { DoubleArray(FIR_TAPS / 2) }
        downDelay = Array(channelCount) { DoubleArray(FIR_TAPS) }
        upPos = 0
        downPos = 0
    }

    fun reset() {
        for (ch in upDelay) ch.fill(0.0)
        for (ch in downDelay) ch.fill(0.0)
        upPos = 0
        downPos = 0
    }

    /**
     * 2x upsample one 1x-rate input frame into two 2x-rate output frames.
     * [input], [out0], [out1] all length == channelCount; [out0] is the
     * "even phase" sample (h[0], h[2], ...), [out1] the "odd phase" sample.
     *
     * The two output frames are temporally adjacent at 2x rate: feed them
     * to the limiter in order ([out0] first, [out1] second).
     */
    fun upsampleFrame(input: DoubleArray, out0: DoubleArray, out1: DoubleArray) {
        val histLen = upDelay[0].size  // = FIR_TAPS / 2
        for (ch in 0 until channelCount) {
            // Push current input into the delay line.
            upDelay[ch][upPos] = input[ch]
            // Polyphase: even-indexed taps × 1x-rate history → out0
            //            odd-indexed taps × 1x-rate history → out1
            // Both phases use the SAME 1x-rate history slots — the polyphase
            // decomposition is what makes upsampling efficient (each phase
            // sub-filter runs at 1x rate, producing one output sample per
            // input → two outputs per input total).
            var phase0 = 0.0
            var phase1 = 0.0
            for (k in 0 until histLen) {
                val histIdx = (upPos - k + histLen) % histLen
                val sample = upDelay[ch][histIdx]
                phase0 += taps[2 * k] * sample
                phase1 += taps[2 * k + 1] * sample
            }
            // ×2 amplitude scaling: zero-stuffing halves the spectral
            // amplitude at every passband frequency (the spectrum is
            // periodically duplicated, and the filter only keeps half), so
            // we multiply by 2 to recover unity passband gain.
            out0[ch] = phase0 * 2.0
            out1[ch] = phase1 * 2.0
        }
        upPos = (upPos + 1) % histLen
    }

    /**
     * 2x downsample two 2x-rate input frames into one 1x-rate output frame.
     * [in0], [in1] are temporally adjacent at 2x rate ([in0] first); [output]
     * is the corresponding 1x-rate sample. All length == channelCount.
     *
     * The two 2x inputs are pushed into the delay line in order; the FIR is
     * then evaluated against the post-push state. No amplitude scaling: the
     * FIR low-pass at unity DC gain naturally preserves passband amplitude
     * across decimation.
     */
    fun downsampleFrame(in0: DoubleArray, in1: DoubleArray, output: DoubleArray) {
        val N = downDelay[0].size  // = FIR_TAPS
        for (ch in 0 until channelCount) {
            // Push two 2x samples in order. Advance position twice — the
            // FIR scan below sees both samples in their correct relative
            // positions in the delay line.
            downDelay[ch][downPos] = in0[ch]
            val nextPos = (downPos + 1) % N
            downDelay[ch][nextPos] = in1[ch]

            // Full FIR convolution at 2x rate. We evaluate this only ONCE
            // per 1x output (decimation by 2), so total downsampler MAC cost
            // is FIR_TAPS per 1x output frame.
            var sum = 0.0
            for (k in 0 until N) {
                val histIdx = (nextPos - k + N) % N
                sum += taps[k] * downDelay[ch][histIdx]
            }
            output[ch] = sum
        }
        // Advance position by 2 (we wrote two samples this call).
        downPos = (downPos + 2) % downDelay[0].size
    }

    companion object {
        // FIR length. 128 taps with Kaiser β=9 gives ~90 dB stopband and a
        // ~0.045 transition width in normalized [0, 0.5] coordinates (≈ 4 kHz
        // transition centered on original Nyquist at 44.1k input rate, i.e.
        // ~20..24 kHz). Doubles MAC count vs. the earlier 64-tap design but
        // CPU cost is negligible on any modern phone — the cleaner top-end
        // roll-off is worth it for golden-ear audiophiles. 64-tap version
        // shipped through v0.6.x; bumped to 128 alongside the Kaiser-windowed
        // linear-phase kernel polish.
        private const val FIR_TAPS = 128
        // Cutoff at 0.25 normalized = original Nyquist (= half of the
        // filter's Nyquist, which is the 2x rate's Nyquist). The filter
        // operates at 2x rate; in its normalized [0, 0.5] coordinates,
        // 0 = DC, 0.25 = original Nyquist (boundary between original signal
        // band and the image generated by zero-stuffing), 0.5 = 2x Nyquist.
        // Below 0.25 = original signal content (passband); above 0.25 =
        // imaging artifacts to remove (stopband). Earlier code used 0.5
        // here, which put the cutoff at the filter's own Nyquist — i.e.,
        // no filtering at all — so the alias survived through the
        // limiter and folded back into the audible range as garbled
        // high-frequency hash. v0.4.10 fixes this.
        private const val FIR_CUTOFF = 0.25
        // Kaiser β=9 ↔ ~90 dB stopband attenuation. β=10 would give ~95 dB at
        // the cost of slightly wider transition; β=7 gives ~70 dB (audible
        // alias residue on loud transients). 9 is the audiophile sweet spot.
        private const val FIR_KAISER_BETA = 9.0

        /**
         * Generate a Kaiser-windowed sinc low-pass FIR.
         *
         * Sinc impulse response of an ideal LPF at normalized cutoff [cutoff]
         * (0..1, where 1 = Nyquist of the rate the filter operates at):
         *   h[n] = 2*cutoff * sinc(2*cutoff*(n - mid))
         * where mid = (N-1)/2 places the peak at the center of the window
         * (linear-phase filter).
         *
         * Kaiser window:
         *   w[n] = I0(β * sqrt(1 - ((2n/(N-1)) - 1)²)) / I0(β)
         * I0 is the modified Bessel function of the first kind, order 0.
         *
         * Final taps are normalized so sum(h) = 1 (DC gain = 1). The 2x
         * upscale factor for upsampling is applied separately at the call
         * site so the same coefficients work for both up and down stages.
         */
        private fun generateLowpass(N: Int, cutoff: Double, beta: Double): DoubleArray {
            val h = DoubleArray(N)
            val mid = (N - 1) / 2.0
            val betaI0 = besselI0(beta)
            var sum = 0.0
            for (n in 0 until N) {
                val x = n - mid
                // sinc(0) = 1 by limit; otherwise sin(πx)/(πx) scaled by 2c.
                val sincVal = if (x == 0.0) {
                    2.0 * cutoff
                } else {
                    sin(2.0 * PI * cutoff * x) / (PI * x)
                }
                // Kaiser window scaled to [-1, 1] over the FIR span.
                val ratio = 2.0 * n / (N - 1) - 1.0
                val w = besselI0(beta * sqrt(1.0 - ratio * ratio)) / betaI0
                h[n] = sincVal * w
                sum += h[n]
            }
            // Normalize for unity DC gain. The Kaiser window slightly perturbs
            // the analytic sinc DC sum (= 2*cutoff = 1 for cutoff=0.5), so the
            // normalization corrects to exact unity.
            for (n in 0 until N) h[n] /= sum
            return h
        }

        /**
         * Modified Bessel function I0(x), summed by series until further terms
         * are below 1e-15 (machine epsilon × ~10¹). Called only at startup for
         * filter coefficient generation, so iteration count doesn't affect the
         * audio path.
         *
         *   I0(x) = Σ_{k=0..∞} (x/2)^(2k) / (k!)²
         *
         * Recurrence on the term avoids computing (k!)² directly.
         */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-15) {
                val r = x / (2.0 * k)
                term *= r * r
                sum += term
                k++
                if (k > 200) break  // belt-and-braces; converges in ~30 iters
            }
            return sum
        }
    }
}
