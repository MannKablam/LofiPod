package com.lofipod.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Linear-phase parametric EQ via FFT overlap-add convolution. Phase C of the
 * audiophile DSP rebuild.
 *
 * **Why linear phase, optionally.** The default biquad EQ chain is
 * MINIMUM-phase: gain shaping is cheap and low-latency, but it introduces
 * frequency-dependent group delay (different frequencies are delayed by
 * different amounts). For most listening this is inaudible — the ear can't
 * resolve sub-millisecond group delay differences. For listeners who can
 * (or who want to verify a recording's transient response without the EQ
 * smearing it), linear phase is the audiophile-grade alternative: the entire
 * spectrum is delayed by the SAME amount, preserving the original signal's
 * waveform shape exactly.
 *
 * **Architecture.**
 *   - **Kernel synthesis** ([synthesizeKernel]). Sample the cascaded biquad
 *     magnitude response at [FFT_SIZE]/2 + 1 frequency points. Set phase to
 *     zero, IFFT to get an acausal symmetric impulse response, circular-shift
 *     by [FFT_SIZE]/2 to make it causal, truncate to [KERNEL_LENGTH] taps
 *     centered on the impulse. The result is a symmetric (linear-phase) FIR
 *     kernel. Re-FFT'd to a packed-complex spectrum for runtime convolution.
 *   - **Overlap-add convolution** ([processChunk]). Per [FRAME_SIZE]-sample
 *     input chunk: zero-pad to [FFT_SIZE], FFT, multiply by the kernel
 *     spectrum, IFFT. The first [FRAME_SIZE] samples of the result are this
 *     chunk's output (mixed with overlap-tail from prior chunks); the next
 *     [KERNEL_LENGTH]-1 samples are saved as overlap for future chunks.
 *
 * **Latency.** Group delay = ([KERNEL_LENGTH] - 1) / 2 ≈ 2047 samples ≈
 * 46.4 ms at 44.1k. Combined with the chain's existing ~5.7 ms (limiter LA +
 * oversampler FIR), total chain latency in linear-phase mode is ~52 ms.
 * Audible at very fast UI feedback contexts (typing-while-listening) but
 * fine for podcast playback.
 *
 * **Threading.**
 *   - Audio thread calls [pushFrame] / [popFrame] / [outputFramesAvailable].
 *   - UI thread calls [setBands] which kicks off async kernel synthesis on
 *     [Dispatchers.Default]. Synthesis publishes the new kernel via a single
 *     `@Volatile` reference swap; the audio thread snapshots it once per
 *     chunk. No locks, no torn reads.
 *   - One JTransforms `DoubleFFT_1D` instance per channel is allocated at
 *     [configure]; JTransforms is NOT thread-safe across channels but each
 *     instance is single-threaded by construction. The synthesis worker uses
 *     its own dedicated FFT instance ([synthesisFft]) to avoid contention.
 *
 * **Allocation discipline.** No allocation in [pushFrame] / [popFrame] /
 * [processChunk] (the hot path). Reusable scratch buffers + per-channel
 * pre-allocated state. Kernel synthesis on the worker DOES allocate (~16
 * doubles × FFT_SIZE = ~128 KB transient), but that happens only on band
 * changes, not every audio buffer.
 */
class LinearPhaseEq {

    private var sampleRate = 0
    private var channelCount = 0

    // Kernel spectrum in JTransforms packed complex format. Length [FFT_SIZE].
    // Updated atomically via @Volatile reference swap from the synthesis
    // worker; audio thread snapshots once per chunk.
    @Volatile private var kernelSpectrum: DoubleArray = DoubleArray(0)

    // Per-channel state. Allocated in [configure]; nulled by [release].
    private var fft: Array<DoubleFFT_1D> = emptyArray()
    private var inputAccum: Array<DoubleArray> = emptyArray()
    private var inputAccumPos: IntArray = IntArray(0)
    // Workspace per channel for the FFT in/out — preallocated so processChunk
    // doesn't allocate on the hot path. JTransforms operates in-place on this
    // buffer across the realForward → multiply → realInverse cycle.
    private var workspace: Array<DoubleArray> = emptyArray()
    // Overlap-add tail: accumulated convolution-output samples at positions
    // beyond the current chunk that will mix into future chunks' outputs.
    // Length [KERNEL_LENGTH] - 1 per channel.
    private var pending: Array<DoubleArray> = emptyArray()
    // Output queue per channel. ArrayDeque<Double> is allocation-light for
    // FIFO operations and matches our "push L per chunk, pop 1 per frame"
    // pattern. Sized typically a few thousand entries.
    private var outputQueue: Array<ArrayDeque<Double>> = emptyArray()

    // Synthesis worker. Owns its own FFT instance to avoid contending with
    // the audio-thread FFTs.
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null
    private val synthesisFft = DoubleFFT_1D(FFT_SIZE.toLong())

    fun configure(rate: Int, channels: Int) {
        sampleRate = rate
        channelCount = channels
        fft = Array(channels) { DoubleFFT_1D(FFT_SIZE.toLong()) }
        inputAccum = Array(channels) { DoubleArray(FRAME_SIZE) }
        inputAccumPos = IntArray(channels)
        workspace = Array(channels) { DoubleArray(FFT_SIZE) }
        pending = Array(channels) { DoubleArray(KERNEL_LENGTH - 1) }
        outputQueue = Array(channels) { ArrayDeque() }
        // Initial kernel = FLAT response (impulse). Synthesizing this gives a
        // 4096-tap symmetric kernel with peak at center; convolution becomes
        // identity (modulo group delay). This way the chain still functions
        // before the first setBands call lands a real kernel.
        synthesizeKernelSync(EqPresets.FLAT)
    }

    /**
     * Trigger asynchronous kernel re-synthesis. Cancels any in-flight job so
     * rapid slider drags don't queue up stale syntheses. The new kernel is
     * published via a single `@Volatile` reference swap once ready; until
     * then the audio thread continues using the previous kernel.
     */
    fun setBands(bands: List<EqBand>) {
        synthJob?.cancel()
        synthJob = workerScope.launch {
            synthesizeKernelSync(bands)
        }
    }

    /**
     * Synchronous kernel synthesis. Used internally by [setBands] (on the
     * worker) and by [configure] (one-shot). Builds the linear-phase impulse
     * response from the biquad cascade's magnitude response.
     */
    private fun synthesizeKernelSync(bands: List<EqBand>) {
        if (sampleRate == 0) return  // Pre-configure call; ignored.

        // Step 1: magnitude response of the biquad cascade at FFT_SIZE/2 + 1
        // frequency points (= bin centers of an FFT_SIZE FFT).
        val mag = DoubleArray(FFT_SIZE / 2 + 1)
        for (k in mag.indices) {
            val w = 2.0 * PI * k / FFT_SIZE
            var magSquared = 1.0
            for (band in bands) {
                magSquared *= biquadMagSquared(band, w)
            }
            mag[k] = sqrt(magSquared)
        }

        // Step 2: build a packed-complex JTransforms spectrum with zero
        // phase. Format reminder:
        //   spec[0] = Re[0] (DC)
        //   spec[1] = Re[N/2] (Nyquist)
        //   spec[2k] = Re[k], spec[2k+1] = Im[k] for k=1..N/2-1
        val spec = DoubleArray(FFT_SIZE)
        spec[0] = mag[0]
        spec[1] = mag[FFT_SIZE / 2]
        for (k in 1 until FFT_SIZE / 2) {
            spec[2 * k] = mag[k]
            spec[2 * k + 1] = 0.0
        }

        // Step 3: IFFT to get the time-domain impulse response. With zero
        // phase, the result is symmetric around index 0 in a CIRCULAR sense
        // (i.e., spec[N-i] = spec[i] for i > 0 after IFFT).
        synthesisFft.realInverse(spec, true)

        // Step 4: circular shift by FFT_SIZE/2 so the impulse peak (which
        // sits at index 0 in the acausal form) ends up at FFT_SIZE/2 in the
        // shifted form. The result is a causal, symmetric impulse response
        // of length FFT_SIZE peaked at FFT_SIZE/2.
        val shifted = DoubleArray(FFT_SIZE)
        val half = FFT_SIZE / 2
        for (i in 0 until FFT_SIZE) {
            shifted[i] = spec[(i + half) % FFT_SIZE]
        }

        // Step 5: truncate to KERNEL_LENGTH taps centered on the peak. With
        // KERNEL_LENGTH < FFT_SIZE, this drops the very-low-amplitude
        // trailing ringing on each side. Without a window the truncation
        // applies a rectangular window in time = sinc convolution in
        // frequency = small ripple in the magnitude response. Acceptable for
        // typical EQ shapes (-12..+12 dB peaking biquads); revisit if high-Q
        // bands show audible ripple.
        val kernel = DoubleArray(FFT_SIZE)  // zero-padded to FFT_SIZE for the convolution FFT
        val start = half - KERNEL_LENGTH / 2
        for (i in 0 until KERNEL_LENGTH) {
            kernel[i] = shifted[start + i]
        }
        // kernel[KERNEL_LENGTH..FFT_SIZE-1] = 0 (already zeroed)

        // Step 6: FFT the kernel to get the convolution spectrum. The kernel
        // has linear phase (slope = -PI * KERNEL_LENGTH/2 / (FFT_SIZE/2) per
        // bin) so the spectrum has non-zero imaginary parts. We use a full
        // complex multiply at runtime to handle that.
        synthesisFft.realForward(kernel)

        // Atomic publish via @Volatile reference swap. Audio thread will see
        // either the old kernel or the new kernel, never a partial mix.
        kernelSpectrum = kernel
    }

    /**
     * Reset all internal state. Called from the audio chain's onFlush so a
     * seek doesn't leak pre-seek audio out the back of the convolution.
     */
    fun reset() {
        for (ch in 0 until channelCount) {
            inputAccumPos[ch] = 0
            java.util.Arrays.fill(inputAccum[ch], 0.0)
            java.util.Arrays.fill(pending[ch], 0.0)
            outputQueue[ch].clear()
        }
    }

    /**
     * Push one frame of input (one sample per channel). When per-channel
     * accumulators reach [FRAME_SIZE], runs the FFT convolution and pushes
     * [FRAME_SIZE] samples per channel into [outputQueue].
     */
    fun pushFrame(input: DoubleArray) {
        for (ch in 0 until channelCount) {
            inputAccum[ch][inputAccumPos[ch]] = input[ch]
            inputAccumPos[ch]++
            if (inputAccumPos[ch] == FRAME_SIZE) {
                processChunk(ch)
                inputAccumPos[ch] = 0
            }
        }
    }

    /** Number of complete output frames currently buffered (across channels;
     *  channels advance in lockstep so any channel's queue size is
     *  representative). */
    fun outputFramesAvailable(): Int =
        if (outputQueue.isNotEmpty()) outputQueue[0].size else 0

    /** Pop one frame of output (one sample per channel). Caller must have
     *  verified [outputFramesAvailable] >= 1. */
    fun popFrame(output: DoubleArray) {
        for (ch in 0 until channelCount) {
            output[ch] = outputQueue[ch].removeFirst()
        }
    }

    private fun processChunk(channel: Int) {
        val ks = kernelSpectrum  // snapshot once per chunk
        if (ks.isEmpty()) {
            // Kernel not yet synthesized (shouldn't happen post-configure
            // but guard anyway). Drop input; emit zeros.
            for (i in 0 until FRAME_SIZE) outputQueue[channel].addLast(0.0)
            return
        }

        val ws = workspace[channel]
        // Copy accumulator into workspace; zero the rest. The copy is needed
        // because realForward operates in-place and we need to keep the
        // accumulator intact for next chunk's first FRAME_SIZE samples...
        // actually no, the accumulator is a STAGING buffer (filled then
        // drained in one shot) so we could realForward directly on it after
        // zero-padding. But re-using the workspace keeps the accumulator
        // small (FRAME_SIZE) and the FFT-sized buffer separate.
        System.arraycopy(inputAccum[channel], 0, ws, 0, FRAME_SIZE)
        java.util.Arrays.fill(ws, FRAME_SIZE, FFT_SIZE, 0.0)

        fft[channel].realForward(ws)
        multiplyComplexPacked(ws, ks)
        fft[channel].realInverse(ws, true)

        // ws[0..FRAME_SIZE+KERNEL_LENGTH-2] is the convolution result.
        // Output for THIS chunk: ws[0..FRAME_SIZE-1] mixed with pending[0..FRAME_SIZE-1].
        val pendingArr = pending[channel]
        val q = outputQueue[channel]
        for (i in 0 until FRAME_SIZE) {
            q.addLast(ws[i] + pendingArr[i])
        }

        // Slide pending forward by FRAME_SIZE (drop just-consumed prefix),
        // zero the now-empty tail.
        val tailLen = KERNEL_LENGTH - 1
        System.arraycopy(pendingArr, FRAME_SIZE, pendingArr, 0, tailLen - FRAME_SIZE)
        java.util.Arrays.fill(pendingArr, tailLen - FRAME_SIZE, tailLen, 0.0)

        // Add the convolution output's tail (ws[FRAME_SIZE..FRAME_SIZE+tailLen-1])
        // into pending, where it'll be summed against future chunks' outputs.
        for (i in 0 until tailLen) {
            pendingArr[i] += ws[FRAME_SIZE + i]
        }
    }

    /**
     * In-place packed-complex multiplication: a[k] *= b[k] for all k.
     * JTransforms packed format (FFT_SIZE-real result):
     *   a[0] = Re[0] (DC, real)
     *   a[1] = Re[N/2] (Nyquist, real)
     *   a[2k] = Re[k], a[2k+1] = Im[k] for k=1..N/2-1
     */
    private fun multiplyComplexPacked(a: DoubleArray, b: DoubleArray) {
        // DC and Nyquist are real-only.
        a[0] *= b[0]
        a[1] *= b[1]
        // Mid bins: full complex multiply.
        for (k in 1 until FFT_SIZE / 2) {
            val re1 = a[2 * k]
            val im1 = a[2 * k + 1]
            val re2 = b[2 * k]
            val im2 = b[2 * k + 1]
            a[2 * k] = re1 * re2 - im1 * im2
            a[2 * k + 1] = re1 * im2 + im1 * re2
        }
    }

    /**
     * Squared magnitude response of one peaking biquad at normalized angular
     * frequency [w] (= 2π·f/fs). RBJ peaking-EQ coefficients in standard
     * direct form, then |H(jw)|² = |B(z)|² / |A(z)|² where z = e^(jw).
     */
    private fun biquadMagSquared(band: EqBand, w: Double): Double {
        val A = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * band.centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * band.qFactor)
        val cosW0 = cos(w0)

        val b0 = 1.0 + alpha * A
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * A
        val a0 = 1.0 + alpha / A
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / A

        // Numerator: |B(e^jw)|² = |b0 + b1·e^(-jw) + b2·e^(-2jw)|²
        val cosOmega = cos(w)
        val sinOmega = sin(w)
        val cos2Omega = cos(2.0 * w)
        val sin2Omega = sin(2.0 * w)
        val numRe = b0 + b1 * cosOmega + b2 * cos2Omega
        val numIm = -b1 * sinOmega - b2 * sin2Omega
        val denRe = a0 + a1 * cosOmega + a2 * cos2Omega
        val denIm = -a1 * sinOmega - a2 * sin2Omega

        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = denRe * denRe + denIm * denIm
        return numMagSq / denMagSq
    }

    /**
     * Cancel any in-flight synthesis worker and free background coroutine
     * resources. Call from the audio chain's `onReset` so the scope doesn't
     * leak across processor lifecycles.
     */
    fun release() {
        workerScope.cancel()
    }

    companion object {
        /**
         * FIR kernel length (number of taps). 4096 gives ~46 ms group delay
         * at 44.1k — long enough to capture the shaped magnitude response of
         * a 10-band cascade with low Q (default ~1.41), short enough that
         * total chain latency stays comfortably below 100 ms.
         */
        const val KERNEL_LENGTH = 4096

        /**
         * Audio-frame chunk size. Each chunk runs one FFT/IFFT pair through
         * the convolution path. 1024 trades chunk latency (~23 ms at 44.1k)
         * against per-chunk FFT cost.
         */
        const val FRAME_SIZE = 1024

        /**
         * FFT size = next power of 2 ≥ FRAME_SIZE + KERNEL_LENGTH - 1 =
         * 5119, rounded up to 8192. Larger FFT than strictly necessary, but
         * powers of 2 are the JTransforms fast path.
         */
        const val FFT_SIZE = 8192
    }
}
