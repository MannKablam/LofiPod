package com.lofipod.app.audio

import org.jtransforms.fft.DoubleFFT_1D

/**
 * Uniform Partitioned Convolution (UPC).
 *
 * Convolves a real-valued input stream with an arbitrary FIR kernel. Replaces
 * the monolithic FFT overlap-add used by v0.8.0's LinearPhaseEq
 * (KERNEL_LENGTH = 4096, FRAME_SIZE = 1024, FFT_SIZE = 8192) with a
 * partitioned scheme:
 *
 *   - Kernel of length `kernelLen` is split into `P = kernelLen / blockSize`
 *     partitions of `blockSize` samples each. Each partition is zero-padded
 *     to `fftSize = 2 * blockSize` and stored as a packed-complex spectrum.
 *   - Per input block of `blockSize` samples, the processor builds a 2L
 *     overlap of `[prev_input | current_input]`, runs ONE forward FFT,
 *     pushes the result onto a frequency-domain delay line (FDL) of length
 *     P, multiply-accumulates the FDL against the kernel partitions, runs
 *     ONE inverse FFT, and emits the second half (overlap-save).
 *
 * **Why this is better than the monolithic 8192-pt OLA used pre-v0.9.3:**
 *
 *   1. **Cheaper per output sample.** Complexity per sample is
 *      `O(log L + P/2)` vs. `O(log FFT_SIZE)`. For L=1024 / P=4 / 2L=2048
 *      vs. FFT_SIZE=8192, UPC runs ~3× fewer FFT ops per output block —
 *      one realForward + one realInverse at size 2048 vs. one of each at
 *      size 8192. The P=4 spectral multiply-accumulate has its own cost
 *      but doesn't outweigh the FFT savings.
 *   2. **Clean kernel switching mid-stream.** The FDL stores INPUT history
 *      (frequency-domain), not OUTPUT history. Replacing the kernel
 *      partition spectra atomically yields a clean linear-time-invariant
 *      switch — no stale OLA tail mixing the new kernel's output with the
 *      old kernel's convolution residue. Resolves the
 *      _LOFIPOD_V1_BRIEF.md §A2 "tail-mix on band change" symptom that
 *      gave the monolithic OLA path a soft chuff per slider tick.
 *   3. **Lower algorithmic latency.** Buffering latency is `blockSize`
 *      samples (~23 ms at 1024@44.1k) regardless of kernel length —
 *      monolithic OLA gives `FRAME_SIZE` samples but with a longer-FFT
 *      penalty per chunk. The audible group delay still equals the kernel
 *      center for symmetric kernels (~46 ms for KERNEL_LENGTH=4096); for
 *      asymmetric / minimum-phase kernels (energy front-loaded), group
 *      delay collapses to ms-scale.
 *
 * **Reference:** Frank Wefers, "Partitioned convolution algorithms for
 * real-time auralization" (RWTH Aachen, 2015). FFT size = 2 × block_length
 * is the optimal default; deviating only pays for very long kernels.
 *
 * **Threading.** Single-threaded by construction inside [processBlock] /
 * [setKernelTaps]. The audio thread calls [processBlock]; the kernel-
 * synthesis worker thread calls [setKernelTaps]. Publication via a single
 * `@Volatile` reference swap of the partition-spectra array; the audio
 * thread snapshots that reference once per block, so no torn reads.
 *
 * **Allocation discipline.** [processBlock] allocates nothing on the audio
 * thread. [setKernelTaps] allocates a new partition-spectra array (worker
 * thread, before publish).
 */
class UpcConvolver(
    private val channels: Int,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val kernelLen: Int = DEFAULT_KERNEL_LEN,
) {
    init {
        require(blockSize > 0 && (blockSize and (blockSize - 1)) == 0) {
            "blockSize must be a power of two; got $blockSize"
        }
        require(kernelLen % blockSize == 0) {
            "kernelLen ($kernelLen) must be a multiple of blockSize ($blockSize)"
        }
    }

    /** FFT size for the per-block transform; 2× blockSize is the Wefers optimal default. */
    private val fftSize: Int = 2 * blockSize

    /** Number of kernel partitions (P). */
    private val numPartitions: Int = kernelLen / blockSize

    /** Per-channel FFT instances. JTransforms `DoubleFFT_1D` is not
     *  thread-safe across one instance, but each channel uses its own
     *  on a single thread (the audio thread). */
    private val fft: Array<DoubleFFT_1D> =
        Array(channels) { DoubleFFT_1D(fftSize.toLong()) }

    /**
     * Kernel partition spectra. Index `[p]` holds the packed-complex
     * spectrum of the p-th partition, zero-padded to [fftSize] before
     * the FFT. Published atomically by [setKernelTaps] via `@Volatile`
     * reference swap; audio thread snapshots once per block.
     *
     * Initial value: identity (impulse at sample 0 of the zeroth
     * partition, zero elsewhere). Convolution with this identity is
     * the input passed through with [blockSize] samples of buffering
     * delay. Lets the engine produce sensible output even before
     * the first [setKernelTaps] call lands.
     */
    @Volatile
    private var kernelPartitionSpectra: Array<DoubleArray> = buildIdentityPartitions()

    /** Frequency-domain delay line per channel: ring of P partition spectra
     *  of past input. Head walks forward by 1 each block; oldest partition
     *  is overwritten in place. */
    private val fdl: Array<Array<DoubleArray>> =
        Array(channels) { Array(numPartitions) { DoubleArray(fftSize) } }

    /** Per-channel head pointer into the FDL ring. */
    private val fdlHead: IntArray = IntArray(channels)

    /** Per-channel last block of input (for the overlap part of overlap-save). */
    private val prevInput: Array<DoubleArray> =
        Array(channels) { DoubleArray(blockSize) }

    /** Per-channel scratch buffer holding the 2L overlap before the forward FFT. */
    private val workspace: Array<DoubleArray> =
        Array(channels) { DoubleArray(fftSize) }

    /** Per-channel spectral accumulator for the partition multiply-accumulate. */
    private val accumSpec: Array<DoubleArray> =
        Array(channels) { DoubleArray(fftSize) }

    /**
     * Replace the convolution kernel. Synthesizes partition spectra from
     * the time-domain taps and publishes them via a single `@Volatile`
     * reference swap. Safe to call from any thread; the audio thread will
     * pick up the new kernel on its next [processBlock] call (atomic
     * boundary, no torn reads).
     *
     * `taps` may be shorter than [kernelLen]; the remainder zero-pads
     * implicitly. Longer than [kernelLen] truncates with a log-worthy
     * silent drop (this would be a programming bug — the convolver's
     * length is fixed at construction).
     */
    fun setKernelTaps(taps: DoubleArray) {
        val partitions = Array(numPartitions) { p ->
            val ws = DoubleArray(fftSize)
            val srcOff = p * blockSize
            if (srcOff < taps.size) {
                val n = minOf(blockSize, taps.size - srcOff)
                System.arraycopy(taps, srcOff, ws, 0, n)
            }
            // Worker-thread FFT instance: per-synthesis fresh instance is
            // wasteful, but kernel changes are rare (per slider stop / mode
            // toggle) and the allocation is small. Alternative would be a
            // dedicated DoubleFFT_1D held by the synthesizer.
            DoubleFFT_1D(fftSize.toLong()).realForward(ws)
            ws
        }
        kernelPartitionSpectra = partitions
    }

    /**
     * Process one block of input. Reads `blockSize` samples per channel
     * from `input[ch][0 .. blockSize-1]` and writes `blockSize` samples
     * per channel into `output[ch][0 .. blockSize-1]`.
     *
     * Algorithmic delay = blockSize samples (one block). The output of
     * this call corresponds to a sliding-window convolution of the input
     * stream against the current kernel, evaluated at the most recent
     * blockSize-sample boundary.
     */
    fun processBlock(input: Array<DoubleArray>, output: Array<DoubleArray>) {
        val parts = kernelPartitionSpectra  // snapshot once
        for (ch in 0 until channels) {
            val ws = workspace[ch]
            val acc = accumSpec[ch]
            val prev = prevInput[ch]
            val inp = input[ch]
            val out = output[ch]

            // Overlap-save 2L input window: [prev | current].
            System.arraycopy(prev, 0, ws, 0, blockSize)
            System.arraycopy(inp, 0, ws, blockSize, blockSize)
            // Slide: this block becomes the next call's prev.
            System.arraycopy(inp, 0, prev, 0, blockSize)

            fft[ch].realForward(ws)

            // Push into FDL.
            val head = fdlHead[ch]
            System.arraycopy(ws, 0, fdl[ch][head], 0, fftSize)

            // Accumulate Σ FDL[head - p] · K[p].
            java.util.Arrays.fill(acc, 0.0)
            for (p in 0 until numPartitions) {
                val idx = ((head - p) % numPartitions + numPartitions) % numPartitions
                packedComplexMultiplyAccumulate(fdl[ch][idx], parts[p], acc, fftSize)
            }
            fdlHead[ch] = (head + 1) % numPartitions

            fft[ch].realInverse(acc, true)

            // Emit second half (overlap-save: first L samples are circular
            // wrap-around garbage; second L is the linear convolution
            // output we want).
            System.arraycopy(acc, blockSize, out, 0, blockSize)
        }
    }

    /**
     * Clear all FDL contents and the overlap buffer. Call from the audio
     * chain's flush so a seek doesn't leak pre-seek input out the back of
     * the convolution. Does NOT touch the kernel partition spectra — those
     * remain valid across flushes.
     */
    fun reset() {
        for (ch in 0 until channels) {
            for (p in 0 until numPartitions) {
                java.util.Arrays.fill(fdl[ch][p], 0.0)
            }
            java.util.Arrays.fill(prevInput[ch], 0.0)
            fdlHead[ch] = 0
        }
    }

    /** Algorithmic input-to-output buffering delay, in samples at the
     *  input rate. Equals [blockSize] regardless of kernel length —
     *  group delay for symmetric kernels comes ON TOP via the kernel
     *  itself. */
    val algorithmicDelayFrames: Int get() = blockSize

    /** Number of kernel partitions. Exposed for diagnostics. */
    val partitionCount: Int get() = numPartitions

    /** FFT size per partition. Exposed for diagnostics. */
    val partitionFftSize: Int get() = fftSize

    /**
     * JTransforms real-FFT packed multiply-accumulate: `dst += a · b`
     * elementwise in packed-complex form.
     *
     * Packed format reminder:
     *   - `spec[0]` = Re[0]              (DC, real-only)
     *   - `spec[1]` = Re[N/2]            (Nyquist, real-only)
     *   - `spec[2k]`   = Re[k] for k=1..N/2-1
     *   - `spec[2k+1]` = Im[k] for k=1..N/2-1
     */
    private fun packedComplexMultiplyAccumulate(
        a: DoubleArray,
        b: DoubleArray,
        dst: DoubleArray,
        n: Int,
    ) {
        dst[0] += a[0] * b[0]                  // DC
        dst[1] += a[1] * b[1]                  // Nyquist
        val half = n / 2
        var k = 1
        while (k < half) {
            val aRe = a[2 * k]
            val aIm = a[2 * k + 1]
            val bRe = b[2 * k]
            val bIm = b[2 * k + 1]
            dst[2 * k]     += aRe * bRe - aIm * bIm
            dst[2 * k + 1] += aRe * bIm + aIm * bRe
            k++
        }
    }

    /** Build the kernel partition spectra for an identity kernel (impulse
     *  at tap 0). Used as the initial value before the first
     *  [setKernelTaps] call lands. */
    private fun buildIdentityPartitions(): Array<DoubleArray> {
        val parts = Array(numPartitions) { DoubleArray(fftSize) }
        // Identity: impulse at the very first sample of partition 0.
        parts[0][0] = 1.0
        for (p in 0 until numPartitions) {
            DoubleFFT_1D(fftSize.toLong()).realForward(parts[p])
        }
        return parts
    }

    companion object {
        /**
         * Default kernel length. Matches v0.8.0's `LinearPhaseEq.KERNEL_LENGTH` so
         * the perceptual character of the FIR-based modes is preserved
         * across the v0.9.3 architecture switch from monolithic OLA to UPC.
         */
        const val DEFAULT_KERNEL_LEN = 4096

        /**
         * Default partition size (L). 1024 gives P=4 partitions at the
         * default kernel length — Wefers's optimal default for medium-
         * length kernels at modest CPU.
         */
        const val DEFAULT_BLOCK_SIZE = 1024
    }
}
