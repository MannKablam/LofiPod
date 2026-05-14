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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FIR equalizer wrapping [UpcConvolver] for the convolution and providing
 * two kernel-synthesis paths: **linear-phase** (symmetric impulse response,
 * preserves transient waveform shape, ~46 ms group delay) and **min-phase**
 * (cepstrum-derived, energy front-loaded, no pre-ringing, sub-ms group delay).
 *
 * Replaces v0.8.0's `LinearPhaseEq` (monolithic 8192-pt OLA, linear-phase
 * only). The convolution engine (UPC) is shared across FIR modes — the
 * [Mode] dispatch only affects how the kernel taps are synthesized.
 *
 * **Why min-phase matters for speech.** The default biquad cascade is
 * minimum-phase by construction, but its frequency-response shape is
 * controlled by the Q + center of each band — high-Q peaks bend the magnitude
 * response in ways that differ subtly from a true magnitude-target FIR
 * design. The cepstrum path here builds the same TARGET magnitude response
 * (sampled from the biquad cascade) and folds it into a causal min-phase
 * impulse response via the real-cepstrum recipe (Mian & Nainer 1982 /
 * Oppenheim-Schafer §10.5). Result: surgical magnitude precision + zero
 * pre-ringing + ms-scale group delay across all frequencies.
 *
 * **Threading & allocation:** synthesis runs on [Dispatchers.Default];
 * the audio thread allocates nothing in [pushFrame] / [popFrame]. New
 * kernel taps are published into UPC via a single `@Volatile` reference
 * swap (inside [UpcConvolver.setKernelTaps]).
 */
class FirEq {

    /**
     * FIR phase mode. Determines which kernel-synthesis path produces
     * the impulse response handed to [UpcConvolver.setKernelTaps].
     */
    enum class Mode {
        /** Symmetric impulse response. Constant ~46 ms group delay.
         *  Preserves transient waveform shape exactly. Has audible
         *  pre-ringing on sharp transients (the famous "cough that
         *  precedes itself"). */
        LINEAR,
        /** Causal min-phase impulse response derived from the same target
         *  magnitude via real-cepstrum. Energy front-loaded → no
         *  pre-ringing; sub-millisecond group delay across all frequencies
         *  at the Qs LofiPod uses (0.7-1.4). Best for spoken content. */
        MIN_PHASE,
        /** Hybrid: min-phase below the crossover frequency
         *  ([MIXED_CROSSOVER_HZ]), linear-phase above. The two contributions
         *  are pre-multiplied by complementary real-valued crossover
         *  filters (sum to 1 in magnitude, so the EQ shape is preserved
         *  everywhere), synthesized separately, time-aligned by delaying
         *  the min-phase contribution to match the linear-phase group
         *  delay, then summed. Same latency as [LINEAR]. Mastering-style
         *  approach — pairs the academic-pure linear-phase response at
         *  speech-band frequencies with the no-pre-ringing min-phase
         *  response in the bass where pre-ringing is most audible
         *  (kicks, organ pedal, low rumble). FabFilter Pro-Q3 "Natural
         *  Phase" / DMG EQuilibrium territory. */
        MIXED,
    }

    @Volatile var mode: Mode = Mode.LINEAR
        private set

    private var sampleRate = 0
    private var channelCount = 0

    private var convolver: UpcConvolver? = null

    // Per-channel input accumulator: fills with input frame-by-frame until
    // BLOCK_SIZE is reached, then triggers UPC processBlock(). Mirrors
    // the v0.8.0 pre-UPC accumulator pattern.
    private var inputAccum: Array<DoubleArray> = emptyArray()
    private var inputAccumPos: IntArray = IntArray(0)

    // Per-channel block scratch for UPC input / output. Float-typed
    // because v0.9.6's UpcConvolver runs single-precision internally
    // (backed by PFFFT, which is float-only). Conversion happens here at
    // the FirEq ↔ UpcConvolver boundary: input accumulators stay Double
    // (matching the rest of the DSP chain) and convert into blockIn;
    // UPC output comes back in blockOut as Float and converts back to
    // Double when pushed into outputQueue.
    private var blockIn: Array<FloatArray> = emptyArray()
    private var blockOut: Array<FloatArray> = emptyArray()

    // Per-channel output ring buffer. Primitive DoubleArray-backed; no
    // autoboxing on push/pop. Capacity sized for several blocks of slack.
    private var outputQueue: Array<DoubleRing> = emptyArray()

    // Synthesis worker scope. One supervised job at a time; rapid slider
    // drags cancel the in-flight synthesis before starting a new one.
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null

    fun configure(rate: Int, channels: Int) {
        sampleRate = rate
        channelCount = channels
        convolver = UpcConvolver(channels = channels)
        inputAccum = Array(channels) { DoubleArray(BLOCK_SIZE) }
        inputAccumPos = IntArray(channels)
        blockIn = Array(channels) { FloatArray(BLOCK_SIZE) }
        blockOut = Array(channels) { FloatArray(BLOCK_SIZE) }
        outputQueue = Array(channels) { DoubleRing(OUTPUT_RING_CAPACITY) }
        // Initial kernel = FLAT. UpcConvolver defaults to identity already,
        // but synthesize an explicit FLAT-symmetric kernel here so the
        // convolver is in a well-defined linear-phase state matching the
        // first user EQ edit's eventual response.
        setBands(EqPresets.FLAT)
    }

    /**
     * Switch FIR phase mode. Re-synthesizes the kernel against the current
     * bands using the new mode's synthesis path. Cheap to call even with
     * the same mode — no-ops at the equality check.
     */
    fun setMode(newMode: Mode, currentBands: List<EqBand>) {
        if (mode == newMode) return
        mode = newMode
        setBands(currentBands)
    }

    /**
     * Trigger asynchronous kernel re-synthesis. Cancels any in-flight job.
     * The new kernel is published to UPC via [UpcConvolver.setKernelTaps],
     * which is itself a `@Volatile` reference swap; the audio thread picks
     * it up on its next [processBlock] call.
     */
    fun setBands(bands: List<EqBand>) {
        synthJob?.cancel()
        synthJob = workerScope.launch {
            synthesizeKernelSync(bands)
        }
    }

    /** Reset the convolver's FDL + overlap state + input accumulators +
     *  output queue. Call from the chain's flush. Does NOT discard the
     *  current kernel — convolver retains its partition spectra. */
    fun reset() {
        for (ch in 0 until channelCount) {
            inputAccumPos[ch] = 0
            java.util.Arrays.fill(inputAccum[ch], 0.0)
            outputQueue[ch].clear()
        }
        convolver?.reset()
    }

    /** Free the synthesis worker scope. Call from the chain's reset
     *  (processor lifecycle teardown). */
    fun release() {
        workerScope.cancel()
    }

    fun pushFrame(input: DoubleArray) {
        for (ch in 0 until channelCount) {
            inputAccum[ch][inputAccumPos[ch]] = input[ch]
            inputAccumPos[ch]++
            if (inputAccumPos[ch] == BLOCK_SIZE) {
                processBlockForChannel(ch)
                inputAccumPos[ch] = 0
            }
        }
    }

    /** Pop one frame of output (one sample per channel). Caller must have
     *  verified [outputFramesAvailable] >= 1. */
    fun popFrame(output: DoubleArray) {
        for (ch in 0 until channelCount) {
            output[ch] = outputQueue[ch].pop()
        }
    }

    /** Number of complete output frames ready to pop. */
    fun outputFramesAvailable(): Int =
        if (outputQueue.isNotEmpty()) outputQueue[0].size else 0

    /**
     * Algorithmic chain latency in samples at the input rate. Includes
     * UPC's block-buffering delay plus the kernel's effective group delay.
     *
     * - Linear mode: BLOCK_SIZE (block delay) + (KERNEL_LENGTH - 1)/2
     *   (symmetric kernel center) ≈ 1024 + 2047 = 3071 samples ≈ 69.6 ms
     *   at 44.1k. **Note:** this is higher than the brief's §F target of
     *   ~46 ms because that figure assumed a different chain architecture
     *   (the brief later notes UPC pairs naturally with min-phase, where
     *   the group delay collapses). For LofiPod the linear-phase mode
     *   stays the academically-pure heaviest-latency option.
     * - Min-phase mode: BLOCK_SIZE (block delay) only ≈ 23 ms at 44.1k.
     *   The cepstrum kernel is causal energy-front-loaded; group delay
     *   is sub-ms across the audio band at typical EQ Qs.
     */
    val algorithmicDelayFrames1x: Int
        get() = when (mode) {
            Mode.LINEAR -> BLOCK_SIZE + (KERNEL_LENGTH - 1) / 2
            Mode.MIN_PHASE -> BLOCK_SIZE
            // MIXED aligns the min-phase contribution to the linear-phase
            // group delay so both arrive at the listener simultaneously
            // through the convolution. Same latency as LINEAR.
            Mode.MIXED -> BLOCK_SIZE + (KERNEL_LENGTH - 1) / 2
        }

    private fun processBlockForChannel(ch: Int) {
        // The UPC convolver processes all channels in one call, but our
        // input accumulators advance per-channel as queueInput pushes
        // frames. So we trigger the block when ALL channels' accumulators
        // are simultaneously full — which is guaranteed by the frame-
        // interleaved pushFrame pattern: each channel ticks in lockstep
        // because pushFrame iterates 0..channelCount-1 once per frame.
        //
        // When ch=channelCount-1 triggers, all earlier channels have
        // also just hit BLOCK_SIZE in this same pushFrame call. Build
        // the per-channel input arrays from accumulators and emit.
        if (ch != channelCount - 1) return  // wait until the last channel ticks

        val conv = convolver ?: return
        // Double → Float conversion at the UPC boundary. v0.9.6+ UPC runs
        // single-precision internally (PFFFT backend).
        for (i in 0 until channelCount) {
            val src = inputAccum[i]
            val dst = blockIn[i]
            for (n in 0 until BLOCK_SIZE) dst[n] = src[n].toFloat()
        }
        conv.processBlock(blockIn, blockOut)
        // Float → Double conversion back to the rest of the DSP chain
        // (post-gain → oversampler → limiter, all still Double).
        for (i in 0 until channelCount) {
            val q = outputQueue[i]
            val out = blockOut[i]
            for (n in 0 until BLOCK_SIZE) q.push(out[n].toDouble())
        }
    }

    /**
     * Synthesize a kernel from the biquad cascade's magnitude response,
     * via the [mode]-appropriate path. Hands the resulting time-domain
     * taps to [UpcConvolver.setKernelTaps] which re-partitions + FFTs them.
     * Worker thread only — allocations OK here.
     */
    private fun synthesizeKernelSync(bands: List<EqBand>) {
        if (sampleRate == 0) return
        val conv = convolver ?: return

        // Step 1: target magnitude response, sampled at FFT_SIZE/2 + 1
        // frequency points. FFT_SIZE here = KERNEL_LENGTH * 2 = 8192;
        // ample resolution for the EQ shape regardless of mode.
        val fftSize = KERNEL_LENGTH * 2
        val mag = DoubleArray(fftSize / 2 + 1)
        for (k in mag.indices) {
            val w = 2.0 * PI * k / fftSize
            var magSquared = 1.0
            for (band in bands) {
                magSquared *= biquadMagSquared(band, w, sampleRate)
            }
            mag[k] = sqrt(magSquared)
        }

        val taps = when (mode) {
            Mode.LINEAR -> synthLinearPhaseKernel(mag, fftSize)
            Mode.MIN_PHASE -> synthMinPhaseKernel(mag, fftSize)
            Mode.MIXED -> synthMixedPhaseKernel(mag, fftSize)
        }
        conv.setKernelTaps(taps)
    }

    /**
     * Mixed-phase kernel synthesis.
     *
     * Splits the target magnitude response at a 120 Hz crossover via a
     * complementary cosine-ramped filter pair (`H_low + H_high = 1`
     * everywhere, so the EQ shape is preserved with no transition-band
     * dip). Synthesizes:
     *   - a linear-phase kernel from `mag · H_high` (above-crossover
     *     content gets the symmetric-impulse / transient-preserving path)
     *   - a min-phase kernel from `mag · H_low` (below-crossover content
     *     gets the energy-front-loaded / no-pre-ringing path)
     *
     * Then time-aligns the two contributions: the linear-phase kernel
     * peaks at index KERNEL_LENGTH/2 (its natural group delay), the
     * min-phase kernel peaks at index 0 (causal energy front-load). To
     * deliver both contributions to the listener simultaneously, the
     * min-phase kernel is shifted forward by KERNEL_LENGTH/2 samples,
     * then summed.
     *
     * Result: a single hybrid impulse response of length KERNEL_LENGTH
     * with peaks aligned at the center, delivering linear-phase magnitude
     * shaping above 120 Hz and min-phase shaping below. Same convolution
     * complexity as either pure mode — UPC sees just another 4096-tap
     * kernel.
     *
     * Why this works: the per-sample output through this kernel is the
     * sum of two parallel convolution paths (one per phase mode applied
     * to a complementary band of the input). The complementary crossover
     * preserves the total magnitude target; the linear/min-phase split
     * trades pre-ringing where it matters most.
     */
    private fun synthMixedPhaseKernel(mag: DoubleArray, fftSize: Int): DoubleArray {
        // Step 1: build complementary crossover masks. Half-cosine ramp
        // from MIXED_CROSSOVER_LO_HZ to MIXED_CROSSOVER_HI_HZ; flat 0/1
        // outside that band. Real-valued, sums to 1 → EQ magnitude
        // preserved everywhere.
        val nBins = mag.size                   // fftSize / 2 + 1
        val hLow = DoubleArray(nBins)
        val hHigh = DoubleArray(nBins)
        for (k in 0 until nBins) {
            val freqHz = k.toDouble() * sampleRate / fftSize
            hLow[k] = crossoverLow(freqHz)
            hHigh[k] = 1.0 - hLow[k]
        }

        // Step 2: derive band-limited magnitudes for each phase path.
        val magHigh = DoubleArray(nBins) { k -> mag[k] * hHigh[k] }
        val magLow = DoubleArray(nBins) { k -> mag[k] * hLow[k] }

        // Step 3: synthesize each kernel. synthLinearPhaseKernel and
        // synthMinPhaseKernel already return KERNEL_LENGTH-length
        // time-domain taps.
        val kernelLinHigh = synthLinearPhaseKernel(magHigh, fftSize)
        val kernelMinLow = synthMinPhaseKernel(magLow, fftSize)

        // Step 4: align contributions. Linear-phase kernel peaks at
        // KERNEL_LENGTH/2 (causal-symmetric, K/2 group delay). Min-phase
        // kernel peaks at index 0 (energy-front-loaded, ~0 group delay).
        // To deliver both to the listener simultaneously, shift the
        // min-phase contribution forward by K/2 samples. The min-phase
        // tail past K samples is truncated (its energy at that point is
        // negligible — Kaiser-windowed real-cepstrum kernels decay to
        // -60 dB well within their truncation window).
        val half = KERNEL_LENGTH / 2
        val kernel = DoubleArray(KERNEL_LENGTH)
        for (i in 0 until KERNEL_LENGTH) {
            kernel[i] = kernelLinHigh[i]
        }
        for (i in 0 until KERNEL_LENGTH - half) {
            kernel[half + i] += kernelMinLow[i]
        }
        return kernel
    }

    /** Half-cosine crossover lowpass response at [freqHz]. Returns 1.0
     *  below [MIXED_CROSSOVER_LO_HZ], 0.0 above [MIXED_CROSSOVER_HI_HZ],
     *  smooth cos² transition between. Complementary highpass is
     *  `1 - crossoverLow`, so the pair sums to 1.0 everywhere. */
    private fun crossoverLow(freqHz: Double): Double {
        if (freqHz <= MIXED_CROSSOVER_LO_HZ) return 1.0
        if (freqHz >= MIXED_CROSSOVER_HI_HZ) return 0.0
        val t = (freqHz - MIXED_CROSSOVER_LO_HZ) /
                (MIXED_CROSSOVER_HI_HZ - MIXED_CROSSOVER_LO_HZ)
        // cos² ramp: smooth 1 → 0 over the transition band.
        val c = cos(PI * t * 0.5)
        return c * c
    }

    /**
     * Linear-phase kernel synthesis. Same recipe as v0.8.0's pre-UPC
     * linear-phase EQ: build a zero-phase spectrum from the magnitude
     * response, IFFT to symmetric time-domain impulse, circular shift,
     * truncate to KERNEL_LENGTH taps centered on the peak, Kaiser window.
     */
    private fun synthLinearPhaseKernel(mag: DoubleArray, fftSize: Int): DoubleArray {
        val spec = DoubleArray(fftSize)
        spec[0] = mag[0]
        spec[1] = mag[fftSize / 2]
        for (k in 1 until fftSize / 2) {
            spec[2 * k] = mag[k]
            spec[2 * k + 1] = 0.0
        }
        val fft = DoubleFFT_1D(fftSize.toLong())
        fft.realInverse(spec, true)

        // Circular shift by fftSize/2 to move the peak from index 0 to
        // index fftSize/2; the resulting kernel is causal-symmetric.
        val shifted = DoubleArray(fftSize)
        val half = fftSize / 2
        for (i in 0 until fftSize) {
            shifted[i] = spec[(i + half) % fftSize]
        }

        // Truncate to KERNEL_LENGTH taps centered on the impulse peak,
        // multiply by precomputed Kaiser window for ripple suppression.
        val kernel = DoubleArray(KERNEL_LENGTH)
        val start = half - KERNEL_LENGTH / 2
        for (i in 0 until KERNEL_LENGTH) {
            kernel[i] = shifted[start + i] * KAISER_WINDOW[i]
        }
        return kernel
    }

    /**
     * Real-cepstrum min-phase kernel synthesis. From the same target
     * magnitude response, derive a causal impulse response with the same
     * |H(jω)| but all energy folded into positive time.
     *
     * Recipe (after Mian & Nainer 1982 / Oppenheim-Schafer §10.5):
     *
     *   1. A[k] = ln|H_target[k]|  (with ε floor to avoid log 0)
     *   2. c_real = IFFT(A)        — real cepstrum
     *   3. Fold: w[0] = w[N/2] = 1, w[1..N/2-1] = 2, w[N/2+1..N-1] = 0
     *      c_min[n] = c_real[n] * w[n]
     *   4. min_log_spec = FFT(c_min)  — complex log spectrum, min-phase
     *   5. min_spec = exp(min_log_spec)  — bin-by-bin |·|·exp(jφ)
     *   6. h_min = IFFT(min_spec)  — causal min-phase impulse response
     *   7. Truncate to KERNEL_LENGTH (energy front-loaded; truncation
     *      error tiny). Optional Kaiser window for cleanup.
     *
     * No phase-unwrap step: the real-cepstrum recipe sidesteps it.
     */
    private fun synthMinPhaseKernel(mag: DoubleArray, fftSize: Int): DoubleArray {
        val eps = 1e-12
        val fft = DoubleFFT_1D(fftSize.toLong())

        // Step 1+2: log-magnitude in packed real form, IFFT to real cepstrum.
        val logSpec = DoubleArray(fftSize)
        logSpec[0] = ln(max(mag[0], eps))                // DC
        logSpec[1] = ln(max(mag[fftSize / 2], eps))      // Nyquist
        for (k in 1 until fftSize / 2) {
            logSpec[2 * k]     = ln(max(mag[k], eps))
            logSpec[2 * k + 1] = 0.0
        }
        fft.realInverse(logSpec, true)
        val cReal = logSpec  // alias for readability

        // Step 3: fold cepstrum into causal-min form.
        val cMin = DoubleArray(fftSize)
        cMin[0] = cReal[0]
        cMin[fftSize / 2] = cReal[fftSize / 2]
        for (n in 1 until fftSize / 2) {
            cMin[n] = 2.0 * cReal[n]
        }
        // cMin[fftSize/2 + 1 .. fftSize - 1] stays zero.

        // Step 4: FFT of folded cepstrum = complex log of min-phase spectrum
        // (packed real form: spec[2k]=Re, spec[2k+1]=Im of the log-spectrum bins).
        fft.realForward(cMin)
        val minLogSpec = cMin  // alias

        // Step 5: exponentiate bin-by-bin in packed-complex form.
        // For DC and Nyquist (real bins), exp of a real log gives the real
        // magnitude back (no imaginary part — the min-phase spectrum is
        // purely real at DC/Nyquist because |H(0)| and |H(Nyq)| are real
        // and phase is 0/π modulo discrete sign).
        val minSpec = DoubleArray(fftSize)
        minSpec[0] = exp(minLogSpec[0])
        minSpec[1] = exp(minLogSpec[1])
        for (k in 1 until fftSize / 2) {
            val re = minLogSpec[2 * k]
            val im = minLogSpec[2 * k + 1]
            val m = exp(re)
            minSpec[2 * k]     = m * cos(im)
            minSpec[2 * k + 1] = m * sin(im)
        }

        // Step 6: IFFT to causal time-domain impulse response.
        fft.realInverse(minSpec, true)

        // Step 7: truncate to KERNEL_LENGTH (energy is front-loaded so
        // truncation drops the negligible tail). NO circular shift — the
        // min-phase IR is already causal at index 0 in the IFFT output.
        //
        // **CRITICAL**: NO Kaiser window here. The symmetric Kaiser
        // window has its maximum at the CENTER of KERNEL_LENGTH and tapers
        // to ~0.015 at both edges (β=6). For the linear-phase kernel,
        // the impulse energy is at the center, so the window's max value
        // multiplies the dominant taps — gain ≈ 1. For the min-phase
        // kernel, energy is at the START of KERNEL_LENGTH (causal,
        // front-loaded), so the window's MIN value multiplies the dominant
        // taps — this was a -36 dB attenuation bug shipped in v0.9.3
        // through v0.10.0 that surfaced as "Min FIR ~5% of original volume"
        // in real-world testing (v0.10.1 fix). The cepstrum kernel
        // naturally tapers to near-zero in its tail; truncating at
        // KERNEL_LENGTH without windowing introduces a tiny step
        // discontinuity well below audibility (< -80 dB at typical Qs).
        val kernel = DoubleArray(KERNEL_LENGTH)
        System.arraycopy(minSpec, 0, kernel, 0, KERNEL_LENGTH)
        return kernel
    }

    /**
     * Primitive-double ring buffer. Power-of-two capacity, mask-indexed.
     * No autoboxing — replaces `ArrayDeque<Double>` patterns on the audio
     * thread. Originally introduced in v0.9.0 to fix the autobox-GC
     * symptom; hoisted here in v0.9.3 alongside the UPC convolver rebuild.
     */
    internal class DoubleRing(capacityPow2: Int) {
        init {
            require(capacityPow2 > 0 && (capacityPow2 and (capacityPow2 - 1)) == 0) {
                "DoubleRing capacity must be a positive power of two; got $capacityPow2"
            }
        }
        private val buf = DoubleArray(capacityPow2)
        private val mask = capacityPow2 - 1
        private var head = 0
        private var tail = 0
        var size: Int = 0
            private set

        fun push(v: Double) {
            buf[tail] = v
            tail = (tail + 1) and mask
            size++
        }

        fun pop(): Double {
            val v = buf[head]
            head = (head + 1) and mask
            size--
            return v
        }

        fun clear() {
            head = 0
            tail = 0
            size = 0
        }
    }

    companion object {
        /** Block size for the UPC convolver. Matches [UpcConvolver.DEFAULT_BLOCK_SIZE]. */
        const val BLOCK_SIZE = UpcConvolver.DEFAULT_BLOCK_SIZE
        /** FIR kernel length. Matches [UpcConvolver.DEFAULT_KERNEL_LEN]. */
        const val KERNEL_LENGTH = UpcConvolver.DEFAULT_KERNEL_LEN

        /** Output ring capacity per channel (samples). Power of two ≥ 4×BLOCK_SIZE. */
        private const val OUTPUT_RING_CAPACITY = 8192

        /** Kaiser-window β=6 → ~60 dB ripple suppression. Same as v0.8.0. */
        private const val KAISER_BETA = 6.0

        /**
         * Mixed-phase crossover transition band, in Hz.
         *
         * Below [MIXED_CROSSOVER_LO_HZ]: full weight on the min-phase
         * (energy-front-loaded) synthesis path — no pre-ringing on bass
         * transients (kicks, organ pedal, low rumble).
         *
         * Above [MIXED_CROSSOVER_HI_HZ]: full weight on the linear-phase
         * (symmetric impulse) synthesis path — exact transient waveform
         * preservation across the speech / music band.
         *
         * Between the two: half-cosine ramp, complementary so the two
         * weights sum to 1.0 in magnitude and the EQ shape is preserved
         * everywhere (no transition-band dip).
         *
         * 120 Hz is the brief's suggestion (FabFilter Pro-Q3 "Natural
         * Phase" uses ~150 Hz; DMG EQuilibrium ~120 Hz). 80→180 Hz gives
         * a ~1-octave smooth transition centered roughly at 120 Hz.
         */
        private const val MIXED_CROSSOVER_LO_HZ = 80.0
        private const val MIXED_CROSSOVER_HI_HZ = 180.0

        /** Symmetric Kaiser window of length [KERNEL_LENGTH], precomputed once at class load. */
        private val KAISER_WINDOW: DoubleArray = run {
            val w = DoubleArray(KERNEL_LENGTH)
            val betaI0 = besselI0(KAISER_BETA)
            for (n in 0 until KERNEL_LENGTH) {
                val ratio = 2.0 * n / (KERNEL_LENGTH - 1) - 1.0
                w[n] = besselI0(KAISER_BETA * sqrt(1.0 - ratio * ratio)) / betaI0
            }
            w
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-15) {
                val r = x / (2.0 * k)
                term *= r * r
                sum += term
                k++
                if (k > 200) break
            }
            return sum
        }

        /**
         * Squared magnitude response of one peaking biquad at normalized
         * angular frequency [w] (= 2π·f/fs). Same closed form as v0.8.0
         * pre-UPC LinearPhaseEq.biquadMagSquared; pulled into the companion so
         * both LINEAR and MIN_PHASE synth paths can use it without
         * duplication.
         */
        private fun biquadMagSquared(band: EqBand, w: Double, sampleRate: Int): Double {
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
    }
}
