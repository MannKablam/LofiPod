package com.lofipod.app.parser.transcript

import org.jsoup.nodes.Document

/**
 * Per-host strategy for pulling transcript paragraphs out of a podcast page's
 * HTML. New host? Add an extractor. The extractor list is consulted in
 * registration order; the first whose [matches] returns true gets to extract.
 *
 * NOTE: jsoup parses already-fetched HTML strings — it does NOT introduce a
 * browser, WebView, or network. The app's no-WebView invariant is preserved.
 */
interface TranscriptExtractor {
    fun matches(host: String): Boolean
    fun extract(doc: Document): List<String>
}

/**
 * Shared paragraph cleanup: strip leading/trailing whitespace, collapse
 * internal whitespace runs, and drop empty results.
 */
internal fun normalizeParagraphs(paragraphs: List<String>): List<String> =
    paragraphs
        .asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
        .toList()
