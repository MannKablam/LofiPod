package com.lofipod.app.bible

/**
 * Detects Bible passage references in free-form text (RSS title +
 * description). Builds a regex over [BibleCanon]'s canonical names plus
 * each book's aliases, and walks the text picking the first/highest-
 * confidence match.
 *
 * Anchored requirements: a match needs the book name followed by
 * whitespace + a chapter digit. "Walking in 1 John" alone won't match;
 * "1 John 4:1" or "1 John 4" will. This is the disambiguation rule that
 * keeps "John" the gospel from being detected from "John Piper" or
 * "John MacArthur" prose.
 *
 * Confidence (0..100):
 *   - Title match with chapter + verse range: 95
 *   - Title match with chapter only: 80
 *   - Description match with chapter + verse range: 70
 *   - Description match with chapter only: 55
 *   - No match: returns null
 *
 * The tagger is deterministic and client-side. Re-running it on the same
 * episode is idempotent; storage layer (`episode_scripture` rows) can be
 * dropped + re-tagged when rules evolve.
 *
 * Single-instance object — the regex is precompiled once and reused.
 */
object ScriptureTagger {

    /** Where the match was found, in priority order. */
    enum class Source { TITLE, DESCRIPTION }

    /** Detected reference with confidence. Mirrors [BibleCanon.Book] +
     *  the kabod scriptureRef structure so the persistence layer is shared. */
    data class Reference(
        val book: String,           // canonical name (matches BibleCanon.Book.canonicalName)
        val startCh: Int?,          // null if just the book name was matched
        val startV: Int?,
        val endCh: Int?,
        val endV: Int?,
        val source: Source,
        val confidence: Int,        // 0..100
    )

    /**
     * Run detection over [title] (preferred) and the FIRST 50 WORDS of
     * [description] (fallback). Returns the highest-confidence match
     * or null.
     *
     * Why first 50 words: a sermon's description often opens with a
     * single explicit passage citation ("In this sermon, Pastor expounds
     * Romans 8:28-30...") and then drifts into broader themes that
     * mention many other verses incidentally. Scanning the whole
     * description picks up incidental mentions and yields a wrong
     * primary tag. The first ~50 words is where the discipline of
     * stating the passage tends to live.
     */
    fun detect(title: String?, description: String?): Reference? {
        // Title first. A successful chapter+verse hit there short-circuits.
        title?.let { findFirst(it, Source.TITLE) }?.let { return it }
        val descPrefix = description?.let { firstNWords(it, 50) }
        descPrefix?.let { findFirst(it, Source.DESCRIPTION) }?.let { return it }
        return null
    }

    /** Take the first [n] whitespace-separated tokens of [s], rejoined
     *  with single spaces. Drops HTML-ish artifacts (bare tags) by
     *  stripping `<...>` runs first. */
    private fun firstNWords(s: String, n: Int): String {
        val stripped = s.replace(Regex("<[^>]+>"), " ")
        val tokens = stripped.trim().split(Regex("\\s+"))
        return tokens.take(n).joinToString(" ")
    }

    /**
     * All explicit references in [text], in order of appearance. Used by
     * the structured scrubber's transcript-marker extraction — same
     * anchoring rules and confidence semantics as [detect] (source is
     * DESCRIPTION-tier since transcript prose isn't a title).
     */
    fun findAllRefs(text: String): List<Reference> =
        REGEX.findAll(text).mapNotNull { m -> toReference(m, Source.DESCRIPTION) }.toList()

    private fun findFirst(text: String, source: Source): Reference? {
        val match = REGEX.find(text) ?: return null
        return toReference(match, source)
    }

    private fun toReference(match: MatchResult, source: Source): Reference? {
        // The combined regex has named groups for the book token and the
        // numeric tail. We map the matched book token back to a canonical
        // name via [aliasMap] (case-insensitive, numeral-normalized).
        val rawBook = match.groups[GROUP_BOOK]?.value ?: return null
        val canonical = aliasMap[normalizeAlias(rawBook)] ?: return null
        val startCh = match.groups[GROUP_START_CH]?.value?.toIntOrNull()
        val startV = match.groups[GROUP_START_V]?.value?.toIntOrNull()
        val endCh = match.groups[GROUP_END_CH]?.value?.toIntOrNull()
            ?: if (startV != null) startCh else null
        val endV = match.groups[GROUP_END_V]?.value?.toIntOrNull()
        // Confidence: hierarchy is title-vs-description, then how much of
        // the citation is captured (chapter only vs chapter+verse).
        val baseTitle = startCh != null && startV != null
        val confidence = when (source) {
            Source.TITLE -> if (baseTitle) 95 else 80
            Source.DESCRIPTION -> if (baseTitle) 70 else 55
        }
        return Reference(
            book = canonical,
            startCh = startCh,
            startV = startV,
            endCh = endCh,
            endV = endV,
            source = source,
            confidence = confidence,
        )
    }

    // --- Regex construction ----------------------------------------------

    private const val GROUP_BOOK = "book"
    private const val GROUP_START_CH = "startCh"
    private const val GROUP_START_V = "startV"
    private const val GROUP_END_CH = "endCh"
    private const val GROUP_END_V = "endV"

    // Order matters: regex constants must be declared BEFORE [aliasMap]
    // because [aliasMap]'s initializer calls [normalizeAlias], which
    // reads these. Kotlin `object` member initialization runs in
    // declaration order; a forward reference returns null and the
    // function NPEs inside the static initializer (= class fails to
    // load with ExceptionInInitializerError, not just a runtime NPE).
    private val romanLeadingPattern = Regex("^(iii|ii|i)(?=\\s|[a-z])")
    private val whitespaceCollapsePattern = Regex("\\s+")
    private val numericPrefixPattern = Regex("^([123])(?=[a-z])")

    /** Normalize an alias for map lookup: lowercase, collapse whitespace,
     *  Roman numeral I/II/III to 1/2/3. */
    private fun normalizeAlias(s: String): String {
        var out = s.trim().lowercase()
        // Convert leading Roman numerals to arabic. "i john" -> "1 john".
        // Order matters: longest first so "iii" beats "ii".
        out = romanLeadingPattern.replace(out) { m ->
            when (m.value) {
                "iii" -> "3"
                "ii" -> "2"
                "i" -> "1"
                else -> m.value
            }
        }
        // Collapse interior whitespace.
        out = whitespaceCollapsePattern.replace(out, " ")
        // Drop the space between leading digit and book name: "1 john" -> "1 john" stays,
        // "1john" gets a space inserted: "1john" -> "1 john".
        // Tagger itself handles both forms via aliases, so we only need to
        // canonicalize for map lookup.
        out = numericPrefixPattern.replace(out, "$1 ")
        return out
    }

    /**
     * Map every alias (canonical name + each entry in `book.aliases`) to
     * the canonical book name. Keys are normalized via [normalizeAlias]
     * so "1Jn", "1 Jn", "I Jn" all map to "1 John". Values are the
     * canonical names (as in [BibleCanon.BOOKS]).
     */
    private val aliasMap: Map<String, String> = buildMap {
        for (book in BibleCanon.BOOKS) {
            val all = listOf(book.canonicalName) + book.aliases
            for (alias in all) {
                put(normalizeAlias(alias), book.canonicalName)
            }
        }
    }

    /**
     * The mega-regex. Matches any book alias followed by REQUIRED
     * whitespace + a chapter digit, with optional verse, optional
     * range. Book aliases include all canonical names + entries from
     * `Book.aliases`. Aliases are sorted longest-first to ensure greedy
     * matches prefer "1 Corinthians" over "1 Cor".
     *
     * The regex is case-insensitive; all-caps "ROMANS 8" still matches.
     */
    private val REGEX: Regex = run {
        val aliases = buildList {
            for (book in BibleCanon.BOOKS) {
                add(book.canonicalName)
                addAll(book.aliases)
            }
        }
            .sortedByDescending { it.length }
            .map { Regex.escape(it) }
        // Allow "1John" / "1 John" / "I John" interchangeably by relaxing
        // the embedded space inside the alias. We do that by transforming
        // each escaped alias: collapse the literal space to "\\s*" so the
        // alias "1 John" matches "1John" too. Roman-numeral forms are
        // already in [aliases].
        val flexible = aliases.map { it.replace("\\ ", "\\s*") }
        val bookGroup = "(?<$GROUP_BOOK>${flexible.joinToString("|")})"
        // Chapter required. Optional verse + range.
        val tail = "\\s+(?<$GROUP_START_CH>\\d{1,3})" +
            "(?::(?<$GROUP_START_V>\\d{1,3})" +
            "(?:[\\-–](?:(?<$GROUP_END_CH>\\d{1,3}):)?(?<$GROUP_END_V>\\d{1,3}))?)?"
        Regex("\\b$bookGroup$tail\\b", RegexOption.IGNORE_CASE)
    }
}
