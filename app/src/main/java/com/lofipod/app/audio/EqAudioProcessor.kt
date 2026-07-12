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
    // EQ phase mode (v0.9.3+). Three values:
    //   PURE_IIR (default): 10-band minimum-phase biquad cascade with
    //     equal-power crossfade on band changes. Fast, low-latency, low CPU.
    //   MIN_FIR: minimum-phase 4096-tap FIR via UPC convolution + real-
    //     cepstrum kernel synthesis. Surgical magnitude precision, no
    //     pre-ringing, ms-scale group delay.
    //   LINEAR_FIR: linear-phase 4096-tap FIR via UPC convolution +
    //     symmetric kernel. Preserves transient waveform shape, audible
    //     pre-ringing on transients, ~46 ms group delay.
    //
    // The downstream chain (DC blocker, gain, oversampler, limiter, dither)
    // is identical across all three modes; only the EQ stage swaps.
    @Volatile private var phaseMode: PhaseMode = PhaseMode.PURE_IIR
    @Volatile private var dirty: Boolean = true       // recompute coefficients on next buffer

    // ---- Tone filters (v0.11 "FabFilter-lite" corrective stage) ----
    // Three zero-latency IIR controls that run BEFORE the EQ stage (right
    // after the DC blocker) in every phase mode:
    //   - lowCutHz  > 0: 12 dB/oct Butterworth high-pass (rumble removal)
    //   - highCutHz > 0: 12 dB/oct Butterworth low-pass (hiss removal)
    //   - tiltDb   != 0: complementary low/high shelf pair pivoting at
    //     TILT_PIVOT_HZ — one knob from "darker" (negative) to "brighter"
    //     (positive)
    // Deliberately IIR (minimum-phase) even when the EQ stage runs FIR:
    // corrective cuts *want* min-phase behavior (no pre-ringing), this is
    // exactly how zero-latency mode works in the desktop parametrics the
    // feature is modeled on, and it keeps the controls identical across
    // all four phase modes with zero added latency.
    @Volatile private var lowCutHz: Float = 0f
    @Volatile private var highCutHz: Float = 0f
    @Volatile private var tiltDb: Float = 0f
    // v0.11 refinement: optional 24 dB/oct low-cut slope. When on, a second
    // identical Butterworth section cascades after the first — two Q=0.707
    // stages form a 4th-order Linkwitz-Riley high-pass (-6 dB at corner,
    // 24 dB/oct skirt). Steeper rumble removal without touching the voice
    // band above the corner.
    @Volatile private var lowCutSteep: Boolean = false
    @Volatile private var toneDirty: Boolean = true

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
    // Tone-filter biquads, one of each per channel. Allocated in onConfigure;
    // configured lazily by [ensureToneCoefficients] when [toneDirty].
    private var lowCutFilters: Array<Biquad> = emptyArray()
    private var highCutFilters: Array<Biquad> = emptyArray()
    private var tiltLowFilters: Array<Biquad> = emptyArray()
    private var tiltHighFilters: Array<Biquad> = emptyArray()
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
    // FIR EQ via UPC (uniform partitioned convolution). Used when phaseMode
    // is MIN_FIR or LINEAR_FIR; idle when PURE_IIR. Owns its own worker scope
    // for kernel synthesis on band changes; reset on flush; released on
    // processor reset. The same FirEq instance handles both FIR modes —
    // [FirEq.setMode] dispatches between the linear-phase symmetric kernel
    // synthesis and the min-phase real-cepstrum synthesis path, both feeding
    // the underlying [UpcConvolver].
    private val firEq = FirEq()
    // ---- Voice suite (v0.11 premium stages) ----
    // Four staged (off/L1-L3) voice-treatment effects, modeled on the
    // desktop mastering suites:
    //   - deEsser + leveler: zero-latency, run at 1x on the pre-EQ frame
    //     (corrective placement — the EQ and everything downstream see the
    //     tamed, ridden signal).
    //   - saturator (Warmth) + airExciter (Air): nonlinear, so they run
    //     INSIDE the existing 2x oversampling envelope between upsampler
    //     and limiter — harmonics land below the 2x Nyquist and the
    //     downsampler's anti-alias FIR removes anything above the source
    //     band. Zero added latency, no extra oversampler instances.
    // All four are identical across every phase mode, like the tone stage.
    private val deEsser = DeEsser()
    private val leveler = Leveler()
    private val saturator = Saturator()
    private val airExciter = AirExciter()
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
    // Scratch for popping FIR EQ output frames into per-channel doubles
    // before they enter the gain → oversample → limiter chain. Sized at
    // configure to channelCount; reused every frame.
    private var firPop: DoubleArray = DoubleArray(0)
    // Pre-EQ frame scratch: the voice stages (leveler, de-esser) need the
    // whole frame — all channels — for linked detection BEFORE the EQ
    // cascade runs, so the IIR path decodes into this first.
    private var framePre: DoubleArray = DoubleArray(0)
    // Second low-cut section, cascaded when [lowCutSteep] (LR4 slope).
    private var lowCutFilters2: Array<Biquad> = emptyArray()
    // Audio-thread mirror of [lowCutSteep], set by ensureToneCoefficients
    // alongside the coefficient swap so processTone stays branch-cheap.
    private var steepActive = false

    fun setBands(newBands: List<EqBand>) {
        bands = newBands
        dirty = true
        // FIR EQ kicks off async kernel re-synthesis on its worker scope.
        // Cheap to call even when phase mode is PURE_IIR — the worker just
        // produces a kernel that goes unused until the user toggles modes.
        firEq.setBands(newBands)
        // Counter increments per call; the actual cross-fade only fires inside
        // ensureCoefficients (where we also log the breadcrumb event). Cheap
        // enough to call from the UI thread on every slider tick.
        AudioChainTelemetry.incBandChanges()
    }
    fun setGainDb(db: Float) { gainDb = db.coerceIn(-12f, 12f) }

    /**
     * Sleep-timer fade multiplier (1.0 = none). Owned by the sleep timer
     * in PlayerController, which steps it along a half-cosine every 250ms
     * during the final fade window and RESETS it to 1.0 on pause/cancel.
     * Deliberately a dumb multiplier here — no ramp state, no curve.
     * Applied in every output path INCLUDING the bit-exact passthrough
     * copy (per-episode "Disable EQ" short-circuits before
     * isPassthroughEffective, so the DSP-path fold alone would silently
     * skip fading on EQ-disabled episodes).
     */
    @Volatile private var sleepFadeLinear: Double = 1.0
    fun setSleepFade(fade: Double) {
        sleepFadeLinear = fade.coerceIn(0.0, 1.0)
    }
    fun currentSleepFade(): Double = sleepFadeLinear

    /** Low-cut (high-pass) corner in Hz; 0 disables. Takes effect next buffer. */
    fun setLowCutHz(hz: Float) {
        val v = if (hz <= 0f) 0f else hz.coerceIn(20f, 300f)
        if (v == lowCutHz) return
        lowCutHz = v
        toneDirty = true
        AudioChainTelemetry.logEvent("tone_low_cut", if (v == 0f) "off" else "${v.toInt()} Hz")
    }

    /** High-cut (low-pass) corner in Hz; 0 disables. Takes effect next buffer. */
    fun setHighCutHz(hz: Float) {
        val v = if (hz <= 0f) 0f else hz.coerceIn(2_000f, 20_000f)
        if (v == highCutHz) return
        highCutHz = v
        toneDirty = true
        AudioChainTelemetry.logEvent("tone_high_cut", if (v == 0f) "off" else "${v.toInt()} Hz")
    }

    /** Spectral tilt in dB (negative = darker, positive = brighter); 0 disables. */
    fun setTiltDb(db: Float) {
        val v = db.coerceIn(-6f, 6f)
        if (v == tiltDb) return
        tiltDb = v
        toneDirty = true
        AudioChainTelemetry.logEvent("tone_tilt", "%+.1f dB".format(v))
    }

    /** 24 dB/oct low-cut slope (LR4). Only audible while low cut is on. */
    fun setLowCutSteep(on: Boolean) {
        if (on == lowCutSteep) return
        lowCutSteep = on
        toneDirty = true
        AudioChainTelemetry.logEvent("tone_low_cut_slope", if (on) "24 dB/oct" else "12 dB/oct")
    }

    // ---- Voice suite setters (0 = off, 1..3 staged). The stage classes own
    // their level as a volatile and self-apply parameter changes on the
    // audio thread, mirroring SilenceSkippingProcessor.setLevel. ----
    fun setDeEsserLevel(l: Int) {
        if (l.coerceIn(0, 3) == deEsser.currentLevel()) return
        deEsser.setLevel(l)
        AudioChainTelemetry.logEvent("voice_deesser", "L${deEsser.currentLevel()}")
    }

    fun setWarmthLevel(l: Int) {
        if (l.coerceIn(0, 3) == saturator.currentLevel()) return
        saturator.setLevel(l)
        AudioChainTelemetry.logEvent("voice_warmth", "L${saturator.currentLevel()}")
    }

    fun setLevelerLevel(l: Int) {
        if (l.coerceIn(0, 3) == leveler.currentLevel()) return
        leveler.setLevel(l)
        AudioChainTelemetry.logEvent("voice_leveler", "L${leveler.currentLevel()}")
    }

    fun setAirLevel(l: Int) {
        if (l.coerceIn(0, 3) == airExciter.currentLevel()) return
        airExciter.setLevel(l)
        AudioChainTelemetry.logEvent("voice_air", "L${airExciter.currentLevel()}")
    }

    fun currentLowCutHz(): Float = lowCutHz
    fun currentHighCutHz(): Float = highCutHz
    fun currentTiltDb(): Float = tiltDb
    fun currentLowCutSteep(): Boolean = lowCutSteep
    fun currentDeEsserLevel(): Int = deEsser.currentLevel()
    fun currentWarmthLevel(): Int = saturator.currentLevel()
    fun currentLevelerLevel(): Int = leveler.currentLevel()
    fun currentAirLevel(): Int = airExciter.currentLevel()
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
     * Switch the EQ phase mode. Takes effect on the next audio buffer;
     * mid-buffer audio in flight through the previous mode is not cross-
     * faded — there will be a brief (< 50 ms) audible artifact at the
     * transition. Acceptable for a manual mode switch.
     *
     * Side effect: full chain reset (DC blocker, biquads, FIR EQ, limiter,
     * oversampler). Without it the post-EQ stages would briefly mix the
     * previous mode's audio with the new mode's first output frames.
     */
    fun setPhaseMode(newMode: PhaseMode) {
        if (phaseMode == newMode) return
        phaseMode = newMode
        // Re-synthesize the FIR kernel for the new mode (no-op when
        // PURE_IIR — FirEq idles).
        firEq.setMode(
            when (newMode) {
                PhaseMode.LINEAR_FIR -> FirEq.Mode.LINEAR
                PhaseMode.MIN_FIR -> FirEq.Mode.MIN_PHASE
                PhaseMode.MIXED -> FirEq.Mode.MIXED
                PhaseMode.PURE_IIR -> firEq.mode  // unchanged, FIR idles
            },
            bands,
        )
        chainReset()
        AudioChainTelemetry.logEvent("phase_mode", newMode.storageKey)
    }

    /** Legacy boolean entrypoint kept for backwards compat with any caller
     *  not yet migrated to [setPhaseMode]. True maps to LINEAR_FIR, false
     *  to PURE_IIR. v0.9.0 callers (PlaybackService rehydrate) used this
     *  shape; v0.9.3+ callers should prefer [setPhaseMode]. */
    @Deprecated(
        "Use setPhaseMode(PhaseMode) — supports the v0.9.3 three-mode lineup.",
        ReplaceWith("setPhaseMode(if (on) PhaseMode.LINEAR_FIR else PhaseMode.PURE_IIR)")
    )
    fun setPhaseModeLinear(on: Boolean) {
        setPhaseMode(if (on) PhaseMode.LINEAR_FIR else PhaseMode.PURE_IIR)
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
        for (b in lowCutFilters) b.reset()
        for (b in lowCutFilters2) b.reset()
        for (b in highCutFilters) b.reset()
        for (b in tiltLowFilters) b.reset()
        for (b in tiltHighFilters) b.reset()
        deEsser.reset()
        leveler.reset()
        saturator.reset()
        airExciter.reset()
        // The voice stages' meter state just went idle; park the UI-facing
        // mirrors to match, or a flush with no follow-up DSP buffer (pause
        // after seek, stop at track end) leaves the meters frozen on stale
        // pre-flush values.
        AudioChainTelemetry.parkVoiceMirrorsIdle()
        limiter.reset()
        oversampler.reset()
        firEq.reset()
        fadeRemaining = 0
    }

    /** Read-only accessor for the current phase mode. Used by the in-Player
     *  diagnostics tab to surface "Linear-Phase FIR" / "Min-Phase FIR" /
     *  "Pure IIR" without needing a Settings round-trip on every recompose. */
    fun currentPhaseMode(): PhaseMode = phaseMode

    /** Legacy boolean accessor kept for backwards compat with callers
     *  that still treat phase mode as a 2-state. True iff a FIR mode is
     *  active (either MIN_FIR or LINEAR_FIR). */
    @Deprecated(
        "Use currentPhaseMode() — supports the v0.9.3 three-mode lineup.",
        ReplaceWith("currentPhaseMode() != PhaseMode.PURE_IIR")
    )
    fun isPhaseModeLinear(): Boolean = phaseMode != PhaseMode.PURE_IIR

    /**
     * Total chain latency from input to audible output, in microseconds.
     *
     *   - FIR EQ (when phaseMode is MIN_FIR or LINEAR_FIR): UPC block-
     *     buffering delay + kernel group delay. MIN_FIR: BLOCK_SIZE only,
     *     ≈ 23 ms at 44.1k (cepstrum kernel is energy-front-loaded, group
     *     delay is sub-ms). LINEAR_FIR: BLOCK_SIZE + (KERNEL_LENGTH-1)/2,
     *     ≈ 70 ms at 44.1k (symmetric kernel center). Always 0 when
     *     phaseMode is PURE_IIR.
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
        // FirEq exposes the combined block-buffering + kernel-group-delay
        // at 1× rate; convert to microseconds.
        val firUs = if (phaseMode != PhaseMode.PURE_IIR) {
            firEq.algorithmicDelayFrames1x.toLong() * 1_000_000L / sampleRate
        } else 0L
        val osUs = oversampler.totalDelayFrames1x.toLong() * 1_000_000L / sampleRate
        val limUs = (limiter.drainFrameCount.toLong() / 2L) * 1_000_000L / sampleRate
        return firUs + osUs + limUs
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
        if (toneActive()) return false
        if (voiceActive()) return false
        // NOTE deliberately NO sleepFadeLinear clause here: the passthrough
        // copy loop multiplies the fade itself, so a FLAT/0dB config can
        // stay on the bit-copy path for the whole fade. Forcing the DSP
        // path instead (an earlier revision did) made FIR-mode users eat a
        // ~23ms convolver re-prime gap right at fade onset — an audible
        // dropout exactly when the transition should be seamless.
        val current = bands
        for (b in current) if (b.gainDb != 0f) return false
        return true
    }

    /** True when any tone filter (low-cut / high-cut / tilt) is engaged. */
    private fun toneActive(): Boolean =
        lowCutHz > 0f || highCutHz > 0f || tiltDb != 0f

    /** True when any voice-suite stage is engaged — the DSP path must run
     *  so the stages actually process (same rationale as the DC-blocker
     *  check above). */
    private fun voiceActive(): Boolean =
        deEsser.currentLevel() > 0 || leveler.currentLevel() > 0 ||
            saturator.currentLevel() > 0 || airExciter.currentLevel() > 0

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
        // Tone-filter biquads. Coefficients depend on sample rate, so mark
        // dirty and let ensureToneCoefficients reconfigure on the first
        // buffer at the new rate.
        lowCutFilters = Array(channelCount) { Biquad() }
        lowCutFilters2 = Array(channelCount) { Biquad() }
        highCutFilters = Array(channelCount) { Biquad() }
        tiltLowFilters = Array(channelCount) { Biquad() }
        tiltHighFilters = Array(channelCount) { Biquad() }
        toneDirty = true
        // Voice suite: de-esser + leveler at 1x, saturator + air at the 2x
        // oversampled rate (they run inside the oversampling envelope).
        deEsser.configure(sampleRate, channelCount)
        leveler.configure(sampleRate, channelCount)
        saturator.configure(2 * sampleRate, channelCount)
        airExciter.configure(2 * sampleRate, channelCount)
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
        firPop = DoubleArray(channelCount)
        framePre = DoubleArray(channelCount)
        // FIR EQ: allocate per-channel state + synthesize the initial kernel
        // from the current bands at the current phase mode. Cheap to keep
        // configured even when phase mode is PURE_IIR — the kernel sits
        // unused until the user switches into a FIR mode.
        firEq.configure(sampleRate, channelCount)
        firEq.setMode(
            when (phaseMode) {
                PhaseMode.LINEAR_FIR -> FirEq.Mode.LINEAR
                PhaseMode.MIN_FIR -> FirEq.Mode.MIN_PHASE
                PhaseMode.MIXED -> FirEq.Mode.MIXED
                PhaseMode.PURE_IIR -> FirEq.Mode.LINEAR  // FirEq idle in PURE_IIR; mode picked for first toggle
            },
            bands,
        )
        firEq.setBands(bands)
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
        // FIR EQ (UPC FDL + overlap + output ring), biquad cross-fade state,
        // and DC blocker (one-frame) would each leak a slice of pre-flush
        // audio into the start of the post-flush output. See [chainReset].
        chainReset()
        AudioChainTelemetry.incFlushes()
        AudioChainTelemetry.logEvent("flush")
    }

    override fun onReset() {
        filters = emptyArray()
        oldFilters = emptyArray()
        dcBlockers = emptyArray()
        lowCutFilters = emptyArray()
        lowCutFilters2 = emptyArray()
        highCutFilters = emptyArray()
        tiltLowFilters = emptyArray()
        tiltHighFilters = emptyArray()
        deEsser.reset()
        leveler.reset()
        saturator.reset()
        airExciter.reset()
        AudioChainTelemetry.parkVoiceMirrorsIdle()
        // Limiter + oversampler buffers freed via reset; configure will
        // reallocate next time.
        limiter.reset()
        oversampler.reset()
        // FIR EQ: reset state AND release the worker scope so the
        // synthesis coroutine doesn't outlive the processor lifecycle.
        firEq.reset()
        firEq.release()
        frameInput = DoubleArray(0)
        frameOutput = DoubleArray(0)
        up0Frame = DoubleArray(0)
        up1Frame = DoubleArray(0)
        lim0Frame = DoubleArray(0)
        lim1Frame = DoubleArray(0)
        firPop = DoubleArray(0)
        framePre = DoubleArray(0)
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
     * Recompute the tone-filter coefficients when [toneDirty]. Unlike the
     * band cascade there's no cross-fade machinery: these are corrective
     * filters adjusted via discrete chips / a coarse slider, and the biquad
     * state (z1/z2) is preserved across coefficient swaps, so transitions
     * are click-free in practice.
     */
    private fun ensureToneCoefficients() {
        if (!toneDirty) return
        val lc = lowCutHz
        val hc = highCutHz
        val tilt = tiltDb
        val steep = lowCutSteep
        for (ch in 0 until channelCount) {
            if (lc > 0f) {
                lowCutFilters[ch].setHighpass(sampleRate, lc)
                // Second identical section when steep: two cascaded Q=0.707
                // Butterworth HPs = 4th-order Linkwitz-Riley, 24 dB/oct.
                if (steep) lowCutFilters2[ch].setHighpass(sampleRate, lc)
                else lowCutFilters2[ch].setIdentity()
            } else {
                lowCutFilters[ch].setIdentity()
                lowCutFilters2[ch].setIdentity()
            }
            if (hc > 0f) highCutFilters[ch].setLowpass(sampleRate, hc)
            else highCutFilters[ch].setIdentity()
            if (tilt != 0f) {
                tiltLowFilters[ch].setLowShelf(sampleRate, TILT_PIVOT_HZ, -tilt)
                tiltHighFilters[ch].setHighShelf(sampleRate, TILT_PIVOT_HZ, tilt)
            } else {
                tiltLowFilters[ch].setIdentity()
                tiltHighFilters[ch].setIdentity()
            }
        }
        steepActive = steep && lc > 0f
        toneDirty = false
    }

    /** Run one sample of channel [ch] through the tone-filter stage. */
    private fun processTone(ch: Int, x: Double): Double {
        var y = lowCutFilters[ch].process(x)
        if (steepActive) y = lowCutFilters2[ch].process(y)
        y = highCutFilters[ch].process(y)
        y = tiltLowFilters[ch].process(y)
        y = tiltHighFilters[ch].process(y)
        return y
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
            // Sleep fade applies even on the bit-exact path — one snapshot
            // per buffer, one multiply per short when engaged. fade==1.0
            // keeps the copy bit-identical (no float round-trip).
            val fade = sleepFadeLinear
            if (fade == 1.0) {
                while (src.hasRemaining()) {
                    out.putShort(src.short)
                }
            } else {
                while (src.hasRemaining()) {
                    val v = (src.short * fade).toInt().coerceIn(-32768, 32767)
                    out.putShort(v.toShort())
                }
            }
            out.flip()
            // Telemetry: mark this buffer as a passthrough hit. The transition
            // from DSP -> passthrough is logged as an event (one-time) in the
            // breadcrumb log; per-buffer counts go through the cheap atomic.
            if (!AudioChainTelemetry.passthrough) {
                AudioChainTelemetry.passthrough = true
                AudioChainTelemetry.logEvent("passthrough", "enter")
                // The DSP path never runs in passthrough, so the voice-suite
                // mirrors would otherwise hold their last DSP-buffer values
                // forever (e.g. chain disabled mid-sibilant). Park them at
                // idle once, on the transition.
                AudioChainTelemetry.parkVoiceMirrorsIdle()
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
            // Full chain reset, not just firEq: EVERY stateful stage holds
            // pre-bypass audio across a passthrough window — the
            // oversampler's FIR delay lines and the limiter's ~5ms
            // look-ahead would otherwise emit a fragment from before the
            // window (an audible blip on Hold-to-A/B release). The FIR
            // FDL/output-ring case this block originally handled is
            // covered by chainReset too.
            chainReset()
        }
        AudioChainTelemetry.incDspBuffers()
        AudioChainTelemetry.addFrames(frameCount)

        // Wallclock the DSP path so the diagnostics screen can show how close
        // each buffer is to its real-time deadline (= load factor). This
        // catches thermal/power-saver throttling — when the phone goes in
        // a pocket and the OS clamps CPU, the load factor climbs toward 1.0
        // and underruns become likely.
        val dspStartNs = System.nanoTime()

        // FIR branch — UPC convolution path (LINEAR_FIR or MIN_FIR). The
        // DC blocker still runs frame-by-frame upstream of the FIR EQ, but
        // the EQ stage is a partitioned-convolution emit-on-block-boundary
        // rather than the biquad cascade. Output frame count per call may
        // differ from input frame count (frames accumulate then emit in
        // BLOCK_SIZE-aligned bursts); the helper handles its own
        // [replaceOutputBuffer] sizing.
        if (phaseMode != PhaseMode.PURE_IIR) {
            queueInputFir(inputBuffer, frameCount)
            val elapsedNsFir = System.nanoTime() - dspStartNs
            val audioNsFir = if (sampleRate > 0) {
                frameCount.toLong() * 1_000_000_000L / sampleRate
            } else 0L
            AudioChainTelemetry.recordBufferTiming(elapsedNsFir, audioNsFir)
            return
        }

        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val src = inputBuffer.order(ByteOrder.nativeOrder())

        ensureCoefficients()

        // Float64 throughout: int16 → Double → biquad chain → gain →
        // look-ahead limiter → dither → int16. See Biquad.kt for why precision
        // matters at the low end (31/62 Hz); Limiter.kt for why a real limiter
        // replaces the tanh waveshaper that lived here pre-Phase A5.
        // Sleep fade folds into the per-buffer gain snapshot — the timer
        // steps the volatile at 250ms, well above buffer cadence.
        val gainLinear = 10.0.pow(gainDb / 20.0) * sleepFadeLinear
        val driveScale = 1.0 / 32768.0
        val invDrive = 32767.0

        // Snapshot toggles once per buffer — `dcBlocker` is volatile, so
        // toggling mid-buffer would otherwise produce a half-treated frame.
        val applyDcBlocker = dcBlocker
        val applyTone = toneActive()
        if (applyTone) ensureToneCoefficients()
        val applyLeveler = leveler.currentLevel() > 0
        val applyDeEsser = deEsser.currentLevel() > 0
        val applyWarmth = saturator.currentLevel() > 0
        val applyAir = airExciter.currentLevel() > 0

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

            // Pass 0: decode + DC blocker + tone into [framePre] — the voice
            // stages (leveler ride, de-esser detection) are linked across
            // channels, so they need the WHOLE frame before the EQ runs.
            for (ch in 0 until channelCount) {
                val sampleI = src.short.toInt()
                var x = sampleI * driveScale
                if (applyDcBlocker) {
                    x = dcBlockers[ch].process(x)
                }
                if (applyTone) {
                    x = processTone(ch, x)
                }
                framePre[ch] = x
            }
            if (applyLeveler) leveler.processFrame(framePre)
            if (applyDeEsser) deEsser.processFrame(framePre)

            // Pass 1: run each channel through the biquad chain +
            // (cross-fade if active) + gain. Result lands in [frameInput] for
            // the limiter to consume as a whole frame (it needs ALL channels
            // for linked-stereo peak detection).
            for (ch in 0 until channelCount) {
                val x = framePre[ch]

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
            // Nonlinear voice stages run at 2x, pre-limiter: their harmonics
            // sit below the 2x Nyquist and the downsampler strips anything
            // above the source band. Warmth first, then Air, so the exciter
            // works on the saturated (final) tonality.
            if (applyWarmth) {
                saturator.processFrame(up0Frame)
                saturator.processFrame(up1Frame)
            }
            if (applyAir) {
                airExciter.processFrame(up0Frame)
                airExciter.processFrame(up1Frame)
            }
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

        // Voice-suite meters — once per buffer is plenty for the 250 ms
        // diagnostics poll, and keeps the volatile writes out of the frame
        // loop.
        AudioChainTelemetry.deEsserGainLin = if (applyDeEsser) deEsser.lastGainLin else 1.0
        AudioChainTelemetry.levelerGainLin = if (applyLeveler) leveler.lastGainLin else 1.0
        AudioChainTelemetry.warmthActivity =
            if (applyWarmth) saturator.currentActivityLin() else 0.0
        AudioChainTelemetry.airActivity =
            if (applyAir) airExciter.currentActivityLin() else 0.0

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
     * FIR-mode queueInput path. Replaces the biquad cascade (and its
     * cross-fade machinery) with the 4096-tap UPC convolution provided by
     * [firEq]. The rest of the chain (DC blocker, gain, oversampler ↔
     * limiter, dither, truncation) is shared with the PURE_IIR path.
     *
     * Output frame count != input frame count: FirEq accumulates input
     * into [FirEq.BLOCK_SIZE] chunks before convolving, then emits in
     * [FirEq.BLOCK_SIZE]-sized bursts. Total chain latency depends on
     * mode — see [getChainLatencyUs]:
     *   - MIN_FIR: ~29 ms (block delay + ms-scale kernel group delay
     *     + ~6 ms post-gain chain).
     *   - LINEAR_FIR: ~70 ms (block delay + symmetric kernel center
     *     ~46 ms + post-gain chain).
     *
     * When FirEq is still accumulating its first block after a
     * configure / flush / mode switch, this method emits zero output
     * bytes for the buffer and Media3's audio sink waits for the next
     * call.
     */
    private fun queueInputFir(inputBuffer: ByteBuffer, frameCount: Int) {
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val driveScale = 1.0 / 32768.0
        val invDrive = 32767.0
        val gainLinear = 10.0.pow(gainDb / 20.0) * sleepFadeLinear
        val applyDcBlocker = dcBlocker
        val applyTone = toneActive()
        if (applyTone) ensureToneCoefficients()
        val applyLeveler = leveler.currentLevel() > 0
        val applyDeEsser = deEsser.currentLevel() > 0
        val applyWarmth = saturator.currentLevel() > 0
        val applyAir = airExciter.currentLevel() > 0

        // Pass 1: read the whole input buffer through the DC blocker + tone
        // filters + voice stages (leveler ride, de-esser — same pre-EQ
        // placement as the PURE_IIR path) into the FIR EQ. Per-channel
        // accumulators inside [firEq] hold partial blocks across queueInput
        // calls; complete blocks (BLOCK_SIZE samples per channel) trigger
        // the UPC convolution.
        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                val sampleI = src.short.toInt()
                var x = sampleI * driveScale
                if (applyDcBlocker) x = dcBlockers[ch].process(x)
                if (applyTone) x = processTone(ch, x)
                frameInput[ch] = x
            }
            if (applyLeveler) leveler.processFrame(frameInput)
            if (applyDeEsser) deEsser.processFrame(frameInput)
            firEq.pushFrame(frameInput)
        }
        AudioChainTelemetry.deEsserGainLin = if (applyDeEsser) deEsser.lastGainLin else 1.0
        AudioChainTelemetry.levelerGainLin = if (applyLeveler) leveler.lastGainLin else 1.0

        // Pass 2: how many output frames are ready? May be 0 (FIR EQ still
        // accumulating its first block after a mode switch / flush /
        // configure) or several blocks worth (if multiple completed in one
        // call).
        val outputFrames = firEq.outputFramesAvailable()
        if (outputFrames == 0) return

        val byteCount = outputFrames * channelCount * 2
        val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())

        for (frame in 0 until outputFrames) {
            firEq.popFrame(firPop)
            // Apply gain and compute input-meter peak (same telemetry the
            // PURE_IIR path feeds).
            var inFramePeak = 0.0
            for (ch in 0 until channelCount) {
                val v = firPop[ch] * gainLinear
                frameInput[ch] = v
                val a = if (v >= 0) v else -v
                if (a > inFramePeak) inFramePeak = a
            }
            AudioChainTelemetry.pushInputSample(inFramePeak)

            // Same post-gain chain as the PURE_IIR path: 2x upsample,
            // Warmth + Air at 2x, limiter at 2x, 2x downsample, gated TPDF
            // dither, int16 truncation. Reusing the existing scratch arrays.
            oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
            if (applyWarmth) {
                saturator.processFrame(up0Frame)
                saturator.processFrame(up1Frame)
            }
            if (applyAir) {
                airExciter.processFrame(up0Frame)
                airExciter.processFrame(up1Frame)
            }
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
            AudioChainTelemetry.fading = false  // no biquad cross-fade in FIR mode
            AudioChainTelemetry.ditherActive = ditherEnabled
        }
        out.flip()

        // Warmth/Air mirrors AFTER Pass 2, where those stages actually ran —
        // mirroring up top with deEsser/leveler (which run in Pass 1) would
        // publish a one-buffer-stale envelope, and on the first buffer after
        // a 0→on toggle would leak the pre-toggle peak (pendingReset is only
        // consumed inside processFrame). Zero-output buffers skip this via
        // the early return above; the envelope doesn't advance without
        // processFrame, so the last published value stays accurate.
        AudioChainTelemetry.warmthActivity =
            if (applyWarmth) saturator.currentActivityLin() else 0.0
        AudioChainTelemetry.airActivity =
            if (applyAir) airExciter.currentActivityLin() else 0.0
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

        val gainLinear = 10.0.pow(gainDb / 20.0) * sleepFadeLinear
        val invDrive = 32767.0
        // Warmth/Air must keep running through the drain: the FIR engine
        // buffers up to ~70 ms of REAL program audio upstream of the 2x
        // nonlinear stages, and the upsampler's own delay lines always hold
        // a pre-Warmth slice — draining without the stages would emit the
        // final moments of every track dry (an audible level/tone step at
        // track end with Warmth engaged).
        val applyWarmth = saturator.currentLevel() > 0
        val applyAir = airExciter.currentLevel() > 0
        // Post-gain chain delay at 1x rate. Common to both phase modes:
        // limiter.drainFrameCount is in 2x-rate frames (LA window in 2x
        // samples); divide by 2 for 1x equivalent. Add the oversampler's
        // combined up+down FIR group delay at 1x.
        val postGainDrain = oversampler.totalDelayFrames1x + limiter.drainFrameCount / 2

        if (phaseMode != PhaseMode.PURE_IIR) {
            // FIR drain has two stages stacked back-to-back:
            //   1) push zero frames into firEq to flush any partial input-
            //      accumulator block + the convolver's FDL contribution.
            //      FOUR blocks, not three: the 4096-tap kernel spans 4
            //      partitions of 1024, and the last real block's
            //      partition-3 contribution (taps 3072..4095 — the far
            //      half of a LINEAR/MIXED symmetric kernel's post-peak
            //      tail, ~23ms) only emerges after a 4th zero block.
            //      Worst case: partial accumulator (BLOCK_SIZE-1) + 3
            //      full partitions ≈ 4×BLOCK_SIZE.
            //   2) drain the post-gain chain (oversampler ↔ limiter) with
            //      zero input, same as the PURE_IIR drain below.
            val zeroFramesIn = FirEq.BLOCK_SIZE * 4
            for (ch in 0 until channelCount) frameInput[ch] = 0.0
            for (i in 0 until zeroFramesIn) firEq.pushFrame(frameInput)
            val firOut = firEq.outputFramesAvailable()
            val totalDrainFrames = firOut + postGainDrain
            AudioChainTelemetry.logEvent(
                "eos_drain",
                "fir: $firOut + post: $postGainDrain @1x"
            )
            val byteCount = totalDrainFrames * channelCount * 2
            if (byteCount <= 0) return
            val out = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
            // Stage 1: drain firEq output through gain → post-gain chain.
            for (frame in 0 until firOut) {
                firEq.popFrame(firPop)
                for (ch in 0 until channelCount) {
                    frameInput[ch] = firPop[ch] * gainLinear
                }
                oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
                if (applyWarmth) {
                    saturator.processFrame(up0Frame)
                    saturator.processFrame(up1Frame)
                }
                if (applyAir) {
                    airExciter.processFrame(up0Frame)
                    airExciter.processFrame(up1Frame)
                }
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
            // Stage 2: zero-input drain of the post-gain chain. The
            // upsampler's delay lines still hold pre-Warmth audio, so the
            // nonlinear stages stay in the loop here too.
            for (ch in 0 until channelCount) frameInput[ch] = 0.0
            for (frame in 0 until postGainDrain) {
                oversampler.upsampleFrame(frameInput, up0Frame, up1Frame)
                if (applyWarmth) {
                    saturator.processFrame(up0Frame)
                    saturator.processFrame(up1Frame)
                }
                if (applyAir) {
                    airExciter.processFrame(up0Frame)
                    airExciter.processFrame(up1Frame)
                }
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

        // PURE_IIR drain — push zeros through oversampler ↔ limiter and emit.
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
            if (applyWarmth) {
                saturator.processFrame(up0Frame)
                saturator.processFrame(up1Frame)
            }
            if (applyAir) {
                airExciter.processFrame(up0Frame)
                airExciter.processFrame(up1Frame)
            }
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

        /**
         * Pivot frequency for the tilt control's complementary shelf pair.
         * ~700 Hz sits in the meat of speech formants — tilting around it
         * trades "body" against "presence/air" symmetrically, which is the
         * classic single-knob tone behavior the control is modeled on.
         */
        const val TILT_PIVOT_HZ = 700f
    }
}
