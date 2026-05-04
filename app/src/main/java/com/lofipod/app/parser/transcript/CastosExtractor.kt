package com.lofipod.app.parser.transcript

import org.jsoup.nodes.Document

object CastosExtractor : TranscriptExtractor {
    override fun matches(host: String): Boolean =
        host.endsWith("castos.com") || host.contains(".castos.")

    override fun extract(doc: Document): List<String> {
        val candidates = listOf(
            ".episode-content",
            ".transcript",
            ".cb-transcript",
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
