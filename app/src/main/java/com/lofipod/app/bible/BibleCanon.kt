package com.lofipod.app.bible

/**
 * Canonical Bible reference data for the 66-book Protestant canon.
 *
 * Provides:
 *   - The book list, ordered Genesis -> Revelation, with chapter and
 *     per-chapter verse counts (KJV versification — the same chapter+verse
 *     numbering the user's preferred translations all use; LSB / NET / ESV
 *     match KJV at the structural level).
 *   - Logos-style canonical groupings (Pentateuch, Historical, Wisdom,
 *     Major Prophets, Minor Prophets, Gospels, Acts, Pauline Epistles,
 *     General Epistles, Revelation) with stable color-token names that the
 *     UI can map to theme colors.
 *   - Common abbreviations for each book so the [ScriptureTagger]'s regex
 *     can detect references in RSS titles + descriptions.
 *
 * Reformed / baptistic context: 66-book canon, no Apocrypha. The Hebrew
 * Tanakh's Torah / Nevi'im / Ketuvim divisions are not used here in favor
 * of the traditional Protestant Christian groupings the app's audience is
 * familiar with from Logos and similar tools.
 *
 * The verse counts come from the standard KJV versification (the same one
 * SBL handbook + most reference tools use). They drive the verse grid in
 * the canon-browse screen so empty (no-sermon) cells render correctly.
 */
object BibleCanon {

    /**
     * Logos-style canonical groupings. The [colorToken] is a semantic name
     * the theme layer maps to actual `Color` values; lets us swap palettes
     * across the existing visual directions (Lowlight, Cassette, Reel,
     * etc.) without rewriting the canon data.
     */
    enum class Group(val displayName: String, val colorToken: String) {
        PENTATEUCH("Pentateuch", "canon_pentateuch"),
        HISTORICAL("Historical", "canon_historical"),
        WISDOM("Wisdom", "canon_wisdom"),
        MAJOR_PROPHETS("Major Prophets", "canon_major_prophets"),
        MINOR_PROPHETS("Minor Prophets", "canon_minor_prophets"),
        GOSPELS("Gospels", "canon_gospels"),
        ACTS("Acts", "canon_acts"),
        PAULINE("Pauline Epistles", "canon_pauline"),
        GENERAL_EPISTLES("General Epistles", "canon_general_epistles"),
        REVELATION("Revelation", "canon_revelation"),
    }

    /**
     * One book in the canon.
     *
     * @param canonicalName The display name shown in the UI ("Genesis",
     *   "1 Corinthians"). Also the storage key — `episode_scripture.book`
     *   matches this exactly.
     * @param order 1..66, Genesis = 1, Revelation = 66.
     * @param group Logos-style canonical group (drives color in the grid).
     * @param verseCounts Length = chapter count. `verseCounts[i]` = number
     *   of verses in chapter `i+1` (1-indexed in display, 0-indexed here).
     *   E.g., Genesis chapter 1 has 31 verses, so `verseCounts[0] == 31`.
     * @param aliases Other forms used in titles/descriptions —
     *   abbreviations + alternate spellings. Detection is case-insensitive
     *   so "GEN" matches "Gen". Numerals are normalized: "1Jn" matches
     *   "1 Jn" matches "I Jn".
     */
    data class Book(
        val canonicalName: String,
        val order: Int,
        val group: Group,
        val verseCounts: IntArray,
        val aliases: List<String>,
    ) {
        val chapterCount: Int get() = verseCounts.size
        fun versesIn(chapter: Int): Int =
            if (chapter in 1..verseCounts.size) verseCounts[chapter - 1] else 0
    }

    /**
     * The 66-book canon, in canonical order. KJV versification.
     *
     * Verse counts compiled from the standard Protestant chapter+verse
     * tabulation. A handful of NT books have minor versification differences
     * across translations (e.g., 3 John has 14 verses in some, 15 in
     * others — we use the more common 14). Off-by-one in a verse-grid
     * cell is acceptable; the sermon mapping is what drives utility.
     */
    val BOOKS: List<Book> = listOf(
        // --- Pentateuch ---
        Book("Genesis", 1, Group.PENTATEUCH, intArrayOf(
            31,25,24,26,32,22,24,22,29,32,32,20,18,24,21,16,27,33,38,18,
            34,24,20,67,34,35,46,22,35,43,55,32,20,31,29,43,36,30,23,23,
            57,38,34,34,28,34,31,22,33,26
        ), listOf("Gen","Ge","Gn")),
        Book("Exodus", 2, Group.PENTATEUCH, intArrayOf(
            22,25,22,31,23,30,25,32,35,29,10,51,22,31,27,36,16,27,25,26,
            36,31,33,18,40,37,21,43,46,38,18,35,23,35,35,38,29,31,43,38
        ), listOf("Exod","Ex","Exo")),
        Book("Leviticus", 3, Group.PENTATEUCH, intArrayOf(
            17,16,17,35,19,30,38,36,24,20,47,8,59,57,33,34,16,30,37,27,
            24,33,44,23,55,46,34
        ), listOf("Lev","Le","Lv")),
        Book("Numbers", 4, Group.PENTATEUCH, intArrayOf(
            54,34,51,49,31,27,89,26,23,36,35,16,33,45,41,50,13,32,22,29,
            35,41,30,25,18,65,23,31,40,16,54,42,56,29,34,13
        ), listOf("Num","Nu","Nm","Nb")),
        Book("Deuteronomy", 5, Group.PENTATEUCH, intArrayOf(
            46,37,29,49,33,25,26,20,29,22,32,32,18,29,23,22,20,22,21,20,
            23,30,25,22,19,19,26,68,29,20,30,52,29,12
        ), listOf("Deut","Dt","De")),
        // --- Historical ---
        Book("Joshua", 6, Group.HISTORICAL, intArrayOf(
            18,24,17,24,15,27,26,35,27,43,23,24,33,15,63,10,18,28,51,9,
            45,34,16,33
        ), listOf("Josh","Jos","Jsh")),
        Book("Judges", 7, Group.HISTORICAL, intArrayOf(
            36,23,31,24,31,40,25,35,57,18,40,15,25,20,20,31,13,31,30,48,25
        ), listOf("Judg","Jdg","Jg","Jdgs")),
        Book("Ruth", 8, Group.HISTORICAL, intArrayOf(22,23,18,22), listOf("Ru","Rth")),
        Book("1 Samuel", 9, Group.HISTORICAL, intArrayOf(
            28,36,21,22,12,21,17,22,27,27,15,25,23,52,35,23,58,30,24,42,
            15,23,29,22,44,25,12,25,11,31,13
        ), listOf("1Sam","1Sa","1Sm","I Samuel","I Sam","First Samuel")),
        Book("2 Samuel", 10, Group.HISTORICAL, intArrayOf(
            27,32,39,12,25,23,29,18,13,19,27,31,39,33,37,23,29,33,43,26,
            22,51,39,25
        ), listOf("2Sam","2Sa","2Sm","II Samuel","II Sam","Second Samuel")),
        Book("1 Kings", 11, Group.HISTORICAL, intArrayOf(
            53,46,28,34,18,38,51,66,28,29,43,33,34,31,34,34,24,46,21,43,29,53
        ), listOf("1Kgs","1Ki","1K","I Kings","I Kgs","First Kings")),
        Book("2 Kings", 12, Group.HISTORICAL, intArrayOf(
            18,25,27,44,27,33,20,29,37,36,21,21,25,29,38,20,41,37,37,21,26,20,37,20,30
        ), listOf("2Kgs","2Ki","2K","II Kings","II Kgs","Second Kings")),
        Book("1 Chronicles", 13, Group.HISTORICAL, intArrayOf(
            54,55,24,43,26,81,40,40,44,14,47,40,14,17,29,43,27,17,19,8,
            30,19,32,31,31,32,34,21,30
        ), listOf("1Chr","1Ch","I Chronicles","I Chr","First Chronicles")),
        Book("2 Chronicles", 14, Group.HISTORICAL, intArrayOf(
            17,18,17,22,14,42,22,18,31,19,23,16,22,15,19,14,19,34,11,37,
            20,12,21,27,28,23,9,27,36,27,21,33,25,33,27,23
        ), listOf("2Chr","2Ch","II Chronicles","II Chr","Second Chronicles")),
        Book("Ezra", 15, Group.HISTORICAL, intArrayOf(11,70,13,24,17,22,28,36,15,44),
            listOf("Ezr","Ez")),
        Book("Nehemiah", 16, Group.HISTORICAL, intArrayOf(
            11,20,32,23,19,19,73,18,38,39,36,47,31
        ), listOf("Neh","Ne")),
        Book("Esther", 17, Group.HISTORICAL, intArrayOf(22,23,15,17,14,14,10,17,32,3),
            listOf("Esth","Est","Es")),
        // --- Wisdom / Poetic ---
        Book("Job", 18, Group.WISDOM, intArrayOf(
            22,13,26,21,27,30,21,22,35,22,20,25,28,22,35,22,16,21,29,29,
            34,30,17,25,6,14,23,28,25,31,40,22,33,37,16,33,24,41,30,24,34,17
        ), listOf("Jb")),
        Book("Psalms", 19, Group.WISDOM, intArrayOf(
            6,12,8,8,12,10,17,9,20,18,7,8,6,7,5,11,15,50,14,9,
            13,31,6,10,22,12,14,9,11,12,24,11,22,22,28,12,40,22,13,17,
            13,11,5,26,17,11,9,14,20,23,19,9,6,7,23,13,11,11,17,12,
            8,12,11,10,13,20,7,35,36,5,24,20,28,23,10,12,20,72,13,19,
            16,8,18,12,13,17,7,18,52,17,16,15,5,23,11,13,12,9,9,5,
            8,28,22,35,45,48,43,13,31,7,10,10,9,8,18,19,2,29,176,7,
            8,9,4,8,5,6,5,6,8,8,3,18,3,3,21,26,9,8,24,13,
            10,7,12,15,21,10,20,14,9,6
        ), listOf("Ps","Psa","Psm","Pss")),
        Book("Proverbs", 20, Group.WISDOM, intArrayOf(
            33,22,35,27,23,35,27,36,18,32,31,28,25,35,33,33,28,24,29,30,31,29,35,34,28,28,27,28,27,33,31
        ), listOf("Prov","Prv","Pr")),
        Book("Ecclesiastes", 21, Group.WISDOM, intArrayOf(18,26,22,16,20,12,29,17,18,20,10,14),
            listOf("Eccl","Ecc","Qoh","Qoheleth")),
        Book("Song of Solomon", 22, Group.WISDOM, intArrayOf(17,17,11,16,16,13,13,14),
            listOf("Song","SOS","Cant","Canticles","Song of Songs")),
        // --- Major Prophets ---
        Book("Isaiah", 23, Group.MAJOR_PROPHETS, intArrayOf(
            31,22,26,6,30,13,25,22,21,34,16,6,22,32,9,14,14,7,25,6,
            17,25,18,23,12,21,13,29,24,33,9,20,24,17,10,22,38,22,8,31,
            29,25,28,28,25,13,15,22,26,11,23,15,12,17,13,12,21,14,21,22,
            11,12,19,12,25,24
        ), listOf("Isa","Is")),
        Book("Jeremiah", 24, Group.MAJOR_PROPHETS, intArrayOf(
            19,37,25,31,31,30,34,22,26,25,23,17,27,22,21,21,27,23,15,18,
            14,30,40,10,38,24,22,17,32,24,40,44,26,22,19,32,21,28,18,16,
            18,22,13,30,5,28,7,47,39,46,64,34
        ), listOf("Jer","Je","Jr")),
        Book("Lamentations", 25, Group.MAJOR_PROPHETS, intArrayOf(22,22,66,22,22),
            listOf("Lam","La")),
        Book("Ezekiel", 26, Group.MAJOR_PROPHETS, intArrayOf(
            28,10,27,17,17,14,27,18,11,22,25,28,23,23,8,63,24,32,14,49,
            32,31,49,27,17,21,36,26,21,26,18,32,33,31,15,38,28,23,29,49,
            26,20,27,31,25,24,23,35
        ), listOf("Ezek","Ezk","Eze")),
        Book("Daniel", 27, Group.MAJOR_PROPHETS, intArrayOf(21,49,30,37,31,28,28,27,27,21,45,13),
            listOf("Dan","Da","Dn")),
        // --- Minor Prophets ---
        Book("Hosea", 28, Group.MINOR_PROPHETS, intArrayOf(11,23,5,19,15,11,16,14,17,15,12,14,16,9),
            listOf("Hos","Ho")),
        Book("Joel", 29, Group.MINOR_PROPHETS, intArrayOf(20,32,21), listOf("Jl")),
        Book("Amos", 30, Group.MINOR_PROPHETS, intArrayOf(15,16,15,13,27,14,17,14,15),
            listOf("Am")),
        Book("Obadiah", 31, Group.MINOR_PROPHETS, intArrayOf(21), listOf("Obad","Ob")),
        Book("Jonah", 32, Group.MINOR_PROPHETS, intArrayOf(17,10,10,11), listOf("Jon","Jnh")),
        Book("Micah", 33, Group.MINOR_PROPHETS, intArrayOf(16,13,12,13,15,16,20),
            listOf("Mic","Mi")),
        Book("Nahum", 34, Group.MINOR_PROPHETS, intArrayOf(15,13,19), listOf("Nah","Na")),
        Book("Habakkuk", 35, Group.MINOR_PROPHETS, intArrayOf(17,20,19), listOf("Hab","Hb")),
        Book("Zephaniah", 36, Group.MINOR_PROPHETS, intArrayOf(18,15,20), listOf("Zeph","Zep","Zp")),
        Book("Haggai", 37, Group.MINOR_PROPHETS, intArrayOf(15,23), listOf("Hag","Hg")),
        Book("Zechariah", 38, Group.MINOR_PROPHETS, intArrayOf(
            21,13,10,14,11,15,14,23,17,12,17,14,9,21
        ), listOf("Zech","Zec","Zc")),
        Book("Malachi", 39, Group.MINOR_PROPHETS, intArrayOf(14,17,18,6), listOf("Mal","Ml")),
        // --- Gospels ---
        Book("Matthew", 40, Group.GOSPELS, intArrayOf(
            25,23,17,25,48,34,29,34,38,42,30,50,58,36,39,28,27,35,30,34,46,46,39,51,46,75,66,20
        ), listOf("Matt","Mt")),
        Book("Mark", 41, Group.GOSPELS, intArrayOf(
            45,28,35,41,43,56,37,38,50,52,33,44,37,72,47,20
        ), listOf("Mk","Mar","Mrk")),
        Book("Luke", 42, Group.GOSPELS, intArrayOf(
            80,52,38,44,39,49,50,56,62,42,54,59,35,35,32,31,37,43,48,47,38,71,56,53
        ), listOf("Lk","Luk")),
        Book("John", 43, Group.GOSPELS, intArrayOf(
            51,25,36,54,47,71,53,59,41,42,57,50,38,31,27,33,26,40,42,31,25
        ), listOf("Jn","Jo","Joh")),
        // --- Acts ---
        Book("Acts", 44, Group.ACTS, intArrayOf(
            26,47,26,37,42,15,60,40,43,48,30,25,52,28,41,40,34,28,41,38,40,30,35,27,27,32,44,31
        ), listOf("Ac")),
        // --- Pauline Epistles ---
        Book("Romans", 45, Group.PAULINE, intArrayOf(
            32,29,31,25,21,23,25,39,33,21,36,21,14,23,33,27
        ), listOf("Rom","Ro","Rm")),
        Book("1 Corinthians", 46, Group.PAULINE, intArrayOf(
            31,16,23,21,13,20,40,13,27,33,34,31,13,40,58,24
        ), listOf("1Cor","1Co","I Corinthians","I Cor","First Corinthians")),
        Book("2 Corinthians", 47, Group.PAULINE, intArrayOf(
            24,17,18,18,21,18,16,24,15,18,33,21,14
        ), listOf("2Cor","2Co","II Corinthians","II Cor","Second Corinthians")),
        Book("Galatians", 48, Group.PAULINE, intArrayOf(24,21,29,31,26,18), listOf("Gal","Ga")),
        Book("Ephesians", 49, Group.PAULINE, intArrayOf(23,22,21,32,33,24), listOf("Eph","Ephes")),
        Book("Philippians", 50, Group.PAULINE, intArrayOf(30,30,21,23), listOf("Phil","Php","Pp")),
        Book("Colossians", 51, Group.PAULINE, intArrayOf(29,23,25,18), listOf("Col","Co")),
        Book("1 Thessalonians", 52, Group.PAULINE, intArrayOf(10,20,13,18,28),
            listOf("1Thess","1Thes","1Th","I Thessalonians","First Thessalonians")),
        Book("2 Thessalonians", 53, Group.PAULINE, intArrayOf(12,17,18),
            listOf("2Thess","2Thes","2Th","II Thessalonians","Second Thessalonians")),
        Book("1 Timothy", 54, Group.PAULINE, intArrayOf(20,15,16,16,25,21),
            listOf("1Tim","1Ti","I Timothy","First Timothy")),
        Book("2 Timothy", 55, Group.PAULINE, intArrayOf(18,26,17,22),
            listOf("2Tim","2Ti","II Timothy","Second Timothy")),
        Book("Titus", 56, Group.PAULINE, intArrayOf(16,15,15), listOf("Tit","Ti")),
        Book("Philemon", 57, Group.PAULINE, intArrayOf(25), listOf("Philem","Phlm","Phm")),
        // --- General Epistles ---
        Book("Hebrews", 58, Group.GENERAL_EPISTLES, intArrayOf(
            14,18,19,16,14,20,28,13,28,39,40,29,25
        ), listOf("Heb","He")),
        Book("James", 59, Group.GENERAL_EPISTLES, intArrayOf(27,26,18,17,20),
            listOf("Jas","Jm")),
        Book("1 Peter", 60, Group.GENERAL_EPISTLES, intArrayOf(25,25,22,19,14),
            listOf("1Pet","1Pe","1Pt","1P","I Peter","First Peter")),
        Book("2 Peter", 61, Group.GENERAL_EPISTLES, intArrayOf(21,22,18),
            listOf("2Pet","2Pe","2Pt","2P","II Peter","Second Peter")),
        Book("1 John", 62, Group.GENERAL_EPISTLES, intArrayOf(10,29,24,21,21),
            listOf("1Jn","1Jo","1J","I John","First John")),
        Book("2 John", 63, Group.GENERAL_EPISTLES, intArrayOf(13),
            listOf("2Jn","2Jo","2J","II John","Second John")),
        Book("3 John", 64, Group.GENERAL_EPISTLES, intArrayOf(14),
            listOf("3Jn","3Jo","3J","III John","Third John")),
        Book("Jude", 65, Group.GENERAL_EPISTLES, intArrayOf(25), listOf("Jud","Jd")),
        // --- Apocalyptic ---
        Book("Revelation", 66, Group.REVELATION, intArrayOf(
            20,29,22,11,14,17,17,13,21,11,19,17,18,20,8,21,18,24,21,15,27,21
        ), listOf("Rev","Re","Apoc","Apocalypse","Revelations")),
    )

    /** Index by canonical name for O(1) lookup. */
    val BY_NAME: Map<String, Book> = BOOKS.associateBy { it.canonicalName }

    /** Index by order (1..66). */
    val BY_ORDER: Map<Int, Book> = BOOKS.associateBy { it.order }
}
