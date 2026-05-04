package com.lofipod.app.parser.transcript

import org.jsoup.nodes.Document

object BethanyExtractor : TranscriptExtractor {
    override fun matches(host: String): Boolean =
        host.endsWith("bethanyto.org") || host.endsWith("bethanybiblechurch.org")

    override fun extract(doc: Document): List<String> {
        val candidates = listOf(
            ".sermon-content",
            ".message-body",
            ".entry-content",
            "main",
            "article"
        )
        for (selector in candidates) {
            val container = doc.selectFirst(selector) ?: continue
            val ps = container.select("p, h2, h3, h4")
            if (ps.size < 3) continue
            return normalizeParagraphs(ps.map { it.text() })
        }
        return emptyList()
    }
}
