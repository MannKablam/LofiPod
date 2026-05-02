package com.lofipod.app.audio

/**
 * Represents one band of the graphic EQ.
 * gainDb: -12..+12 typical. centerHz: band center frequency.
 */
data class EqBand(
    val centerHz: Float,
    val gainDb: Float = 0f,
    val qFactor: Float = 1.41f   // ~ octave bandwidth
)

object EqPresets {
    // 10-band ISO graphic EQ frequencies — standard for podcasts/music
    val DEFAULT_BANDS: List<EqBand> = listOf(
        EqBand(31f), EqBand(62f), EqBand(125f), EqBand(250f),
        EqBand(500f), EqBand(1000f), EqBand(2000f),
        EqBand(4000f), EqBand(8000f), EqBand(16000f)
    )

    val FLAT = DEFAULT_BANDS

    /** Reins in low-end mud on male voice podcasts where bass overshadows the
     *  vocal qualities. L1 is gentle, L2 is aggressive (heavy male monologue). */
    val BOOM_KILL_L1 = listOf(
        EqBand(31f, -4f), EqBand(62f, -3f), EqBand(125f, -2f),
        EqBand(250f, -1f), EqBand(500f, 0f), EqBand(1000f, 1f),
        EqBand(2000f, 1f), EqBand(4000f, 1f),
        EqBand(8000f, 0f), EqBand(16000f, 0f)
    )
    val BOOM_KILL_L2 = listOf(
        EqBand(31f, -8f), EqBand(62f, -6f), EqBand(125f, -4f),
        EqBand(250f, -2f), EqBand(500f, 0f), EqBand(1000f, 2f),
        EqBand(2000f, 3f), EqBand(4000f, 2f),
        EqBand(8000f, 1f), EqBand(16000f, 0f)
    )

    /** L1 mild upper-mid lift; L2 more aggressive presence push. */
    val VOICE_L1 = listOf(
        EqBand(31f, -3f), EqBand(62f, -2f), EqBand(125f, -1f),
        EqBand(250f, 1f), EqBand(500f, 3f), EqBand(1000f, 4f),
        EqBand(2000f, 5f), EqBand(4000f, 4f),
        EqBand(8000f, 1f), EqBand(16000f, -1f)
    )
    val VOICE_L2 = listOf(
        EqBand(31f, -5f), EqBand(62f, -4f), EqBand(125f, -2f),
        EqBand(250f, 2f), EqBand(500f, 5f), EqBand(1000f, 7f),
        EqBand(2000f, 8f), EqBand(4000f, 6f),
        EqBand(8000f, 2f), EqBand(16000f, -1f)
    )

    /** Bass enhancement, three intensities. */
    val BASS_L1 = listOf(
        EqBand(31f, 3f), EqBand(62f, 2f), EqBand(125f, 1f),
        EqBand(250f, 0f), EqBand(500f, 0f), EqBand(1000f, 0f),
        EqBand(2000f, 0f), EqBand(4000f, 0f),
        EqBand(8000f, 0f), EqBand(16000f, 0f)
    )
    val BASS_L2 = listOf(
        EqBand(31f, 6f), EqBand(62f, 5f), EqBand(125f, 4f),
        EqBand(250f, 2f), EqBand(500f, 0f), EqBand(1000f, 0f),
        EqBand(2000f, 0f), EqBand(4000f, 0f),
        EqBand(8000f, 0f), EqBand(16000f, 0f)
    )
    val BASS_L3 = listOf(
        EqBand(31f, 9f), EqBand(62f, 8f), EqBand(125f, 6f),
        EqBand(250f, 3f), EqBand(500f, 1f), EqBand(1000f, 0f),
        EqBand(2000f, 0f), EqBand(4000f, 0f),
        EqBand(8000f, 0f), EqBand(16000f, 0f)
    )

    /** High-end air, two intensities. */
    val BRIGHT_L1 = listOf(
        EqBand(31f, 0f), EqBand(62f, 0f), EqBand(125f, 0f),
        EqBand(250f, -1f), EqBand(500f, -1f), EqBand(1000f, 0f),
        EqBand(2000f, 2f), EqBand(4000f, 4f),
        EqBand(8000f, 5f), EqBand(16000f, 4f)
    )
    val BRIGHT_L2 = listOf(
        EqBand(31f, -2f), EqBand(62f, -2f), EqBand(125f, -1f),
        EqBand(250f, -2f), EqBand(500f, -2f), EqBand(1000f, 1f),
        EqBand(2000f, 4f), EqBand(4000f, 7f),
        EqBand(8000f, 8f), EqBand(16000f, 7f)
    )

    /**
     * Cuts the worst rumble (sub-bass) AND the harshest upper-mid / sibilant
     * region in one preset — useful for compressed talk-radio sources where
     * the low-end booms and the 5–10 kHz region is fatiguing. L2 is the
     * user-tuned target curve; L1 is roughly half-strength for less invasive
     * shaping; L3 leans further into the cuts, clipped at -12 dB headroom.
     */
    val HARSH_KILL_L1 = listOf(
        EqBand(31f, -6f), EqBand(62f, -4f), EqBand(125f, -2f),
        EqBand(250f, -1f), EqBand(500f, -3f), EqBand(1000f, -1f),
        EqBand(2000f, -1f), EqBand(4000f, -1f),
        EqBand(8000f, -2f), EqBand(16000f, -1f)
    )
    val HARSH_KILL_L2 = listOf(
        EqBand(31f, -12f), EqBand(62f, -9f), EqBand(125f, -5f),
        EqBand(250f, -3f), EqBand(500f, -6f), EqBand(1000f, -2f),
        EqBand(2000f, -2f), EqBand(4000f, -2f),
        EqBand(8000f, -5f), EqBand(16000f, -3f)
    )
    val HARSH_KILL_L3 = listOf(
        EqBand(31f, -12f), EqBand(62f, -12f), EqBand(125f, -7f),
        EqBand(250f, -4f), EqBand(500f, -8f), EqBand(1000f, -3f),
        EqBand(2000f, -3f), EqBand(4000f, -3f),
        EqBand(8000f, -7f), EqBand(16000f, -4f)
    )
}

/**
 * The named presets in the EQ UI. Each carries an ordered list of intensity
 * levels — taps cycle through 0 (off / flat) → 1 → ... → maxLevel → 0.
 * The button background renders this as N evenly-split divisions filled in
 * up to the active level.
 */
enum class PresetId(val displayName: String, val levels: List<List<EqBand>>) {
    BOOM_KILL("Boom-kill", listOf(EqPresets.BOOM_KILL_L1, EqPresets.BOOM_KILL_L2)),
    HARSH_KILL("Harsh-kill", listOf(
        EqPresets.HARSH_KILL_L1, EqPresets.HARSH_KILL_L2, EqPresets.HARSH_KILL_L3
    )),
    VOICE("Voice", listOf(EqPresets.VOICE_L1, EqPresets.VOICE_L2)),
    BASS("Bass", listOf(EqPresets.BASS_L1, EqPresets.BASS_L2, EqPresets.BASS_L3)),
    BRIGHT("Bright", listOf(EqPresets.BRIGHT_L1, EqPresets.BRIGHT_L2));

    val maxLevel: Int get() = levels.size
}
