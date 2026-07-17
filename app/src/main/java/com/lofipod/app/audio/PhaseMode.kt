package com.lofipod.app.audio

import com.lofipod.app.data.Settings

/**
 * EQ phase-mode selector (v0.9.3+).
 *
 * Replaces the v0.8.0 `phaseModeLinear: Boolean` field on
 * [EqAudioProcessor] with a three-valued enum. The two FIR modes are
 * powered by the same [UpcConvolver] partitioned-convolution engine
 * (see [FirEq]) — only the kernel synthesis differs:
 *
 * - [PURE_IIR] — 10-band minimum-phase biquad cascade with equal-power
 *   crossfade on band changes. ~6.4 ms total chain latency. Lowest CPU.
 *   Default. Best for fast slider feel + low-power devices.
 *
 * - [MIN_FIR] — 4096-tap minimum-phase FIR via UPC + real-cepstrum
 *   kernel synthesis. ~29 ms total chain latency. No pre-ringing.
 *   Surgical magnitude precision; best for transient-heavy speech.
 *
 * - [LINEAR_FIR] — 4096-tap linear-phase FIR via UPC + symmetric
 *   kernel synthesis. ~70 ms total chain latency. Audible pre-ringing
 *   on sharp transients (the symmetric "cough that precedes itself"),
 *   in exchange for preserving the input signal's transient waveform
 *   shape exactly. The academically-pure option.
 *
 * - [MIXED] (v0.9.5) — hybrid: min-phase below ~120 Hz, linear-phase
 *   above, complementary cosine-ramp crossover. Pairs the no-pre-ringing
 *   bass response with transient-preserving mids/highs. Same ~70 ms
 *   latency as LINEAR_FIR. Mastering-EQ flex; niche but the audience
 *   that cares will recognize FabFilter Pro-Q3 "Natural Phase" /
 *   DMG EQuilibrium territory.
 *
 * The DataStore serializes this as a string ([Settings.phaseMode]) so a
 * future fifth mode can extend without a DataStore migration.
 */
enum class PhaseMode(val storageKey: String) {
    PURE_IIR(Settings.PHASE_MODE_PURE_IIR),
    MIN_FIR(Settings.PHASE_MODE_MIN_FIR),
    LINEAR_FIR(Settings.PHASE_MODE_LINEAR_FIR),
    MIXED(Settings.PHASE_MODE_MIXED);

    companion object {
        /** Resolve a [PhaseMode] from its DataStore storage key. Unknown
         *  values fall back to [PURE_IIR] — the safe default. */
        fun fromStorageKey(value: String?): PhaseMode = when (value) {
            Settings.PHASE_MODE_MIN_FIR -> MIN_FIR
            Settings.PHASE_MODE_LINEAR_FIR -> LINEAR_FIR
            Settings.PHASE_MODE_MIXED -> MIXED
            else -> PURE_IIR
        }
    }
}
