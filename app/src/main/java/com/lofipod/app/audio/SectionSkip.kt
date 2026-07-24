package com.lofipod.app.audio

/**
 * The ">| skip past sponsor/music section" target finder — the forward
 * mirror of [PauseBreaks]. Tap-driven by design: the listener already
 * knows an ad / musical interlude is playing; this answers only "where
 * does the different-sounding stretch END?"
 *
 * Texture signal: the analyzer's per-second LSTER curve
 * ([EpisodeAnalysis.lsterPerSec]). Clean conversational speech scores
 * high — 20 ms frames keep dipping below half the local mean in the
 * gaps between syllables and phrases — while music, and speech over a
 * music bed (the classic produced sponsor read), hold a filled-in
 * energy floor and score low. From the tap position we scan forward
 * for the first moment the texture RETURNS to the episode's own
 * speech baseline and stays there, then land on a natural pause
 * boundary when one is close (same landmark family as the |< button),
 * biased so the resume point is never chopped.
 *
 * Tuned 2026-07-17 against real canon audio with the detector
 * replicated offline (session scratchpad, skip_sim.py):
 *   - Compelled ep (1h): music-bedded stretches at ~13:19-14:31 /
 *     ~36:37-37:06 / ~52:x resolve to 20-50 s skips landing at the
 *     texture recovery; mid-testimony mis-taps fall back.
 *   - APJ: tap inside the intro theme lands at the spoken start
 *     (pause-aligned, ~0:18); mid-speech taps fall back; tapping the
 *     outro hops its wobble without jumping backward.
 *   - NTWAF: the repeated 29 s produced mid-roll resolves to a ~50 s
 *     skip. (Its TALKY intro sponsor block reads as speech texture and
 *     is invisible here — that case needs the cross-episode
 *     fingerprint matcher validated offline the same day; see
 *     BUILD_LOG v0.11.4-dev, phase-2 candidate.)
 *
 * Null result = "nothing recognizable ahead" — the caller falls back
 * to a plain +30 s skip, so the button always does something sane.
 */
object SectionSkip {

    /** Median-smoothing radius over the per-second curve (±2 s). */
    private const val SMOOTH_RADIUS = 2

    /** Never land closer than this ahead of the tap. */
    private const val MIN_AHEAD_S = 4

    /** Seconds of sustained speech-texture that count as "recovered".
     *  Shorter windows false-trigger inside ads on brief dry voiceover
     *  moments (measured: 8 s landed ~15 s early on the Compelled
     *  mid-roll; 12 s tracks its true end). */
    private const val SUSTAIN_S = 12

    /** A recovery closer than this to the tap is a mis-tap — UNLESS the
     *  tap second itself is below speech texture (then the listener
     *  really is inside a short section and the nearby edge is the
     *  honest target, e.g. tapping late in a 15 s intro theme). */
    private const val MIN_SECTION_S = 12

    /** How far ahead to search before giving up. Sponsor blocks run
     *  20-90 s; 4 minutes covers chained double-ads with margin. */
    private const val HORIZON_S = 240

    /** Episode speech baseline = this percentile of the smoothed curve
     *  over non-silent seconds (speech dominates any episode's runtime,
     *  so a mid-upper percentile reads as "normal talk"). */
    private const val BASELINE_PERCENTILE = 0.60

    /** Speech threshold = max(fraction * baseline, floor). The floor
     *  keeps quiet-mastered episodes from degenerating to zero. */
    private const val SPEECH_FRACTION = 0.55f
    private const val SPEECH_FLOOR = 0.16f

    /** Pause-end alignment window around the texture edge, and the
     *  same land-just-before-speech pre-roll the |< button uses. */
    private const val ALIGN_S = 6
    private const val PRE_ROLL_MS = 200L

    /** Lead-in slop: a tap this far BEFORE a known shared span still
     *  counts as "inside" it (the listener heard the ad start before
     *  the detector's boundary). */
    private const val SPAN_LEAD_IN_MS = 2_000L

    /** Chained spans (double ads) with gaps up to this merge into one
     *  skip. */
    private const val SPAN_CHAIN_GAP_MS = 5_000L

    /**
     * Landing position for a tap at [positionMs], or null when nothing
     * recognizable lies ahead (caller should fall back to a plain
     * skip). Two signals, in precedence order:
     *
     *  1. MATCHED SHARED SPANS ([adSpans], from AdSpanMatcher): exact
     *     stretches known to repeat verbatim in sibling episodes. A tap
     *     inside one (or [SPAN_LEAD_IN_MS] before it) lands at the end
     *     of the contiguous span chain — precise boundaries, catches
     *     talky sponsor reads the texture signal can't see.
     *  2. TEXTURE RECOVERY (the LSTER curve): everything else.
     *
     * All inputs are media-time domained.
     */
    fun targetAfter(
        positionMs: Long,
        lsterPerSec: ByteArray,
        pauses: List<PauseTapProcessor.PauseSpan>,
        adSpans: List<PauseTapProcessor.PauseSpan> = emptyList(),
    ): Long? {
        spanChainEnd(positionMs, adSpans)?.let { chainEnd ->
            return alignToPause(chainEnd, positionMs, pauses)
        }
        val n = lsterPerSec.size
        if (n < SUSTAIN_S * 2) return null
        val tap = (positionMs / 1000L).toInt().coerceIn(0, n - 1)
        if (tap >= n - MIN_AHEAD_S) return null

        // Decode + median-smooth. Silent seconds decode to -1: below
        // every threshold (silence is not speech texture), excluded
        // from the baseline.
        val curve = FloatArray(n) { i ->
            val v = lsterPerSec[i].toInt() and 0xFF
            if (v == EpisodeAudioAnalyzer.LSTER_SILENT) -1f
            else v / EpisodeAudioAnalyzer.LSTER_SCALE.toFloat()
        }
        val sm = FloatArray(n)
        val scratch = FloatArray(2 * SMOOTH_RADIUS + 1)
        for (i in 0 until n) {
            val lo = maxOf(0, i - SMOOTH_RADIUS)
            val hi = minOf(n - 1, i + SMOOTH_RADIUS)
            var m = 0
            for (j in lo..hi) scratch[m++] = curve[j]
            java.util.Arrays.sort(scratch, 0, m)
            sm[i] = scratch[m / 2]
        }

        // Baseline over non-silent seconds.
        val loud = sm.filter { it >= 0f }.sorted()
        if (loud.size < SUSTAIN_S * 2) return null
        val baseline = loud[(loud.size * BASELINE_PERCENTILE).toInt()
            .coerceAtMost(loud.size - 1)]
        val speechT = maxOf(SPEECH_FRACTION * baseline, SPEECH_FLOOR)

        // First index u >= tap+MIN_AHEAD where SUSTAIN_S consecutive
        // seconds hold speech texture. The inner failure fast-forwards
        // the candidate past the failing second.
        val lastStart = minOf(tap + HORIZON_S, n - SUSTAIN_S)
        var u = -1
        var cand = tap + MIN_AHEAD_S
        outer@ while (cand <= lastStart) {
            for (k in cand until cand + SUSTAIN_S) {
                if (sm[k] < speechT) {
                    cand = k + 1
                    continue@outer
                }
            }
            u = cand
            break
        }
        if (u < 0) return null
        if (u - tap < MIN_SECTION_S && sm[tap] >= speechT) return null

        // Align to the latest pause end near the texture edge — but only
        // ever FORWARD of the tap, so alignment can't fling the listener
        // backward into the section they just asked to leave.
        return alignToPause(u * 1000L, positionMs, pauses)
    }

    /**
     * End of the contiguous matched-span chain covering [positionMs]
     * (with lead-in slop), or null when the tap isn't inside one, or
     * the chain end is already effectively behind the playhead.
     */
    private fun spanChainEnd(
        positionMs: Long,
        adSpans: List<PauseTapProcessor.PauseSpan>,
    ): Long? {
        if (adSpans.isEmpty()) return null
        val sorted = adSpans.sortedBy { it.startMs }
        var idx = -1
        for (i in sorted.indices) {
            val s = sorted[i]
            if (positionMs >= s.startMs - SPAN_LEAD_IN_MS && positionMs < s.endMs) {
                idx = i
                break
            }
        }
        if (idx < 0) return null
        var end = sorted[idx].endMs
        for (i in idx + 1 until sorted.size) {
            if (sorted[i].startMs - end <= SPAN_CHAIN_GAP_MS) {
                end = maxOf(end, sorted[i].endMs)
            } else break
        }
        return end.takeIf { it > positionMs + 500L }
    }

    /** Land at the latest pause end within ±[ALIGN_S] of [edgeMs]
     *  (forward-of-tap only, [PRE_ROLL_MS] before speech resumes), or
     *  at the edge itself when no pause is nearby. */
    private fun alignToPause(
        edgeMs: Long,
        positionMs: Long,
        pauses: List<PauseTapProcessor.PauseSpan>,
    ): Long {
        var best = -1L
        for (p in pauses) {
            val e = p.endMs
            if (e > positionMs + 1000L &&
                e >= edgeMs - ALIGN_S * 1000L &&
                e <= edgeMs + ALIGN_S * 1000L &&
                e > best
            ) best = e
        }
        return if (best > 0) (best - PRE_ROLL_MS).coerceAtLeast(positionMs + 500L)
        else edgeMs
    }
}
