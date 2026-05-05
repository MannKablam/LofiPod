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
    override val displayName: String?,   // null => fall back to feed's own <title>
    /**
     * Optional override for this feed's catalog artwork. When non-null, the
     * catalog renders this URL instead of whatever the parsed feed exposes
     * via `<itunes:image>`. Useful when the feed-attached art is broken
     * (e.g. CCM's `podcast-cc.jpg` is uploaded as 0 bytes), bland, or
     * resolves slowly enough that a placeholder is what the user sees.
     */
    val customArtworkUrl: String? = null,
) : CatalogItem {
    override val id: String get() = feedUrl
}

data class SourceGroup(
    val groupId: String,
    val groupName: String,
    val children: List<SourceEntry>,
    /**
     * Optional banner artwork for the cluster's collapsed card. When null,
     * [com.lofipod.app.ui.screens.CatalogScreen] falls back to the first
     * loaded child's artwork — which is fine for most groups but can be
     * misleading when one feed's brand dominates a multi-show cluster.
     */
    val groupArtworkUrl: String? = null,
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
