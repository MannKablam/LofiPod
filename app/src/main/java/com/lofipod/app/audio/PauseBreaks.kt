package com.lofipod.app.audio

/**
 * The single definition of a structural BREAK — the pause worth jumping
 * back to. One selection, two consumers: the collapsed scrubber draws
 * these as its cut marks, and the pause-skip button walks back through
 * them — so what the bar shows is exactly where the button lands.
 *
 * The raw detector (PauseTapProcessor live, EpisodeAudioAnalyzer
 * offline) flags EVERY audible gap at the configured sensitivity —
 * in spoken word that's nearly every sentence boundary, hundreds per
 * episode. Breaks are the top of that list by GAP LENGTH: the pause
 * before a new thought runs longer than the pause between sentences,
 * so the longest gaps are the section seams / main-idea boundaries.
 * How many survive scales with episode length ([MS_PER_BREAK]) so a
 * 10-minute study keeps a handful of marks and a 2-hour sermon tops
 * out at [MAX_BREAKS]. The sensitivity dialog still tunes the raw
 * detection underneath; selection rides on whatever it detects.
 */
object PauseBreaks {

    /** Break density: one per this much episode. */
    private const val MS_PER_BREAK = 90_000L

    /** Selection bounds: even a short clip keeps its few biggest gaps;
     *  more than [MAX_BREAKS] reads as a barcode on a phone-width bar
     *  (same order as the scrubber's scripture-marker cap). */
    private const val MIN_BREAKS = 4L
    private const val MAX_BREAKS = 40L

    /** Land this far before speech resumes, inside the gap, so the next
     *  phrase starts cleanly without replaying the whole silence. */
    private const val PRE_ROLL_MS = 200L

    /**
     * Select the breaks from [spans]: the longest gaps, at the
     * duration-scaled count, restored to chronological order. Accepts
     * either source's list — the analyzer's full-file scan or the live
     * tap's session-visited snapshot (unordered, possibly with
     * duplicates from re-decoded regions; duplicates merely tie).
     */
    fun select(
        spans: List<PauseTapProcessor.PauseSpan>,
        durationMs: Long,
    ): List<PauseTapProcessor.PauseSpan> {
        if (spans.isEmpty() || durationMs <= 0) return emptyList()
        val k = (durationMs / MS_PER_BREAK).coerceIn(MIN_BREAKS, MAX_BREAKS).toInt()
        if (spans.size <= k) return spans.sortedBy { it.startMs }
        return spans
            .sortedByDescending { it.endMs - it.startMs }
            .subList(0, k)
            .sortedBy { it.startMs }
    }

    /**
     * Seek target for "back to the previous break": the tail of the most
     * recent break that ended at least [guardMs] before [positionMs] —
     * the guard makes repeated taps walk back through successive breaks
     * instead of re-finding the one just landed after. Null when nothing
     * qualifies (caller falls back to a plain skip).
     */
    fun targetBefore(
        breaks: List<PauseTapProcessor.PauseSpan>,
        positionMs: Long,
        guardMs: Long = 400L,
    ): Long? {
        val cutoff = positionMs - guardMs
        var best: PauseTapProcessor.PauseSpan? = null
        for (p in breaks) {
            if (p.endMs <= cutoff && (best == null || p.endMs > best.endMs)) best = p
        }
        val b = best ?: return null
        return (b.endMs - PRE_ROLL_MS).coerceAtLeast(b.startMs)
    }
}
