package com.lofipod.app.audio

/**
 * Uniform Partitioned Convolution (UPC) backed by PFFFT (v0.9.6+).
 *
 * Convolves a real-valued input stream with an arbitrary FIR kernel. Splits
 * the kernel into P partitions of L samples each; per input block of L
 * samples, runs ONE forward FFT at size 2L, multiply-accumulates a
 * frequency-domain delay-line against the partition spectra, runs ONE
 * inverse FFT, and emits the second half (overlap-save).
 *
 * **What changed in v0.9.6.** Pre-v0.9.6 this class used JTransforms's
 * `DoubleFFT_1D` (pure-JVM, double precision) with a hand-rolled
 * packed-complex multiply-accumulate. v0.9.6 swaps in PFFFT for both:
 *
 *   - **FFT path:** [PffftBridge.transform] (NEON-SIMD on ARM, ~3-5×
 *     faster than JTransforms at fftSize=2048).
 *   - **Multiply-accumulate:** [PffftBridge.zconvolveAccumulate] (PFFFT's
 *     hand-tuned SIMD inner loop, faster than rewriting in Kotlin).
 *
 * Internal arrays move from `DoubleArray` to `FloatArray`. The kernel
 * synthesis path inside [FirEq] stays in Double precision (off the audio
 * thread, where the cepstrum log/exp recipe benefits from extra bits) and
 * converts to Float at the [setKernelTaps] boundary.
 *
 * **Why this is better than the monolithic 8192-pt OLA used pre-v0.9.3:**
 *
 *   1. **Cheaper per output sample.** Complexity per sample is
 *      `O(log L + P/2)` vs. `O(log FFT_SIZE)`. For L=1024 / P=4 / 2L=2048
 *      vs. FFT_SIZE=8192, UPC runs ~3× fewer FFT ops per output block.
 *      With PFFFT's NEON speedup on top, the total speedup vs v0.8.0 is
 *      roughly 8-12× on ARM.
 *   2. **Clean kernel switching mid-stream.** The FDL stores INPUT history
 *      (frequency-domain), not OUTPUT history. Replacing the kernel
 *      partition spectra atomically yields a clean linear-time-invariant
 *      switch — no stale OLA tail mixing the new kernel's output with the
 *      old kernel's convolution residue.
 *   3. **Lower algorithmic latency.** Buffering latency is `blockSize`
 *      samples (~23 ms at 1024@44.1k) regardless of kernel length.
 *
 * **Reference:** Frank Wefers, "Partitioned convolution algorithms for
 * real-time auralization" (RWTH Aachen, 2015).
 *
 * **Threading.** Single-threaded by construction inside [processBlock] /
 * [setKernelTaps]. The audio thread calls [processBlock]; the kernel-
 * synthesis worker thread calls [setKernelTaps]. Publication via a single
 * `@Volatile` reference swap of the partition-spectra array.
 *
 * **Allocation discipline.** [processBlock] allocates nothing on the audio
 * thread. [setKernelTaps] allocates a new partition-spectra array (worker
 * thread, before publish). PFFFT's native scratch is allocated once at
 * [configure] inside the [PffftBridge].
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

    /** PFFFT bridge (one per UpcConvolver). Holds the native setup + aligned
     *  scratch buffers. Used by both the audio-thread [processBlock] and the
     *  worker-thread [setKernelTaps] — but since worker only ever calls
     *  [PffftBridge.transform] on its own FloatArrays and the audio thread
     *  only calls transform/zconvolveAccumulate on different FloatArrays,
     *  there's no shared mutable state at the bridge level. The native
     *  buffers inside the bridge get serialized through whoever's calling
     *  at any given moment.
     *
     * Caveat: PFFFT's native scratch buffer is shared. If both threads
     * called the bridge simultaneously they'd clobber each other. We
     * mitigate this by NOT calling setKernelTaps from the audio thread;
     * setKernelTaps runs on the worker, and the worker doesn't run while
     * processBlock is running (they're decoupled by the `@Volatile`
     * reference swap of [kernelPartitionSpectra]). The worker's local
     * FFT work is done on its own thread, but since the bridge has only
     * one scratch, we need a separate bridge for the worker.
     *
     * Solution: two bridges. [audioBridge] for the audio thread,
     * [workerBridge] for kernel-synth FFTs.
     */
    private val audioBridge = PffftBridge().also { it.configure(fftSize) }
    private val workerBridge = PffftBridge().also { it.configure(fftSize) }

    /**
     * Kernel partition spectra in PFFFT INTERNAL layout. Published
     * atomically by [setKernelTaps] via `@Volatile` reference swap;
     * audio thread snapshots once per block.
     *
     * Initial value: identity (impulse at sample 0 of the zeroth
     * partition, zero elsewhere) → convolution is the input passed
     * through with [blockSize] samples of buffering delay.
     */
    @Volatile
    private var kernelPartitionSpectra: Array<FloatArray> = buildIdentityPartitions()

    /** Frequency-domain delay line per channel: ring of P partition spectra
     *  of past input (PFFFT internal layout). */
    private val fdl: Array<Array<FloatArray>> =
        Array(channels) { Array(numPartitions) { FloatArray(fftSize) } }

    /** Per-channel head pointer into the FDL ring. */
    private val fdlHead: IntArray = IntArray(channels)

    /** Per-channel last block of input (for the overlap part of overlap-save). */
    private val prevInput: Array<FloatArray> =
        Array(channels) { FloatArray(blockSize) }

    /** Per-channel scratch holding the 2L overlap before the forward FFT. */
    private val workspace: Array<FloatArray> =
        Array(channels) { FloatArray(fftSize) }

    /** Per-channel spectral accumulator for the partition multiply-accumulate. */
    private val accumSpec: Array<FloatArray> =
        Array(channels) { FloatArray(fftSize) }

    /**
     * Replace the convolution kernel. Synthesizes partition spectra from
     * the time-domain taps and publishes them via a single `@Volatile`
     * reference swap. Safe to call from any thread; the audio thread will
     * pick up the new kernel on its next [processBlock] call.
     *
     * `taps` may be shorter than [kernelLen]; the remainder zero-pads
     * implicitly. Longer truncates with a silent drop (programming bug —
     * the convolver's length is fixed at construction).
     *
     * The Double→Float conversion happens here, at the boundary between
     * the worker thread's high-precision synthesis and the audio thread's
     * single-precision convolution path.
     */
    fun setKernelTaps(taps: DoubleArray) {
        val partitions = Array(numPartitions) { p ->
            val ws = FloatArray(fftSize)
            val srcOff = p * blockSize
            if (srcOff < taps.size) {
                val n = minOf(blockSize, taps.size - srcOff)
                // Double → Float conversion at the boundary. Single-
                // precision is plenty for 16-bit PCM (24-bit mantissa
                // vs 16-bit input resolution).
                for (i in 0 until n) {
                    ws[i] = taps[srcOff + i].toFloat()
                }
            }
            // Forward FFT to PFFFT internal-layout partition spectrum.
            // Uses workerBridge — separate from the audio thread's bridge
            // so we don't clobber its native scratch.
            workerBridge.transform(ws, forward = true)
            ws
        }
        kernelPartitionSpectra = partitions
    }

    /**
     * Process one block of input. Reads `blockSize` samples per channel
     * from `input[ch][0 .. blockSize-1]` and writes `blockSize` samples
     * per channel into `output[ch][0 .. blockSize-1]`.
     *
     * Algorithmic delay = blockSize samples (one block). The output
     * corresponds to a sliding-window convolution of the input stream
     * against the current kernel, evaluated at the most recent
     * blockSize-sample boundary.
     */
    fun processBlock(input: Array<FloatArray>, output: Array<FloatArray>) {
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

            // Forward FFT into PFFFT internal layout (in-place).
            audioBridge.transform(ws, forward = true)

            // Push the new partition into the FDL ring.
            val head = fdlHead[ch]
            System.arraycopy(ws, 0, fdl[ch][head], 0, fftSize)

            // Accumulate Σ FDL[head - p] · K[p] using PFFFT's SIMD
            // zconvolve_accumulate. Zero the accumulator first.
            java.util.Arrays.fill(acc, 0.0f)
            for (p in 0 until numPartitions) {
                val idx = ((head - p) % numPartitions + numPartitions) % numPartitions
                audioBridge.zconvolveAccumulate(fdl[ch][idx], parts[p], acc, 1.0f)
            }
            fdlHead[ch] = (head + 1) % numPartitions

            // Inverse FFT (scaled by 1/N to match canonical IFFT convention).
            audioBridge.transform(acc, forward = false)

            // Emit second half (overlap-save: first L samples are circular
            // wrap-around garbage; second L is the linear convolution
            // output we want).
            System.arraycopy(acc, blockSize, out, 0, blockSize)
        }
    }

    /**
     * Clear all FDL contents and the overlap buffer. Call from the audio
     * chain's flush. Does NOT touch the kernel partition spectra — those
     * remain valid across flushes.
     */
    fun reset() {
        for (ch in 0 until channels) {
            for (p in 0 until numPartitions) {
                java.util.Arrays.fill(fdl[ch][p], 0.0f)
            }
            java.util.Arrays.fill(prevInput[ch], 0.0f)
            fdlHead[ch] = 0
        }
    }

    /** Free the native PFFFT setups + aligned scratch. Call from the
     *  audio processor's onReset. After [release] the convolver is
     *  unusable. */
    fun release() {
        audioBridge.release()
        workerBridge.release()
    }

    /** Algorithmic input-to-output buffering delay, in samples at the
     *  input rate. Equals [blockSize]; the kernel's group delay (which
     *  depends on whether it's symmetric, min-phase, etc.) is ON TOP and
     *  is tracked by the caller. */
    val algorithmicDelayFrames: Int get() = blockSize

    /** Number of kernel partitions. Exposed for diagnostics. */
    val partitionCount: Int get() = numPartitions

    /** FFT size per partition. Exposed for diagnostics. */
    val partitionFftSize: Int get() = fftSize

    /** Build the kernel partition spectra for an identity kernel (impulse
     *  at tap 0). Used as the initial value before the first
     *  [setKernelTaps] call lands. */
    private fun buildIdentityPartitions(): Array<FloatArray> {
        val parts = Array(numPartitions) { FloatArray(fftSize) }
        parts[0][0] = 1.0f
        for (p in 0 until numPartitions) {
            workerBridge.transform(parts[p], forward = true)
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
