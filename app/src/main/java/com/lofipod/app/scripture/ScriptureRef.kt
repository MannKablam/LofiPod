package com.lofipod.app.scripture

/**
 * Build deep links to scripture passages that other apps on the device can
 * intercept. We use [Faithlife's `ref.ly`](https://ref.ly) — Logos / Faithlife
 * registers `ref.ly` as an app link, so users with Logos installed land
 * directly on the passage. Without Logos, the OS falls back to its standard
 * chooser.
 *
 * This is an explicit hand-off via `Intent.ACTION_VIEW` — the app does NOT
 * open the URL in an in-app WebView. That's the user choosing what to do
 * with the link, not us embedding a browser.
 */
object ScriptureRef {

    /** Canonical book → ref.ly abbreviation. ref.ly accepts a fairly wide set;
     * we use the conservative ones from Logos's documented short codes. */
    private val BOOK_ABBREV: Map<String, String> = mapOf(
        // Pentateuch
        "Genesis" to "Gen", "Exodus" to "Exod", "Leviticus" to "Lev",
        "Numbers" to "Num", "Deuteronomy" to "Deut",
        // Historical
        "Joshua" to "Josh", "Judges" to "Judg", "Ruth" to "Ruth",
        "1 Samuel" to "1Sam", "2 Samuel" to "2Sam",
        "1 Kings" to "1Kgs", "2 Kings" to "2Kgs",
        "1 Chronicles" to "1Chr", "2 Chronicles" to "2Chr",
        "Ezra" to "Ezra", "Nehemiah" to "Neh", "Esther" to "Esth",
        // Wisdom / Poetry
        "Job" to "Job", "Psalms" to "Ps", "Psalm" to "Ps",
        "Proverbs" to "Prov", "Ecclesiastes" to "Eccl",
        "Song of Solomon" to "Song", "Song of Songs" to "Song",
        // Major Prophets
        "Isaiah" to "Isa", "Jeremiah" to "Jer", "Lamentations" to "Lam",
        "Ezekiel" to "Ezek", "Daniel" to "Dan",
        // Minor Prophets
        "Hosea" to "Hos", "Joel" to "Joel", "Amos" to "Amos",
        "Obadiah" to "Obad", "Jonah" to "Jonah", "Micah" to "Mic",
        "Nahum" to "Nah", "Habakkuk" to "Hab", "Zephaniah" to "Zeph",
        "Haggai" to "Hag", "Zechariah" to "Zech", "Malachi" to "Mal",
        // Gospels / Acts
        "Matthew" to "Matt", "Mark" to "Mark",
        "Luke" to "Luke", "John" to "John", "Acts" to "Acts",
        // Pauline
        "Romans" to "Rom",
        "1 Corinthians" to "1Cor", "2 Corinthians" to "2Cor",
        "Galatians" to "Gal", "Ephesians" to "Eph",
        "Philippians" to "Phil", "Colossians" to "Col",
        "1 Thessalonians" to "1Thess", "2 Thessalonians" to "2Thess",
        "1 Timothy" to "1Tim", "2 Timothy" to "2Tim",
        "Titus" to "Titus", "Philemon" to "Phlm",
        // General
        "Hebrews" to "Heb", "James" to "Jas",
        "1 Peter" to "1Pet", "2 Peter" to "2Pet",
        "1 John" to "1John", "2 John" to "2John", "3 John" to "3John",
        "Jude" to "Jude", "Revelation" to "Rev",
    )

    /**
     * Build a `https://ref.ly/<ref>` URL for the given passage, or null if
     * the book name isn't in the table.
     *
     * Examples:
     *   buildRefLyUrl("Romans", 1, 1, 1, 1)  -> https://ref.ly/Rom1.1
     *   buildRefLyUrl("Romans", 1, 1, 1, 7)  -> https://ref.ly/Rom1.1-7
     *   buildRefLyUrl("Romans", 1, null)     -> https://ref.ly/Rom1
     *   buildRefLyUrl("Foo", 1, 1)           -> null
     */
    fun buildRefLyUrl(
        book: String?,
        startCh: Int?,
        startV: Int? = null,
        endCh: Int? = null,
        endV: Int? = null,
    ): String? {
        val abbrev = book?.let { BOOK_ABBREV[it] } ?: return null
        if (startCh == null) return null
        val sb = StringBuilder("https://ref.ly/").append(abbrev).append(startCh)
        if (startV != null) sb.append('.').append(startV)
        // Range handling
        if (endCh != null && (endCh != startCh || (endV != null && endV != startV))) {
            sb.append('-')
            if (endCh != startCh) {
                sb.append(endCh)
                if (endV != null) sb.append('.').append(endV)
            } else if (endV != null) {
                sb.append(endV)
            }
        }
        return sb.toString()
    }

    /**
     * Detect scripture references in a paragraph of plain text. Returns each
     * match's character range plus the parsed reference parts so the renderer
     * can both style the text and build a deep link on tap.
     *
     * Heuristic, not exhaustive — handles the common forms found in sermon
     * transcripts: "Romans 1:1", "Romans 1:1–7", "Romans 1:1-7", "Romans 1",
     * and a leading numeric book prefix like "1 Corinthians 13:4".
     */
    fun detectInText(text: String): List<DetectedRef> {
        val pattern = Regex(
            """\b(?<book>(?:[1-3]\s)?[A-Z][a-z]+(?:\s[A-Z][a-z]+)?)\s(?<startCh>\d{1,3})(?::(?<startV>\d{1,3})(?:[–—−\-](?<endV>\d{1,3}))?)?"""
        )
        val results = mutableListOf<DetectedRef>()
        for (m in pattern.findAll(text)) {
            val book = m.groups["book"]?.value?.trim() ?: continue
            if (BOOK_ABBREV[book] == null) continue
            val startCh = m.groups["startCh"]?.value?.toIntOrNull() ?: continue
            val startV = m.groups["startV"]?.value?.toIntOrNull()
            val endV = m.groups["endV"]?.value?.toIntOrNull()
            results.add(
                DetectedRef(
                    range = m.range,
                    book = book,
                    startCh = startCh,
                    startV = startV,
                    endCh = if (endV != null) startCh else null,
                    endV = endV,
                )
            )
        }
        return results
    }
}

data class DetectedRef(
    val range: IntRange,
    val book: String,
    val startCh: Int,
    val startV: Int?,
    val endCh: Int?,
    val endV: Int?,
)
