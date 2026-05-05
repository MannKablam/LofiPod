package com.lofipod.app.parser

/**
 * One displayable item in the catalog. Either a single feed (most podcasts) or
 * a named group of feeds (e.g. Calvary Chapel Modesto's four sub-feeds, John
 * Piper's three Desiring God shows). Groups are rendered as expandable "card
 * stacks" on the catalog screen; the file-override path in [SourcesFileParser]
 * only ever produces flat [SourceEntry] items.
 */
sealed interface CatalogItem {
    /** Stable identity for list keys & expansion state. */
    val id: String
    /** Display label, or null for a flat entry that wants to fall back to the
     *  feed's own <title> tag. */
    val displayName: String?
}

data class SourceEntry(
    val feedUrl: String,
    override val displayName: String?   // null => fall back to feed's own <title>
) : CatalogItem {
    override val id: String get() = feedUrl
}

data class SourceGroup(
    val groupId: String,
    val groupName: String,
    val children: List<SourceEntry>
) : CatalogItem {
    override val id: String get() = groupId
    override val displayName: String get() = groupName
}

/**
 * Parses the user-supplied sources file (.md or .txt).
 *
 * Format per non-comment line:
 *   <feed URL> [ | display name ]
 *
 * Comments start with '#'. Blank lines are ignored. The file-override path
 * always produces flat [SourceEntry] items — group structure is in-code only.
 */
object SourcesFileParser {

    fun parse(text: String): List<SourceEntry> {
        val out = mutableListOf<SourceEntry>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            // Allow either "url | name" or just "url"
            val parts = line.split("|", limit = 2).map { it.trim() }
            val url = parts[0]
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach

            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            out += SourceEntry(feedUrl = url, displayName = name)
        }
        return out
    }
}
