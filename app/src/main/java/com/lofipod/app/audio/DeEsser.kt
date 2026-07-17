package com.lofipod.app.audio

import kotlin.math.log10

/**
 * Split-band de-esser — tames sibilance ("s"/"t"/"ch" spit) the way the
 * desktop voice suites do, with a level-independent detector so it works
 * the same on a quiet archive rip and a hot modern master.
 *
 * Detector: a high-passed sidechain (everything above [SPLIT_HZ]) and the
 * full-band signal each feed a fast peak envelope, linked across channels.
 * Normal voiced speech keeps the sibilance band well below the full-band
 * level; during an "s" the two converge. When the sibilance envelope rises
 * within `marginDb` of the full-band envelope, gain reduction kicks in at
 * a 1 dB/dB slope (compresses the band back to the margin), capped at
 * `maxReductionDb`. Comparing band-vs-band instead of band-vs-threshold is
 * what makes the detector level-independent.
 *
 * Reduction is applied ONLY to the high band via a complementary split:
 * `low = LP(x); high = x - low; y = low + high * gain` — exact
 * reconstruction at unity gain, so the de-esser is transparent whenever it
 * isn't actively reducing. Gain is smoothed with a fast-attack /
 * slow-release one-pole so onsets are caught without chatter.
 *
 * Zero latency, per-sample IIR + envelope math only. Runs at 1x, pre-EQ.
 * All state is audio-thread-owned; [setLevel] is UI-thread-safe (level is
 * volatile, a pending reset is handed to the audio thread as a flag).
 */
class DeEsser {

    /** 0 = off, 1..3 = gentle / standard / strong. */
    @Volatile private var level: Int = 0
    /** Set when leaving level 0 so stale filter state from before the
     *  bypass window is cleared by the AUDIO thread, not the UI thread. */
    @Volatile private var pendingReset: Boolean = false

    /** Last applied high-band gain (linear, 1.0 = no reduction). Volatile
     *  so diagnostics can read it from the UI thread. */
    @Volatile var lastGainLin: Double = 1.0
        private set

    /** Reduction in dB for display (0 or negative). Computed lazily from
     *  [lastGainLin] — keeps log10 out of the audio hot loop. */
    fun lastReductionDb(): Double = 20.0 * log10(lastGainLin.coerceAtLeast(1e-6))

    private var sampleRate = 0
    private var channelCount = 0
    private var splitLp: Array<Biquad> = emptyArray()
    private var sideHp: Array<Biquad> = emptyArray()

    // Linked (cross-channel) envelopes, linear amplitude domain.
    private var sibEnv = 0.0
    private var fullEnv = 0.0
    private var gainLin = 1.0

    // One-pole envelope coefficients, derived from sample rate.
    private var envAttack = 0.0
    private var envRelease = 0.0
    private var grAttack = 0.0
    private var grRelease = 0.0

    // Per-level params, refreshed on the audio thread when [level] changes.
    private var appliedLevel = -1
    private var marginLin = 1.0     // 10^(marginDb/20)
    private var maxGainLin = 1.0    // 10^(-maxReductionDb/20)

    fun setLevel(l: Int) {
        val clamped = l.coerceIn(0, 3)
        if (clamped == level) return
        if (level == 0 && clamped > 0) pendingReset = true
        level = clamped
    }

    fun currentLevel(): Int = level

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        splitLp = Array(channelCount) { Biquad().apply { setLowpass(sampleRate, SPLIT_HZ) } }
        sideHp = Array(channelCount) { Biquad().apply { setHighpass(sampleRate, SPLIT_HZ) } }
        // Envelope time constants: exp(-1 / (tc_seconds * sampleRate)).
        envAttack = onePole(0.003)
        envRelease = onePole(0.120)
        grAttack = onePole(0.001)
        grRelease = onePole(0.070)
        appliedLevel = -1
        reset()
    }

    fun reset() {
        for (b in splitLp) b.reset()
        for (b in sideHp) b.reset()
        sibEnv = 0.0
        fullEnv = 0.0
        gainLin = 1.0
        lastGainLin = 1.0
    }

    /** In-place. Call only when [currentLevel] > 0 and after [configure]. */
    fun processFrame(frame: DoubleArray) {
        if (pendingReset) {
            pendingReset = false
            reset()
        }
        val l = level
        if (l != appliedLevel) {
            // marginDb: how close (in dB) the sibilance band must get to the
            // full-band level before reduction starts. maxReductionDb caps GR.
            val (marginDb, maxReductionDb) = when (l) {
                1 -> -3.0 to 4.0
                2 -> -6.0 to 7.0
                else -> -9.0 to 10.0
            }
            marginLin = pow10(marginDb / 20.0)
            maxGainLin = pow10(-maxReductionDb / 20.0)
            appliedLevel = l
        }

        // Detector: linked peaks of the sidechain + full band.
        var sidePeak = 0.0
        var fullPeak = 0.0
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            val s = sideHp[ch].process(x)
            val sa = if (s >= 0) s else -s
            if (sa > sidePeak) sidePeak = sa
            val xa = if (x >= 0) x else -x
            if (xa > fullPeak) fullPeak = xa
        }
        sibEnv = follow(sibEnv, sidePeak, envAttack, envRelease)
        fullEnv = follow(fullEnv, fullPeak, envAttack, envRelease)

        // Target gain: compress the sibilance band back down to
        // fullEnv * marginLin (1 dB reduction per dB of excess), capped.
        val threshold = fullEnv * marginLin
        val target = if (sibEnv > threshold && sibEnv > SILENCE_FLOOR) {
            (threshold / sibEnv).coerceAtLeast(maxGainLin)
        } else 1.0
        // Fast attack (gain falling), slower release (gain recovering).
        gainLin = if (target < gainLin) {
            target + (gainLin - target) * grAttack
        } else {
            target + (gainLin - target) * grRelease
        }
        lastGainLin = gainLin

        // Apply to the high band only.
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            val low = splitLp[ch].process(x)
            frame[ch] = low + (x - low) * gainLin
        }
    }

    private fun follow(env: Double, peak: Double, attack: Double, release: Double): Double =
        if (peak > env) peak + (env - peak) * attack
        else peak + (env - peak) * release

    private fun onePole(tcSeconds: Double): Double =
        kotlin.math.exp(-1.0 / (tcSeconds * sampleRate))

    private fun pow10(x: Double): Double = kotlin.math.exp(x * LN10)

    companion object {
        /** Sibilance split point. 5.6 kHz keeps voiced consonants below the
         *  reduction band while catching "s"/"sh" energy (6-9 kHz core). */
        private const val SPLIT_HZ = 5_600f
        /** Below this envelope the detector idles — don't chase noise. */
        private const val SILENCE_FLOOR = 1e-4
        private const val LN10 = 2.302585092994046
    }
}
