package com.lofipod.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Real-time audiophile audio processor — full Phase A signal chain.
 *
 *   int16 PCM in
 *     -> Float64                          (zero-error int → double conversion)
 *     -> [DC blocker]                     (toggleable, off by default)
 *     -> per-channel biquad chain         (cross-fade in parallel on band change)
 *     -> master gain
 *     -> 2x polyphase upsample            (anti-imaging FIR)
 *     -> look-ahead brick-wall limiter    (linked-stereo, ~5 ms LA at 1x, soft knee, runs at 2x)
 *     -> 2x polyphase downsample          (anti-aliasing FIR)
 *     -> [TPDF dither when limiter engaged]
 *     -> int16 PCM out
 *
 * Operates on 16-bit PCM (the most common output of decoders going into
 * ExoPlayer's audio sink). For other encodings, we throw
 * UnhandledAudioFormatException and Media3 falls back to passthrough.
 *
 * Settings can be changed live; coefficient updates take effect on the next
 * buffer with a 2048-sample equal-power cross-fade so the user can drag EQ
 * sliders without clicks.
 *
 * Total chain latency: ~6.4 ms (5 ms limiter LA + ~1.4 ms FIR group delay
 * across both oversampling stages). Inaudible. CPU footprint: a few percent
 * of one core for stereo at 44.1k.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    // ---- Settings (volatile so UI thread changes are seen by the audio thread) ----
    @Volatile private var bands: List<EqBand> = EqPresets.FLAT
    @Volatile private var gainDb: Float = 0f          // volume boost, 0..+12 dB typical
    @Volatile private var enabled: Boolean = true
    @Volatile private var dcBlocker: Boolean = false  // pre-EQ DC removal; off by default
    // When true, the EQ stage runs as a 4096-tap linear-phase FIR convolution
    // (Phase C). When false (default), it runs as the 10-band minimum-phase
    // biquad cascade (Phase A). The downstream chain (DC blocker, gain,
    // oversampler, limiter, dither) is identical in both modes — only the EQ
    // stage swaps.
    @Volatile private var phaseModeLinear: Boolean = false
    @Volatile private var dirty: Boolean = true       // recompute coefficients on next buffer

    // ---- Internal DSP state ----
    private var sampleRate = 0
    private var channelCount = 0
    // filters[channel][band] — the LIVE chain. After a band change, this
    // gets the new coefficients; the previous coefficients + state get
    // copied into [oldFilters] for the duration of the cross-fade.
    private var filters: Array<Array<Biquad>> = emptyArray()
    // oldFilters[channel][band] — runs in parallel with [filters] during a
    // cross-fade window after any band change. Output is mixed:
    //   out = oldChain(x) * w_old + newChain(x) * w_new
    // where w_new ramps 0→1 over [fadeRemaining] samples via half-cosine
    // (equal-power crossfade, smooth derivative at both ends).
    // Why not interpolate biquad coefficients directly: poles can briefly
    // leave the unit circle during interpolation = momentary instability =
    // pop. Running two stable filters in parallel and mixing their outputs
    // is bulletproof; ~2x CPU during the fade only (46 ms), zero rest of
    // the time.
    private var oldFilters: Array<Array<Biquad>> = emptyArray()
    // Sample countdown for the fade. >0 means we're mid-fade; 0 means use
    // [filters] only. Decremented once per FRAME (not per channel sample).
    private var fadeRemaining: Int = 0
    // True after the first ensureCoefficients call lands. We don't want a
    // cross-fade on the very first configure — there's nothing to fade
    // FROM, and a 46 ms ramp from "raw" to "EQ'd" at track start is a
    // pointless artifact.
    private var hasInitialized: Boolean = false
    // dcBlockers[channel] — one DcBlocker per channel; each carries its own
    // x_prev/y_prev. Allocated in onConfigure once channelCount is known.
    private var dcBlockers: Array<DcBlocker> = emptyArray()
    // Single TPDF generator for the Double → int16 truncation at the chain
    // output. Signal-independent noise (TPDF property) means we can share
    // one instance across channels without channel-correlation artifacts.
    // Only used in the DSP path — the passthrough fast-path is bit-identical
    // and adding dither there would be a noise-floor regression.
    private val dither = Dither()
    // Look-ahead brick-wall limiter. Replaces the tanh stopgap that lived in
    // the inner loop pre-Phase A5 — see Limiter.kt for spec. Single instance
    // covers all channels; gain reduction is linked stereo (max across
    // channels) so the stereo image is preserved through compression.
    // Phase A6: configured at 2× the input sample rate so it operates inside
    // the oversampling envelope. Aliasing from gain modulation is filtered
    // out by the [oversampler] downsample stage.
    private val limiter = Limiter()
    // 2x polyphase oversampler that wraps the limiter. EQ is linear and
    // doesn't need oversampling; the limiter is time-varying gain (effectively
    // a multiplication, which spreads the spectrum) so it does. See
    // Oversampler.kt for the FIR design and polyphase math.
    private val oversampler = Oversampler()
    // Linear-phase EQ via FFT overlap-add convolution. Used only when
    // [phaseModeLinear] is true. Owns its own worker scope for kernel
    // synthesis on band changes; reset on flush; released on processor reset.
    // See LinearPhaseEq.kt for the kernel synthesis + convolution math.
    private val linearPhaseEq = LinearPhaseEq()
    // Scratch arrays sized to channelCount, allocated on configure. Used to
    // hand whole frames between stages: 1x-rate arrays for input/output,
    // plus two 2x-rate arrays for the upsampler→limiter→downsampler bridge
    // (the upsampler emits two 2x frames per 1x input; the limiter processes
    // each in turn; the downsampler combines them back). Reused every frame —
    // allocation only on configure, never in the hot loop.
    private var frameInput: DoubleArray = DoubleArray(0)
    private var frameOutput: DoubleArray = DoubleArray(0)
    private var up0Frame: DoubleArray = DoubleArray(0)
    private var up1Frame: DoubleArray = DoubleArray(0)
    private var lim0Frame: DoubleArray = DoubleArray(0)
    private var lim1Frame: DoubleArray = DoubleArray(0)
    // Scratch for popping linear-phase EQ output frames into per-channel
    // doubles before they enter the gain → oversample → limiter chain.
    // Sized at configure to channelCount; reused every frame.
    private var linearPhasePop: DoubleArray = DoubleArray(0)

    fun setBands(newBands: List<EqBand>) {
        bands = newBands
        dirty = true
        // Linear-phase EQ kicks off async kernel re-synthesis on its worker
        // scope. Cheap to call even when phase mode is min — the worker just
        // produces a kernel that goes unused until the user toggles modes.
        linearPhaseEq.setBands(newBands)
        // Counter increments per call; the actual cross-fade only fires inside
        // ensureCoefficients (where we also log the breadcrumb event). Cheap
        // enough to call from the UI thread on every slider tick.
        AudioChainTelemetry.incBandChanges()
    }
    fun setGainDb(db: Float) { gainDb = db.coerceIn(-12f, 12f) }
    fun setEnabled(on: Boolean) {
        enabled = on
        AudioChainTelemetry.enabled = on
    }
    fun setDcBlockerEnabled(on: Boolean) {
        dcBlocker = on
        AudioChainTelemetry.dcBlockerEnabled = on
        AudioChainTelemetry.logEvent("dc_blocker", if (on) "on" else "off")
    }
    /**
     * Switch the EQ stage between minimum-phase biquad cascade (false,
     * default) and linear-phase FIR convolution (true). The mode change
     * takes effect on the next audio buffer; mid-buffer audio in flight
     * through the previous mode is not cross-faded — there will be a brief
     * (< 50 ms) audible artifact at the transition. Acceptable for a manual
     * mode switch; could be smoothed with a parallel cross-fade later.
     */
    fun setPhaseModeLinear(on: Boolean) {
        if (phaseModeLinear == on) return
        phaseModeLinear = on
        // Reset the full chain on a phase-mode toggle. The linear-phase EQ has
        // its own buffered state, but the post-EQ stages (limiter look-ahead,
        // oversampler delay lines, biquad cross-fade state) also hold the
        // previous mode's audio; failing to clear them produces a brief level
        // burst or click at the moment of switching. See
        // _LOFIPOD_V1_BRIEF.md §A8.
        chainReset()
        AudioChainTelemetry.logEvent("phase_mode", if (on) "linear" else "minimum")
    }

    /**
     * Reset every stateful DSP stage in the chain (DC blocker, biquad cascade
     * + cross-fade state, linear-phase EQ accumulators/OLA tail/output ring,
     * oversampler FIR delay lines, look-ahead limiter buffer + peak window).
     * Called from [onFlush] (seek / setMediaItem) and from
     * [setPhaseModeLinear] (mid-playback mode toggle). Idempotent and cheap;
     * does not free or reallocate any buffers.
     */
    private fun chainReset() {
        for (ch in filters) for (b in ch) b.reset()
        for (ch in oldFilters) for (b in ch) b.reset()
        for (b in dcBlockers) b.reset()
        limiter.reset()
        oversampler.reset()
        linearPhaseEq.reset()
        fadeRemaining = 0
    }

    /** Read-only accessor for the current phase mode. Used by the in-Player
     *  diagnostics tab to surface "Linear (4096-tap FIR)" vs "Minimum
     *  (biquad)" without needing a Settings round-trip on every recompose. */
    fun isPhaseModeLinear(): Boolean = phaseModeLinear

    /**
     * Total chain latency from input to audible output, in microseconds.
     *
     *   - Linear-phase FIR: (KERNEL_LENGTH-1)/2 samples ≈ 46.4 ms at 44.1k.
     *     Only contributes when the linear-phase path is active. Always 0
     *     in v0.9.0+ since the chip is hidden and PlaybackService forces
     *     `phaseModeLinear = false` regardless of the saved pref. Returns
     *     to non-zero in v0.9.4 when the rebuilt linear chip ships.
     *   - Oversampler FIR group delay (both up + down stages combined):
     *     `totalDelayFrames1x` samples at 1× rate, ≈ 1.4 ms at 44.1k.
     *   - Limiter look-ahead: `drainFrameCount/2` 1×-equiv samples, ≈ 5 ms.
     *
     * Returns 0 when the chain is disabled or in effective passthrough —
     * those paths copy bytes through untouched, no latency contribution.
     *
     * Used by [PlayerController.currentPositionMs] to subtract the chain's
     * algorithmic delay from the position reported to the UI, so the
     * progress bar tracks what the user is actually hearing rather than
     * what the audio sink last wrote to AudioTrack. Watchdog and DB
     * persistence intentionally stay on raw position. See
     * _LOFIPOD_V1_BRIEF.md §A4.
     */
    fun getChainLatencyUs(): Long {
        if (sampleRate == 0) return 0L
        if (!enabled || isPassthroughEffective()) return 0L
        val linUs = if (phaseModeLinear) {
            ((LinearPhaseEq.KERNEL_LENGTH - 1).toLong() / 2) * 1_000_000L / sampleRate
        } else 0L
        val osUs = oversampler.totalDelayFrames1x.toLong() * 1_000_000L / sampleRate
        val limUs = (limiter.drainFrameCount.toLong() / 2L) * 1_000_000L / sampleRate
        return linUs + osUs + limUs
    }

    /**
     * True when the chain would have no audible effect — every band is at 0 dB,
     * no global gain, AND the DC blocker is off. Cheap O(bands) check run per
     * buffer; the volatile reads are fine since the audio thread re-checks
     * each call.
     *
     * The DC-blocker check matters: if a user flips DC blocker on while their
     * EQ is FLAT and gain=0, we still need to take the DSP path so the
     * blocker actually runs. Without this, the toggle would silently do
     * nothing for FLAT users.
     */
    private fun isPassthroughEffective(): Boolean {
        if (dcBlocker) return false
        if (gainDb != 0f) return false
        val current = bands
        for (b in current) if (b.gainDb != 0f) return false
        return true
    }

    fun currentBands(): List<EqBand> = bands
    fun currentGainDb(): Float = gainDb

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // Record the format change as a breadcrumb BEFORE updating sampleRate
        // so the event log reads "44100/2 -> 48000/2" with both sides intact.
        val priorRate = sampleRate
        val priorChannels = channelCount
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        // Allocate filter matrix + the parallel "old" matrix used during
        // cross-fades. Both shapes are channel × band.
        filters = Array(channelCount) { Array(bands.size) { Biquad() } }
        oldFilters = Array(channelCount) { Array(bands.size) { Biquad() } }
        fadeRemaining = 0
        hasInitialized = false
        // Per-channel DC blockers — each gets its own x_prev/y_prev state.
        // Configure here so the cutoff coefficient matches the new sample
        // rate; reused even when the toggle is off (cheap to keep around).
        dcBlockers = Array(channelCount) { DcBlocker().apply { configure(sampleRate) } }
        // Oversampler: 2x polyphase up/down. FIR coefficients depend only on
        // the FIR design parameters (length, cutoff, β), not on sample rate,
        // so configure here only allocates per-channel delay lines.
        oversampler.configure(sampleRate, channelCount)
        // Look-ahead limiter — configured at 2× the input rate. lookAheadSamples
        // = 5 ms × 2*sampleRate = 441 samples at 88.2k = 220 frames at 1x
        // equivalent. The 2× configuration makes the limiter's gain modulation
        // produce aliases above the original Nyquist, which the downsampler's
        // FIR removes.
        limiter.configure(2 * sampleRate, channelCount)
        // Per-frame scratch arrays. 1x rate for input/output; 2x rate for the
        // upsampler↔limiter↔downsampler bridge.
        frameInput = DoubleArray(channelCount)
        frameOutput = DoubleArray(channelCount)
        up0Frame = DoubleArray(channelCount)
        up1Frame = DoubleArray(channelCount)
        lim0Frame = DoubleArray(channelCount)
        lim1Frame = DoubleArray(channelCount)
        linearPhasePop = DoubleArray(channelCount)
        // Linear-phase EQ: allocate per-channel state + synthesize the
        // initial kernel from the current bands. Cheap to do even when phase
        // mode is minimum — the kernel just sits unused until mode switches.
        linearPhaseEq.configure(sampleRate, channelCount)
        linearPhaseEq.setBands(bands)
        dirty = true

        // Diagnostic snapshot: chain config + counters + breadcrumb. Logged
        // here so UI can read the spec even before the first audio buffer
        // arrives, and so format changes mid-session leave a trace.
        AudioChainTelemetry.sampleRate = sampleRate
        AudioChainTelemetry.channelCount = channelCount
        AudioChainTelemetry.encoding = "PCM_16BIT"
        AudioChainTelemetry.dcBlockerEnabled = dcBlocker
        AudioChainTelemetry.firTaps = oversampler.firTaps
        AudioChainTelemetry.lookAheadSamples2x = limiter.drainFrameCount
        AudioChainTelemetry.thresholdDbfs = limiter.thresholdDbfs
        AudioChainTelemetry.totalLatencyFrames1x =
            oversampler.totalDelayFrames1x + limiter.drainFrameCount / 2
        AudioChainTelemetry.enabled = enabled
        AudioChainTelemetry.configurePeakDecay(sampleRate)
        AudioChainTelemetry.incConfigures()
        if (priorRate != 0 && (priorRate != sampleRate || priorChannels != channelCount)) {
            AudioChainTelemetry.logEvent(
                "format_change",
                "$priorRate/$priorChannels -> $sampleRate/$channelCount"
            )
        } else {
            AudioChainTelemetry.logEvent(
                "configure",
                "$sampleRate Hz, $channelCount ch, PCM_16BIT"
            )
        }
        return inputAudioFormat   // we output the same format
    }

    override fun onFlush() {
        // Full chain reset on seek / setMediaItem / format-change-pre-resume.
        // Without this, the limiter (~5 ms LA), oversampler (~16 frames @1x),
        // linear-phase EQ (~46 ms OLA tail), biquad cross-fade state, and DC
        // blocker (one-frame) would each leak a slice of pre-flush audio
        // into the start of the post-flush output. See [chainReset].
        chainReset()
        AudioChainTelemetry.incFlushes()
        AudioChainTelemetry.logEvent("flush")
    }

    override fun onReset() {
        filters = emptyArray()
        oldFilters = emptyArray()
        dcBlockers = emptyArray()
        // Limiter + oversampler buffers freed via reset; configure will
        // reallocate next time.
        limiter.reset()
        oversampler.reset()
        // Linear-phase EQ: reset state AND release the worker scope so the
        // synthesis coroutine doesn't outlive the processor lifecycle.
        linearPhaseEq.reset()
        linearPhaseEq.release()
        frameInput = DoubleArray(0)
        frameOutput = DoubleArray(0)
        up0Frame = DoubleArray(0)
        up1Frame = DoubleArray(0)
        lim0Frame = DoubleArray(0)
        lim1Frame = DoubleArray(0)
        linearPhasePop = DoubleArray(0)
    }

    private fun ensureCoefficients() {
        if (!dirty) return
        // Snapshot bands once so a concurrent setBands from the UI thread
        // can't change which list we're reading partway through the loop.
        val newBands = bands
        // If band count changed (rare — only on schema migration), reallocate.
        if (filters.isNotEmpty() && filters[0].size != newBands.size) {
            filters = Array(channelCount) { Array(newBands.size) { Biquad() } }
            oldFilters = Array(channelCount) { Array(newBands.size) { Biquad() } }
        }
        if (hasInitialized) {
            // Snapshot the live filters' state + (now-stale) coefficients
            // into oldFilters so they can run in parallel for the fade
            // window with the OLD sound. If a fade is already in progress,
            // this overwrites the previous oldFilters with the now-current
            // (mid-fade) state — the new fade smoothly retargets without
            // a discontinuity.
            for (ch in 0 until channelCount) {
                for (i in 0 until filters[ch].size) {
                    oldFilters[ch][i].copyFrom(filters[ch][i])
                }
            }
            fadeRemaining = FADE_LENGTH_SAMPLES
            AudioChainTelemetry.incCrossFades()
            AudioChainTelemetry.logEvent("xfade", "${newBands.size} bands")
        }
        // Install the new coefficients into the live filters. State (z1, z2)
        // is preserved — only the coefficient block is overwritten by
        // setPeaking, so the live filters keep going from where they were.
        for (ch in 0 until channelCount) {
            for (i in newBands.indices) {
                val band = newBands[i]
                filters[ch][i].setPeaking(sampleRate, band.centerHz, band.gainDb, band.qFactor)
            }
        }
        hasInitialized = true
        dirty = false
    }

    /**
     * Half-cosine fade weight for the NEW chain at fade position [progress]
     * (0.0 = start of fade, 1.0 = end). Returns a value in [0, 1] with zero
     * derivative at both ends — equal-power and inaudibly smooth. Old chain
     * weight is `1 - this`.
     */
    private fun fadeWeightNew(progress: Double): Double =
        0.5 - 0.5 * cos(PI * progress)

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Passthrough check FIRST, before any frameCount math — channelCount=0
        // (processor not yet configured) would otherwise div-by-zero on the
        // line below, and we want a robust passthrough even for partial-frame
        // buffers at format transitions.
        //
        // Why per-sample copy and NOT `out.put(inputBuffer)`: with two
        // consecutive BaseAudioProcessors in the chain both doing the bulk
        // ByteBuffer-to-ByteBuffer transfer (this one + SilenceSkipping at
        // level=0), Media3 1.4.1's audio sink throws
        // ERROR_CODE_FAILED_RUNTIME_CHECK (IllegalArgumentException) on the
        // first decoder buffer — the play button visibly cycles
        // play→pause→play and audio never starts. Switching either processor
        // off the bulk-put path makes playback work, which is why the user's
        // workaround was "set Skip-silence to L1." Per-short writes look
        // identical to the chain's view but sidestep whatever the bulk
        // path triggers.
        if (!enabled || channelCount == 0 || isPassthroughEffective()) {
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            val byteCount = src.remaining()
            val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
            while (src.hasRemaining()) {
                out.putShort(src.short)
            }
            out.flip()
            // Telemetry: mark this buffer as a passthrough hit. The transition
            // from DSP -> passthrough is logged as an event (one-time) in the
            // breadcrumb log; per-buffer counts go through the cheap atomic.
            if (!AudioChainTelemetry.passthrough) {
                AudioChainTelemetry.passthrough = true
                AudioChainTelemetry.logEvent("passthrough", "enter")
            }
            AudioChainTelemetry.incPassthroughBuffers()
            if (channelCount > 0) {
                AudioChainTelemetry.addFrames(byteCount / (2 * channelCount))
            }
            return
        }

        val frameCount = inputBuffer.remaining() / (2 * channelCount)
        if (frameCount == 0) return

        if (AudioChainTelemetry.passthrough) {
            AudioChainTelemetry.passthrough = false
            AudioChainTelemetry.logEvent("passthrough", "exit")
            // Linear-phase mode keeps a stateful output queue and overlap-
            // add tail. Resuming after a passthrough window (e.g. release of
            // the Hold-to-A/B button) without reset would emit ~50 ms of
            // stale pre-bypass audio before live audio caught up. Clear the
            // state so resumption sounds like a clean restart instead.
            if (phaseModeLinear) linearPhaseEq.reset()
        }
        AudioChainTelemetry.incDspBuffers()
        AudioChainTelemetry.addFrames(frameCount)

        // Wallclock the DSP path so the diagnostics screen can show how close
        // each buffer is to its real-time deadline (= load factor). This
        // catches thermal/power-saver throttling — when the phone goes in
        // a pocket and the OS clamps CPU, the load factor climbs toward 1.0
        // and underruns become likely.
        val dspStartNs = System.nanoTime()

        // Linear-phase branch — FIR convolution path. The DC blocker still
        // runs frame-by-frame upstream of the linear-phase EQ, but the EQ
        // stage is a chunked overlap-add convolution rather than the biquad
        // cascade. Output frame count per call may differ from input frame
        // count (chunks accumulate then emit in 1024-frame bursts), so the
        // helper handles its own [replaceOutputBuffer] sizing.
        if (phaseModeLinear) {
            queueInputLinearPhase(inputBuffer, frameCount)
            val elapsedNsLinear = System.nanoTime() - dspStartNs
            val audioNsLinear = if (sampleRate > 0) {
                frameCount.toLong() * 1_000_000_000L / sampleRate
            } else 0L
            AudioChainTelemetry.recordBufferTiming(elapsedNsLinear, audioNsLinear)
            return
        }

        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val src = inputBuffer.order(ByteOrder.nativeOrder())

        ensureCoefficients()

        // Float64 throughout: int16 → Double → biquad chain → gain →
        // look-ahead limiter → dither → int16. See Biquad.kt for why precision
        // matters at the low end (31/62 Hz); Limiter.kt for why a real limiter
        // replaces the tanh waveshaper that lived here pre-Phase A5.
        val gainLinear = 10.0.pow(gainDb / 20.0)
        val driveScale = 1.0 / 32768.0
        val invDrive = 32767.0

        // Snapshot toggles once per buffer — `dcBlocker` is volatile, so
        // toggling mid-buffer would otherwise produce a half-treated frame.
        val applyDcBlocker = dcBlocker

        for (frame in 0 until frameCount) {
            // Compute per-frame fade weights once; reuse across channels in
            // the inner loop. `fadeRemaining` decrements per FRAME (not per
            // channel sample) so left/right stay phase-aligned.
            val fading = fadeRemaining > 0
            val wNew: Double
            val wOld: Double
            if (fading) {
                val progress = (FADE_LENGTH_SAMPLES - fadeRemaining).toDouble() /
                    FADE_LENGTH_SAMPLES
                wNew = fadeWeightNew(progress)
                wOld = 1.0 - wNew
            } else {
                wNew = 1.0; wOld = 0.0
            }

            // Pass 1: run each channel through DC blocker + biquad chain +
            // (cross-fade if active) + gain. Result lands in [frameInput] for
            // the limiter to consume as a whole frame (it needs ALL channels
            // for linked-stereo peak detection).
            for (ch in 0 until channelCount) {
                val sampleI = src.short.toInt()
                var x = sampleI * driveScale

                if (applyDcBlocker) {
                    x = dcBlockers[ch].process(x)
                }

                // Run NEW chain. Always — even during fade, we still need
                // the live filters' state to advance (so when the fade ends
                // they're already warmed up to the current signal context).
                var newOut = x
                val newCh = filters[ch]
                for (i in newCh.indices) {
                    newOut = newCh[i].process(newOut)
                }

                // Run OLD chain in parallel during the fade window. Both
                // chains see the same input post-DC-blocker; their outputs
                // are mixed via the equal-power half-cosine weights.
                val mixed = if (fading) {
                    var oldOut = x
                    val oldCh = oldFilters[ch]
                    for (i in oldCh.indices) {
                        oldOut = oldCh[i].process(oldOut)
                    }
                    newOut * wNew + oldOut * wOld
                } else {
                    newOut
                }

                frameInput[ch] = mixed * gainLinear
            }

            // Linked-stereo peak of the post-EQ/gain frame, fed to the input
            // meter. Cheap (max across channels) and only computed in the
            // DSP path; the limiter's own peak detection lives downstream
            // of this, after the upsample.
            var inFramePeak = 0.0
            for (ch in 0 until channelCount) {
                val a = if (frameInput[ch] >= 0) frameInput[ch] else -frameInput[ch]
                if (a > inFramePeak) inFramePeak = a
            }
            AudioChainTelemetry.pushInputSample(inFramePeak)

            // 2x oversampling envelope around the limiter:
            //   1) upsample 1x→2x (anti-imaging FIR)
            //   2) limiter processes both 2x frames (linked-stereo GR sees
            //      both halves of the upsampled pair, so peak detection
            //      operates at 2x temporal resolution)
            //   3) downsample 2x→1x (anti-aliasing FIR removes any harmonics
            //      the gain modulation pushed above the original Nyquist)
            // First ~16 ms after every flush is silent priming output (FIR
            // delay lines + LA buffer ramp up from zeros). Inaudible.
            oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
            limiter.processFrame(up0Frame, lim0Frame)
            limiter.processFrame(up1Frame, lim1Frame)
            oversampler.downsampleFrame(lim0Frame, lim1Frame, frameOutput)
            // Dither only when the limiter actually attenuated. Adding TPDF
            // dither when the chain output is bit-identical to its input
            // would be a noise-floor regression vs the passthrough path.
            // (The DSP path generally won't be exactly bit-identical because
            // of biquad + FIR numeric error, but if all bands are 0 dB and
            // gain=0 the limiter never engages and the chain output is
            // dominated by sub-LSB filter rounding — dithering THAT would
            // amplify the residual into audible noise.)
            val ditherEnabled = limiter.lastReductionDb < 0.0
            var outFramePeak = 0.0
            for (ch in 0 until channelCount) {
                val y = if (ditherEnabled) frameOutput[ch] + dither.next() else frameOutput[ch]
                val a = if (y >= 0) y else -y
                if (a > outFramePeak) outFramePeak = a
                val outI = (y * invDrive).toInt().coerceIn(-32768, 32767)
                out.putShort(outI.toShort())
            }
            AudioChainTelemetry.pushOutputSample(outFramePeak)
            AudioChainTelemetry.reductionDb = limiter.lastReductionDb
            AudioChainTelemetry.fading = fading
            AudioChainTelemetry.ditherActive = ditherEnabled

            if (fading) fadeRemaining--
        }
        out.flip()

        // Push wallclock + audio-time to telemetry. Audio time is derived from
        // frameCount + sampleRate; processing time is the elapsed nanos since
        // [dspStartNs]. The diagnostics screen reads these to surface load
        // factor (= processing/audio): values close to 1.0 mean the audio
        // thread is saturated and underruns are likely.
        val dspElapsedNs = System.nanoTime() - dspStartNs
        val audioNs = if (sampleRate > 0) {
            frameCount.toLong() * 1_000_000_000L / sampleRate
        } else 0L
        AudioChainTelemetry.recordBufferTiming(dspElapsedNs, audioNs)
    }

    /**
     * Linear-phase queueInput path. Replaces the biquad cascade (and its
     * cross-fade machinery) with a 4096-tap FIR convolution provided by
     * [linearPhaseEq]. The rest of the chain (DC blocker, gain,
     * oversampler ↔ limiter, dither, truncation) is shared with the
     * minimum-phase path.
     *
     * Output frame count != input frame count: the EQ accumulates input
     * into [LinearPhaseEq.FRAME_SIZE] chunks before convolving, then emits
     * in [LinearPhaseEq.FRAME_SIZE]-sized bursts. Total chain latency in
     * this mode is ~52 ms (~46 ms FIR group delay + ~6.4 ms post-gain
     * chain). When the EQ is still accumulating its first chunk after a
     * configure / flush / mode switch, this method emits zero output bytes
     * for the buffer (no [replaceOutputBuffer] call) and consumes the input
     * fully — Media3's audio sink waits and queues more input on the next
     * call.
     */
    private fun queueInputLinearPhase(inputBuffer: ByteBuffer, frameCount: Int) {
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val driveScale = 1.0 / 32768.0
        val invDrive = 32767.0
        val gainLinear = 10.0.pow(gainDb / 20.0)
        val applyDcBlocker = dcBlocker

        // Pass 1: read the whole input buffer through the DC blocker into the
        // linear-phase EQ. Per-channel accumulators inside [linearPhaseEq]
        // hold partial chunks across queueInput calls; complete chunks
        // (FRAME_SIZE samples per channel) trigger the FFT convolution.
        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                val sampleI = src.short.toInt()
                var x = sampleI * driveScale
                if (applyDcBlocker) x = dcBlockers[ch].process(x)
                frameInput[ch] = x
            }
            linearPhaseEq.pushFrame(frameInput)
        }

        // Pass 2: how many output frames are ready? May be 0 (the EQ is still
        // accumulating its first chunk after a mode switch / flush / configure)
        // or several thousand (if multiple chunks completed in one call).
        val outputFrames = linearPhaseEq.outputFramesAvailable()
        if (outputFrames == 0) return

        val byteCount = outputFrames * channelCount * 2
        val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())

        for (frame in 0 until outputFrames) {
            linearPhaseEq.popFrame(linearPhasePop)
            // Apply gain and compute input-meter peak (same telemetry the
            // min-phase path feeds).
            var inFramePeak = 0.0
            for (ch in 0 until channelCount) {
                val v = linearPhasePop[ch] * gainLinear
                frameInput[ch] = v
                val a = if (v >= 0) v else -v
                if (a > inFramePeak) inFramePeak = a
            }
            AudioChainTelemetry.pushInputSample(inFramePeak)

            // Same post-gain chain as the min-phase path: 2x upsample,
            // limiter at 2x, 2x downsample, gated TPDF dither, int16
            // truncation. Reusing the existing scratch arrays.
            oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
            limiter.processFrame(up0Frame, lim0Frame)
            limiter.processFrame(up1Frame, lim1Frame)
            oversampler.downsampleFrame(lim0Frame, lim1Frame, frameOutput)
            val ditherEnabled = limiter.lastReductionDb < 0.0
            var outFramePeak = 0.0
            for (ch in 0 until channelCount) {
                val y = if (ditherEnabled) frameOutput[ch] + dither.next() else frameOutput[ch]
                val a = if (y >= 0) y else -y
                if (a > outFramePeak) outFramePeak = a
                val outI = (y * invDrive).toInt().coerceIn(-32768, 32767)
                out.putShort(outI.toShort())
            }
            AudioChainTelemetry.pushOutputSample(outFramePeak)
            AudioChainTelemetry.reductionDb = limiter.lastReductionDb
            AudioChainTelemetry.fading = false  // no biquad cross-fade in linear-phase mode
            AudioChainTelemetry.ditherActive = ditherEnabled
        }
        out.flip()
    }

    /**
     * End-of-stream drain. The 2x oversampler + look-ahead limiter chain
     * holds enough audio in its internal delay lines to fill ~6.4 ms — without
     * this drain, the last bit of every track would be silently eaten as
     * Media3 stops calling [queueInput] and the chain never gets a chance to
     * flush its tail.
     *
     * Media3's [BaseAudioProcessor.queueEndOfStream] is `final` and ends with
     * `onQueueEndOfStream()`; this is the documented override hook for
     * subclasses that need to flush internal buffers. We push zero 1x-rate
     * frames into the FULL post-gain chain (oversampler ↔ limiter ↔
     * oversampler) and emit the resulting samples through the same dither +
     * truncation path as [queueInput]. Each zero input produces one 1x
     * output, just like a normal input — the only difference is the input
     * is silence, which lets the buffered real audio drain out the back.
     *
     * Drain length = oversampler chain delay (1x equivalent) + limiter LA
     * delay (1x equivalent). Both are computed at configure time. Total
     * output stays well under one ByteBuffer's capacity (~1 KB at 44.1k
     * stereo).
     */
    override fun onQueueEndOfStream() {
        // Skip drain entirely if processor never configured (no audio ever
        // played) or in passthrough — the chain has no buffered audio in
        // either case, so the default no-op (no output buffer set) is correct.
        if (channelCount == 0 || !enabled || isPassthroughEffective()) {
            AudioChainTelemetry.logEvent("eos", "skipped (passthrough or unconfigured)")
            return
        }
        AudioChainTelemetry.incDrains()

        val gainLinear = 10.0.pow(gainDb / 20.0)
        val invDrive = 32767.0
        // Post-gain chain delay at 1x rate. Common to both phase modes:
        // limiter.drainFrameCount is in 2x-rate frames (LA window in 2x
        // samples); divide by 2 for 1x equivalent. Add the oversampler's
        // combined up+down FIR group delay at 1x.
        val postGainDrain = oversampler.totalDelayFrames1x + limiter.drainFrameCount / 2

        if (phaseModeLinear) {
            // Linear-phase drain has two stages stacked back-to-back:
            //   1) push zero frames into linearPhaseEq to flush any partial
            //      accumulator chunk + the kernel's group delay (~46 ms).
            //      Three FRAME_SIZE chunks of zeros covers a worst-case
            //      partial accumulator (FRAME_SIZE-1 samples) + 2 chunks of
            //      group delay flush + a chunk of slack.
            //   2) drain the post-gain chain (oversampler ↔ limiter) with
            //      zero input, same as the min-phase drain below.
            val zeroFramesIn = LinearPhaseEq.FRAME_SIZE * 3
            for (ch in 0 until channelCount) frameInput[ch] = 0.0
            for (i in 0 until zeroFramesIn) linearPhaseEq.pushFrame(frameInput)
            val linearOut = linearPhaseEq.outputFramesAvailable()
            val totalDrainFrames = linearOut + postGainDrain
            AudioChainTelemetry.logEvent(
                "eos_drain",
                "linear: $linearOut + post: $postGainDrain @1x"
            )
            val byteCount = totalDrainFrames * channelCount * 2
            if (byteCount <= 0) return
            val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
            // Stage 1: drain linearPhaseEq output through gain → post-gain chain.
            for (frame in 0 until linearOut) {
                linearPhaseEq.popFrame(linearPhasePop)
                for (ch in 0 until channelCount) {
                    frameInput[ch] = linearPhasePop[ch] * gainLinear
                }
                oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
                limiter.processFrame(up0Frame, lim0Frame)
                limiter.processFrame(up1Frame, lim1Frame)
                oversampler.downsampleFrame(lim0Frame, lim1Frame, frameOutput)
                val ditherEnabled = limiter.lastReductionDb < 0.0
                for (ch in 0 until channelCount) {
                    val y = if (ditherEnabled) frameOutput[ch] + dither.next() else frameOutput[ch]
                    val outI = (y * invDrive).toInt().coerceIn(-32768, 32767)
                    out.putShort(outI.toShort())
                }
            }
            // Stage 2: zero-input drain of the post-gain chain.
            for (ch in 0 until channelCount) frameInput[ch] = 0.0
            for (frame in 0 until postGainDrain) {
                oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
                limiter.processFrame(up0Frame, lim0Frame)
                limiter.processFrame(up1Frame, lim1Frame)
                oversampler.downsampleFrame(lim0Frame, lim1Frame, frameOutput)
                val ditherEnabled = limiter.lastReductionDb < 0.0
                for (ch in 0 until channelCount) {
                    val y = if (ditherEnabled) frameOutput[ch] + dither.next() else frameOutput[ch]
                    val outI = (y * invDrive).toInt().coerceIn(-32768, 32767)
                    out.putShort(outI.toShort())
                }
            }
            out.flip()
            return
        }

        // Min-phase drain — push zeros through oversampler ↔ limiter and emit.
        AudioChainTelemetry.logEvent("eos_drain", "$postGainDrain frames @1x")
        val byteCount = postGainDrain * channelCount * 2
        if (byteCount <= 0) return
        val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        // Zero 1x input frame, reused every drain step. Pre-zero just in case
        // [frameInput] held real audio; we want clean silence going into the
        // chain so real audio drains out cleanly.
        for (ch in 0 until channelCount) frameInput[ch] = 0.0
        for (frame in 0 until postGainDrain) {
            // Same chain as the queueInput hot path, just with zero input.
            // Each call reads one stale frame off the back of the chain.
            oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
            limiter.processFrame(up0Frame, lim0Frame)
            limiter.processFrame(up1Frame, lim1Frame)
            oversampler.downsampleFrame(lim0Frame, lim1Frame, frameOutput)
            val ditherEnabled = limiter.lastReductionDb < 0.0
            for (ch in 0 until channelCount) {
                val y = if (ditherEnabled) frameOutput[ch] + dither.next() else frameOutput[ch]
                val outI = (y * invDrive).toInt().coerceIn(-32768, 32767)
                out.putShort(outI.toShort())
            }
        }
        out.flip()
    }

    companion object {
        /**
         * Cross-fade window length in samples for band changes. ~46 ms at
         * 44.1 kHz, ~43 ms at 48 kHz. Long enough that the human ear can't
         * resolve the transition (transient resolution is ~2 ms but we need
         * ~10x that for an inaudible blend), short enough that it doesn't
         * feel laggy when the user drags an EQ slider. The agent review
         * specifically called out 512 samples (~12 ms) as too short — the
         * fade was perceptible on fast slider drags.
         *
         * Sample-count rather than time-based so we don't need a per-buffer
         * sample-rate-aware recompute; the absolute time difference between
         * 44.1 / 48 kHz is 3 ms, inaudible.
         */
        const val FADE_LENGTH_SAMPLES = 2048
    }
}
