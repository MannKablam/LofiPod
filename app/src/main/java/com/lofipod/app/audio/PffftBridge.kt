package com.lofipod.app.audio

/**
 * JNI wrapper for the PFFFT FFT library (BSD-3-Clause, by Julien Pommier).
 *
 * Backs [UpcConvolver]'s forward/inverse real-FFT calls and the
 * multiply-accumulate inner loop of uniform partitioned convolution.
 * Replaces the JTransforms `DoubleFFT_1D` path used pre-v0.9.6.
 *
 * **Why PFFFT.** Pure-JVM JTransforms 3.1 takes 400-1800 µs for an
 * 8192-point real FFT on mid-range ARM cores; PFFFT with NEON SIMD runs
 * the same size in 100-400 µs. For LofiPod's UPC config (fftSize=2048),
 * the absolute time per FFT is smaller but the ratio still holds — and
 * we run 2 FFTs + 4 multiply-accumulates per block per channel, so the
 * speedup compounds. Net effect: substantially more thermal-throttle
 * headroom on the audio thread, especially at 2× playback.
 *
 * **Numeric precision.** PFFFT is single-precision (float). For 16-bit
 * PCM input, single-precision arithmetic in the FFT path is more than
 * sufficient — float has ~24 bits of mantissa, vs 16 bits of input
 * resolution. The min-phase cepstrum kernel synthesis stays in double
 * precision (off the audio thread, where the log/exp recipe benefits
 * from the extra bits) and converts down to float at the
 * [setKernelTaps] / [transform] boundary.
 *
 * **Threading & state.** One [PffftBridge] instance owns one native
 * PFFFT setup plus four 16-byte-aligned scratch buffers (allocated
 * inside the native lib via `pffft_aligned_malloc`). The bridge is NOT
 * thread-safe — callers (audio thread in our case) hold the instance
 * single-threaded. Multiple bridges with the same N value are fine
 * since each owns its own setup.
 *
 * **Lifecycle.** [configure] allocates the native setup; [release]
 * frees it. After [release] the bridge is unusable. Finalizers are NOT
 * used — explicit cleanup only, called from [UpcConvolver]'s reset
 * path. (Avoiding finalizers because they're deprecated and add
 * unpredictable GC pressure on the audio chain.)
 *
 * Attribution: PFFFT BSD-3-Clause license at
 * `app/src/main/assets/licenses/LICENSE-PFFFT.txt`.
 */
internal class PffftBridge {

    /** Native pointer to the LofipodFftContext struct (which owns the
     *  PFFFT_Setup * plus aligned scratch). 0 when unconfigured. */
    private var handle: Long = 0
    private var fftSize: Int = 0

    /** Allocate the native setup for an N-point real FFT. N must be a
     *  power-of-2 ≥ 32 (PFFFT requirement; UpcConvolver always passes
     *  2048). Idempotent against re-configure: an existing setup is
     *  freed first. */
    fun configure(n: Int) {
        if (handle != 0L) release()
        handle = nativeNewSetup(n)
        if (handle == 0L) {
            throw IllegalStateException("PFFFT setup failed for N=$n")
        }
        fftSize = n
    }

    /** Free the native setup. Safe to call multiple times; safe to call
     *  on an unconfigured bridge. */
    fun release() {
        if (handle != 0L) {
            nativeDestroySetup(handle)
            handle = 0
            fftSize = 0
        }
    }

    /**
     * In-place real-FFT. [inOut] holds N floats; on return it holds the
     * forward (or inverse) transform of the input in PFFFT's INTERNAL
     * layout (not the canonical packed-complex layout).
     *
     * Use [zconvolveAccumulate] directly on internal-layout spectra —
     * that's the fast path. If you need to read individual bins, call
     * [reorderToCanonical] to convert layouts.
     *
     * Inverse direction includes a 1/N scaling factor to match the
     * JTransforms `realInverse(spec, scale=true)` convention used
     * elsewhere in the codebase.
     */
    fun transform(inOut: FloatArray, forward: Boolean) {
        require(handle != 0L) { "PffftBridge not configured" }
        require(inOut.size == fftSize) {
            "transform: array size ${inOut.size} != fftSize $fftSize"
        }
        nativeTransform(handle, inOut, forward)
    }

    /**
     * `abAcc += a · b · scaling` in PFFFT internal-layout packed-complex
     * form. This is the canonical UPC inner loop — PFFFT implements it
     * with NEON SIMD, ~4 floats per cycle on arm64-v8a. All three arrays
     * are N floats long (the same N as the configure).
     */
    fun zconvolveAccumulate(
        a: FloatArray,
        b: FloatArray,
        abAcc: FloatArray,
        scaling: Float = 1.0f,
    ) {
        require(handle != 0L) { "PffftBridge not configured" }
        require(a.size == fftSize && b.size == fftSize && abAcc.size == fftSize) {
            "zconvolveAccumulate: array size mismatch (expected $fftSize)"
        }
        nativeZconvolveAccumulate(handle, a, b, abAcc, scaling)
    }

    /** Reorder a PFFFT internal-layout spectrum to canonical packed-
     *  complex layout (or back). Used for diagnostics / bin-by-bin
     *  inspection; not needed on the UPC hot path. */
    fun reorderToCanonical(spec: FloatArray) {
        require(handle != 0L) { "PffftBridge not configured" }
        nativeReorder(handle, spec, true)
    }

    fun reorderToInternal(spec: FloatArray) {
        require(handle != 0L) { "PffftBridge not configured" }
        nativeReorder(handle, spec, false)
    }

    private external fun nativeNewSetup(n: Int): Long
    private external fun nativeDestroySetup(handle: Long)
    private external fun nativeTransform(handle: Long, inOut: FloatArray, forward: Boolean)
    private external fun nativeZconvolveAccumulate(
        handle: Long,
        a: FloatArray,
        b: FloatArray,
        abAcc: FloatArray,
        scaling: Float,
    )
    private external fun nativeReorder(handle: Long, spec: FloatArray, toCanonical: Boolean)

    companion object {
        init {
            // Loads libloofipod_fft.so. The shared library is built by the
            // CMake project at app/src/main/cpp/CMakeLists.txt and packed
            // into the APK by AGP at lib/<abi>/libloofipod_fft.so.
            //
            // System.loadLibrary searches LD_LIBRARY_PATH (which on Android
            // includes the APK's lib/<abi> dir for the current device's
            // ABI). Failure here is unrecoverable — without PFFFT, FIR
            // modes can't run. We let the UnsatisfiedLinkError surface
            // so debug builds show a clear stacktrace rather than silently
            // falling back.
            System.loadLibrary("lofipod_fft")
        }
    }
}
