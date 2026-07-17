package com.lofipod.app.bible

/**
 * Spoken-style scripture reference extraction for transcript prose —
 * catches the preacher's "chapter nineteen, verse two" phrasing that the
 * written-citation regex in [ScriptureTagger] can't see.
 *
 * Only meaningful when the episode's BOOK is already known (kabod
 * metadata or the scripture tagger): "chapter three verse five" is
 * ambiguous without one, and running this un-scoped would tag every
 * "chapter" mention in any audiobook-ish feed. Callers must not invoke
 * it bookless.
 *
 * Number words cover one..ninety-nine plus digits. A rolling
 * `lastChapter` carries across paragraphs (seeded by [defaultChapter],
 * typically the kabod scriptureStartCh) so bare "verse seven" mentions
 * attach to the chapter most recently named.
 */
object SpokenScriptureExtractor {

    /** One extracted reference, anchored to the paragraph it appeared in. */
    data class ParagraphRef(val paragraphIndex: Int, val chapter: Int, val verse: Int?)

    fun extract(paragraphs: List<String>, defaultChapter: Int?): List<ParagraphRef> {
        val out = mutableListOf<ParagraphRef>()
        var lastChapter: Int? = defaultChapter
        for ((i, p) in paragraphs.withIndex()) {
            val chapterMatches = CHAPTER_VERSE.findAll(p).toList()
            val chapterRanges = chapterMatches.map { it.range }
            for (m in chapterMatches) {
                val ch = wordToNumber(m.groupValues[1]) ?: continue
                if (ch !in 1..150) continue
                val v = m.groupValues[2].takeIf { it.isNotBlank() }
                    ?.let { wordToNumber(it) }
                    ?.takeIf { it in 1..176 }
                lastChapter = ch
                out.add(ParagraphRef(i, ch, v))
            }
            // Bare "verse N" mentions that aren't part of a chapter+verse
            // match above attach to the most recently named chapter.
            val chapterForBare = lastChapter ?: continue
            for (m in BARE_VERSE.findAll(p)) {
                if (chapterRanges.any { it.first <= m.range.first && m.range.last <= it.last }) {
                    continue
                }
                val v = wordToNumber(m.groupValues[1])?.takeIf { it in 1..176 } ?: continue
                out.add(ParagraphRef(i, chapterForBare, v))
            }
        }
        // Dedupe consecutive identical chapter:verse — a preacher repeats
        // the reference many times while expounding it; one marker carries
        // the meaning.
        return out.filterIndexed { idx, r ->
            idx == 0 || r.chapter != out[idx - 1].chapter || r.verse != out[idx - 1].verse
        }
    }

    // one..nine | ten..nineteen | twenty..ninety[-| ]one..nine | digits
    private const val NUM =
        "(?:\\d{1,3}" +
            "|(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)" +
            "(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?" +
            "|(?:ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)" +
            "|(?:one|two|three|four|five|six|seven|eight|nine))"

    private val CHAPTER_VERSE = Regex(
        "\\bchapter\\s+($NUM)" +
            "(?:[\\s,]{1,3}(?:and\\s+)?verses?\\s+($NUM))?",
        RegexOption.IGNORE_CASE,
    )

    private val BARE_VERSE = Regex("\\bverses?\\s+($NUM)\\b", RegexOption.IGNORE_CASE)

    private val UNITS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    internal fun wordToNumber(raw: String): Int? {
        val s = raw.trim().lowercase()
        s.toIntOrNull()?.let { return it }
        UNITS[s]?.let { return it }
        TEENS[s]?.let { return it }
        TENS[s]?.let { return it }
        val parts = s.split(' ', '-').filter { it.isNotBlank() }
        if (parts.size == 2) {
            val tens = TENS[parts[0]] ?: return null
            val unit = UNITS[parts[1]] ?: return null
            return tens + unit
        }
        return null
    }
}
