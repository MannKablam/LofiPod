package com.lofipod.app.parser

/**
 * Parses the user-supplied sources file (.md or .txt).
 *
 * Format per non-comment line:
 *   <feed URL> [ | display name ]
 *
 * Comments start with '#'. Blank lines are ignored.
 */
data class SourceEntry(
    val feedUrl: String,
    val displayName: String?   // null => fall back to feed's own <title>
)

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
