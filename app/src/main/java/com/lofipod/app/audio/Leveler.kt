package com.lofipod.app.audio

import kotlin.math.log10

/**
 * Vocal leveler — a slow "gain rider" that walks quiet preachers up and
 * hot masters down toward one comfortable listening level, the job a
 * mastering-suite maximizer/rider does for spoken word. NOT a compressor:
 * gain moves at single-digit dB **per second**, so word-level dynamics
 * and emphasis survive untouched; only the minute-scale loudness drift
 * between (and within) episodes is flattened.
 *
 * Mechanics: a slow linked-channel amplitude envelope (~150 ms attack /
 * ~400 ms release) approximates the running speech level. The desired
 * gain is `TARGET_LEVEL / envelope`, clamped to the level's ± range, and
 * the applied gain slews toward it multiplicatively at a per-second dB
 * rate (precomputed as a per-frame factor — no log/pow in the hot loop).
 * Downward correction is ~3x faster than upward so a suddenly-hot signal
 * is reined in promptly while quiet passages are raised gently.
 *
 * Safety rails: below [GATE_FLOOR] (silence, pauses, tails) the gain
 * FREEZES — riding noise floors up is the classic AGC failure and the
 * gate is what prevents it. After a reset the gain also holds at unity
 * for [WARMUP_SECONDS] while the envelope acquires the signal.
 *
 * Zero latency; runs at 1x, pre-EQ (before tonal shaping so the ride is
 * judged on the source, and upstream of the limiter which catches any
 * transient the ride boosts into the ceiling).
 */
class Leveler {

    /** 0 = off, 1..3 = gentle (±4 dB) / standard (±7 dB) / strong (±10 dB). */
    @Volatile private var level: Int = 0
    @Volatile private var pendingReset: Boolean = false

    /** Applied gain (linear) for diagnostics; volatile for UI reads. */
    @Volatile var lastGainLin: Double = 1.0
        private set

    fun lastGainDb(): Double = 20.0 * log10(lastGainLin.coerceAtLeast(1e-6))

    private var sampleRate = 0
    private var channelCount = 0

    private var env = 0.0
    private var gainLin = 1.0
    private var warmupFrames = 0

    private var envAttack = 0.0
    private var envRelease = 0.0

    private var appliedLevel = -1
    private var maxGainLin = 1.0
    private var minGainLin = 1.0
    private var riseStep = 1.0   // per-frame multiplicative gain step upward
    private var fallStep = 1.0   // per-frame multiplicative gain step downward

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
        envAttack = kotlin.math.exp(-1.0 / (0.150 * sampleRate))
        envRelease = kotlin.math.exp(-1.0 / (0.400 * sampleRate))
        appliedLevel = -1
        reset()
    }

    fun reset() {
        env = 0.0
        gainLin = 1.0
        lastGainLin = 1.0
        warmupFrames = (WARMUP_SECONDS * sampleRate).toInt()
    }

    /** In-place. Call only when [currentLevel] > 0 and after [configure]. */
    fun processFrame(frame: DoubleArray) {
        if (pendingReset) {
            pendingReset = false
            reset()
        }
        val l = level
        if (l != appliedLevel) {
            val (rangeDb, riseDbPerSec, fallDbPerSec) = when (l) {
                1 -> Triple(4.0, 2.5, 8.0)
                2 -> Triple(7.0, 3.5, 12.0)
                else -> Triple(10.0, 5.0, 16.0)
            }
            maxGainLin = pow10(rangeDb / 20.0)
            minGainLin = pow10(-rangeDb / 20.0)
            riseStep = pow10(riseDbPerSec / (20.0 * sampleRate))
            fallStep = pow10(-fallDbPerSec / (20.0 * sampleRate))
            appliedLevel = l
        }

        // Linked slow envelope of the loudest channel.
        var peak = 0.0
        for (ch in 0 until channelCount) {
            val a = if (frame[ch] >= 0) frame[ch] else -frame[ch]
            if (a > peak) peak = a
        }
        env = if (peak > env) peak + (env - peak) * envAttack
        else peak + (env - peak) * envRelease

        // Ride only on real signal, after warmup; otherwise hold.
        if (warmupFrames > 0) {
            warmupFrames--
        } else if (env > GATE_FLOOR) {
            val desired = (TARGET_LEVEL / env).coerceIn(minGainLin, maxGainLin)
            if (gainLin < desired) {
                gainLin *= riseStep
                if (gainLin > desired) gainLin = desired
            } else if (gainLin > desired) {
                gainLin *= fallStep
                if (gainLin < desired) gainLin = desired
            }
        }
        lastGainLin = gainLin

        for (ch in 0 until channelCount) frame[ch] *= gainLin
    }

    private fun pow10(x: Double): Double = kotlin.math.exp(x * LN10)

    companion object {
        /** Target for the decayed-peak envelope, ≈ -16 dBFS. The 150/400 ms
         *  follower reads several dB above true RMS on speech, so a peak-env
         *  target of -16 lands the program around a typical -21..-19 LUFS-ish
         *  podcast loudness — engaging the Leveler on well-mastered content
         *  should feel near-transparent, not quieter. (First cut was -20,
         *  which pulled everything conservative.) */
        private const val TARGET_LEVEL = 0.16
        /** ≈ -50 dBFS: below this, freeze the ride (pauses, room tone). */
        private const val GATE_FLOOR = 0.003
        private const val WARMUP_SECONDS = 0.3
        private const val LN10 = 2.302585092994046
    }
}
