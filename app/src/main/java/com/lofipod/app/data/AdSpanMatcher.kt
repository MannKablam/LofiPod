package com.lofipod.app.data

import com.lofipod.app.audio.AudioFingerprint

/**
 * Cross-episode shared-audio matcher: given two episodes' fingerprint
 * token streams ([AudioFingerprint], one byte per 100 ms), find the
 * stretches that occur VERBATIM in both. Produced sponsor reads,
 * promos, and theme music repeat across a feed's episodes; interviews
 * and sermons never do — so shared stretches are precisely-bounded
 * skippable sections, the exact spans the >| button wants.
 *
 * Method (validated offline 2026-07-17 against real canon audio; the
 * pipeline found NTWAF's repeated 138 s intro sponsor block and 29 s
 * mid-roll across independently encoded episodes, matched nothing
 * between different shows, and matched only the show theme between
 * Compelled episodes):
 *   - [SHINGLE] consecutive tokens (1.2 s) packed into a 60-bit key;
 *   - episode B's shingles hashed; A's probed against the index;
 *   - hits voted into 2 s offset-delta bins — dynamic ad insertion
 *     places the same ad at different offsets per episode, and each
 *     shared instance produces its own offset peak;
 *   - bins with enough hits are split into runs (gap ≤ [RUN_GAP_MS]),
 *     runs ≥ [MIN_SEG_MS] become spans in BOTH episodes;
 *   - per-episode overlapping spans merge.
 *
 * Pure function, no I/O — the repository owns partner discovery and
 * persistence.
 */
object AdSpanMatcher {

    const val SHINGLE = 12
    private const val HOP_MS = AudioFingerprint.HOP_MS
    private const val OFFSET_BIN_MS = 2_000L
    private const val MIN_SEG_MS = 12_000L
    private const val RUN_GAP_MS = 4_000L

    /** Fraction of a minimum segment's shingles that must match for its
     *  offset bin to be considered at all — tolerant of token dropout
     *  at splice seams and codec variance. */
    private const val VOTE_FRACTION = 0.25

    data class SpanMs(val startMs: Long, val endMs: Long)

    class MatchResult(val inA: List<SpanMs>, val inB: List<SpanMs>)

    fun match(tokensA: ByteArray, tokensB: ByteArray): MatchResult {
        val na = tokensA.size - SHINGLE + 1
        val nb = tokensB.size - SHINGLE + 1
        if (na <= 0 || nb <= 0) return MatchResult(emptyList(), emptyList())

        // Index B.
        val index = HashMap<Long, MutableList<Int>>(nb * 2)
        var key = 0L
        val mask = (1L shl (5 * SHINGLE)) - 1L
        for (j in 0 until tokensB.size) {
            key = ((key shl 5) or (tokensB[j].toLong() and 0x1FL)) and mask
            if (j >= SHINGLE - 1) {
                index.getOrPut(key) { ArrayList(1) }.add(j - SHINGLE + 1)
            }
        }

        // Probe A; vote (i - j) offset bins.
        val votes = HashMap<Long, MutableList<IntArray>>()
        key = 0L
        for (t in 0 until tokensA.size) {
            key = ((key shl 5) or (tokensA[t].toLong() and 0x1FL)) and mask
            if (t < SHINGLE - 1) continue
            val i = t - SHINGLE + 1
            val js = index[key] ?: continue
            for (j in js) {
                val bin = Math.floorDiv((i - j) * HOP_MS, OFFSET_BIN_MS)
                votes.getOrPut(bin) { ArrayList() }.add(intArrayOf(i, j))
            }
        }

        val minHits = (MIN_SEG_MS / HOP_MS * VOTE_FRACTION).toInt()
        val gapHops = (RUN_GAP_MS / HOP_MS).toInt()
        val minSegHops = (MIN_SEG_MS / HOP_MS).toInt()

        val spansA = ArrayList<SpanMs>()
        val spansB = ArrayList<SpanMs>()
        for (pairs in votes.values) {
            if (pairs.size < minHits) continue
            pairs.sortBy { it[0] }
            var runStart = 0
            for (p in 1..pairs.size) {
                val broke = p == pairs.size ||
                    pairs[p][0] - pairs[p - 1][0] > gapHops
                if (!broke) continue
                val first = pairs[runStart]
                val last = pairs[p - 1]
                if (last[0] - first[0] >= minSegHops) {
                    spansA.add(SpanMs(
                        first[0] * HOP_MS,
                        (last[0] + SHINGLE) * HOP_MS,
                    ))
                    spansB.add(SpanMs(
                        first[1] * HOP_MS,
                        (last[1] + SHINGLE) * HOP_MS,
                    ))
                }
                runStart = p
            }
        }
        return MatchResult(mergeSpans(spansA), mergeSpans(spansB))
    }

    /** Sort + merge overlapping/adjacent (≤2 s apart) spans. */
    fun mergeSpans(spans: List<SpanMs>): List<SpanMs> {
        if (spans.size <= 1) return spans
        val sorted = spans.sortedBy { it.startMs }
        val out = ArrayList<SpanMs>()
        var cur = sorted[0]
        for (k in 1 until sorted.size) {
            val s = sorted[k]
            cur = if (s.startMs <= cur.endMs + 2_000L) {
                SpanMs(cur.startMs, maxOf(cur.endMs, s.endMs))
            } else {
                out.add(cur)
                s
            }
        }
        out.add(cur)
        return out
    }
}
