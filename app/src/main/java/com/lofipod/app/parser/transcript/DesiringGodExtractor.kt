package com.lofipod.app.parser.transcript

import org.jsoup.nodes.Document

object DesiringGodExtractor : TranscriptExtractor {
    override fun matches(host: String): Boolean =
        host.endsWith("desiringgod.org")

    override fun extract(doc: Document): List<String> {
        val candidates = listOf(
            ".message-transcript",
            ".body-text",
            ".message__body",
            "article .article__body",
            "article",
            "main"
        )
        for (selector in candidates) {
            val container = doc.selectFirst(selector) ?: continue
            val ps = container.select("p, h2, h3, h4")
            if (ps.size < 3) continue
            val paragraphs = ps.map { it.text() }
            return normalizeParagraphs(paragraphs)
        }
        return emptyList()
    }
}
