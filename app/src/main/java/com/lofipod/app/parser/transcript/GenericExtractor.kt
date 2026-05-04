package com.lofipod.app.parser.transcript

import org.jsoup.nodes.Document

/**
 * Last-resort extractor — applies when no host-specific match is found, or when
 * the host-specific one returns empty. Tries broad container selectors and
 * filters by paragraph count to avoid grabbing nav/footer junk.
 */
object GenericExtractor : TranscriptExtractor {
    override fun matches(host: String): Boolean = true

    override fun extract(doc: Document): List<String> {
        // Try article → main → body, in that order. Body is the dragnet — only
        // accept it if we found a substantial paragraph cluster, otherwise the
        // user gets nav links and ad chrome instead of transcript.
        val candidates = listOf("article", "main", ".content", ".post", "body")
        for (selector in candidates) {
            val container = doc.selectFirst(selector) ?: continue
            // Drop common non-content containers before harvesting paragraphs.
            container.select("nav, header, footer, aside, .nav, .menu, .sidebar, script, style")
                .forEach { it.remove() }
            val ps = container.select("p, h2, h3, h4")
            if (ps.size < 5) continue
            return normalizeParagraphs(ps.map { it.text() })
        }
        return emptyList()
    }
}
