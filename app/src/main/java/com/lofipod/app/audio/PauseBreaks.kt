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
 *
 * Density is grounded in measurement, not guesswork (2026-07-16, the
 * detector replicated offline at sensitivity 3 over canon episodes):
 * a 40:42 Piper Romans sermon carries 365 detected gaps of which 100
 * run >=1.2s and 40 run >=2s — at this density every selected break
 * still measures >=1.9s, comfortably structural — while a 12:33 Ask
 * Pastor John, edited tight, has NO gap over 1.8s and only 65 total,
 * so its selection can never balloon. The earlier one-per-90s guess
 * left the sermon with up to 4.8 MINUTES between jump targets.
 */
object PauseBreaks {

    /** Break density: one per this much episode ("one per 30-45s may
     *  be sufficient" — the user; 40s sits mid-range and, measured
     *  against real canon audio, keeps every selected sermon gap
     *  >=~1.9s). */
    private const val MS_PER_BREAK = 40_000L

    /** Selection bounds: even a short clip keeps its few biggest gaps;
     *  the cap covers a 40-minute sermon at full density (a 2h one
     *  degrades to ~one per 2 minutes — the bar is out of pixels long
     *  before that). */
    private const val MIN_BREAKS = 4L
    private const val MAX_BREAKS = 60L

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
