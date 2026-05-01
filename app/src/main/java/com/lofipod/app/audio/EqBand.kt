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

    val VOICE_BOOST = listOf(
        EqBand(31f, -3f), EqBand(62f, -2f), EqBand(125f, -1f),
        EqBand(250f, 1f), EqBand(500f, 3f), EqBand(1000f, 4f),
        EqBand(2000f, 5f), EqBand(4000f, 4f),
        EqBand(8000f, 1f), EqBand(16000f, -1f)
    )

    val BASS_BOOST = listOf(
        EqBand(31f, 6f), EqBand(62f, 5f), EqBand(125f, 4f),
        EqBand(250f, 2f), EqBand(500f, 0f), EqBand(1000f, 0f),
        EqBand(2000f, 0f), EqBand(4000f, 0f),
        EqBand(8000f, 0f), EqBand(16000f, 0f)
    )

    val BRIGHT = listOf(
        EqBand(31f, 0f), EqBand(62f, 0f), EqBand(125f, 0f),
        EqBand(250f, -1f), EqBand(500f, -1f), EqBand(1000f, 0f),
        EqBand(2000f, 2f), EqBand(4000f, 4f),
        EqBand(8000f, 5f), EqBand(16000f, 4f)
    )
}
