package com.lofipod.app.parser

import com.lofipod.app.parser.transcript.BethanyExtractor
import com.lofipod.app.parser.transcript.CastosExtractor
import com.lofipod.app.parser.transcript.DesiringGodExtractor
import com.lofipod.app.parser.transcript.GenericExtractor
import com.lofipod.app.parser.transcript.SermonAudioExtractor
import com.lofipod.app.parser.transcript.normalizeParagraphs
import org.jsoup.Jsoup
import java.net.URI

/**
 * Turns an already-fetched podcast/sermon page HTML into a list of clean
 * paragraph strings the renderer can display.
 *
 * jsoup parses the HTML string in-process — no browser, no network, no
 * WebView. The app's no-WebView invariant is preserved.
 *
 * Strategy: find a host-matched extractor (or the optional pack-supplied
 * CSS selector), fall back to [GenericExtractor]. If a host extractor
 * returns empty (selectors didn't match — common when sites redesign),
 * also fall back to generic.
 */
object TranscriptHtmlParser {

    private val extractors = listOf(
        DesiringGodExtractor,
        SermonAudioExtractor,
        BethanyExtractor,
        CastosExtractor,
    )

    fun parse(
        sourceUrl: String,
        html: String,
        selectorHint: String? = null,
    ): List<String> {
        val doc = Jsoup.parse(html, sourceUrl)
        val host = try {
            URI(sourceUrl).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }

        // Pack-supplied CSS selector wins when provided — packs can override
        // the per-host strategy for their specific source URLs.
        if (!selectorHint.isNullOrBlank()) {
            val container = doc.selectFirst(selectorHint)
            if (container != null) {
                val ps = container.select("p, h2, h3, h4")
                if (ps.isNotEmpty()) {
                    return normalizeParagraphs(ps.map { it.text() })
                }
            }
        }

        for (ex in extractors) {
            if (!ex.matches(host)) continue
            val result = ex.extract(doc)
            if (result.isNotEmpty()) return result
        }

        return GenericExtractor.extract(doc)
    }
}
