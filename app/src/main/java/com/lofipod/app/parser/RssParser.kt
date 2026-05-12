package com.lofipod.app.parser

import android.util.Xml
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight RSS 2.0 / iTunes-namespace parser.
 * Uses Android's built-in XmlPullParser to avoid pulling in a heavy dependency.
 *
 * Handles:
 *  - <channel> level metadata: title, description, author, image (channel/itunes)
 *  - <item> level: title, guid, pubDate, description, enclosure, itunes:duration, itunes:image
 *
 * Tolerates missing fields and unknown elements by skipping them.
 */
object RssParser {

    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    fun parse(feedUrl: String, input: InputStream): Podcast {
        // Read the whole stream so we can retry with a sanitized copy if
        // strict XML parsing fails. Most feeds parse cleanly on the first
        // pass; the sanitization pass costs one regex sweep over the
        // text. Worst-case feed is ~5 MB (Ask Pastor John) — acceptable
        // memory cost given the alternative is no episodes shown for
        // bare-ampersand feeds (CCM Sunday-service feeds reproduce this).
        val raw = input.bufferedReader().use { it.readText() }
        return try {
            parseString(feedUrl, raw)
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            // Retry once with a tolerant pass that escapes bare ampersands
            // (the most common cause: feeds that emit `Q&A` or `Tom & Jerry`
            // in titles without `&amp;`). Negative lookahead keeps already-
            // escaped entities (`&amp;`, `&#39;`, `&#x27;`) untouched, so
            // we don't double-encode. Doesn't fix unclosed tags / bad
            // quoting; those will re-throw as the upstream feed-failure log
            // entry. Surface in [com.lofipod.app.diagnostics.AppDiagnostics]
            // so the user can see which feeds needed the rescue.
            try {
                val sanitized = AMPERSAND_FIXUP.replace(raw, "&amp;")
                parseString(feedUrl, sanitized).also {
                    com.lofipod.app.diagnostics.AppDiagnostics.recordFeedRescue(
                        feedUrl, "bare ampersand"
                    )
                }
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private val AMPERSAND_FIXUP =
        Regex("&(?!(?:[a-zA-Z]+|#\\d+|#x[\\da-fA-F]+);)")

    private fun parseString(feedUrl: String, xml: String): Podcast {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.byteInputStream(), null)

        var channelTitle = ""
        var channelDesc: String? = null
        var channelAuthor: String? = null
        var channelArt: String? = null
        val episodes = mutableListOf<Episode>()

        // Walk to <channel>
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "channel") {
                parser.next()
                while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
                    if (parser.eventType != XmlPullParser.START_TAG) {
                        parser.next(); continue
                    }
                    val ns = parser.namespace
                    when (parser.name) {
                        "title" -> if (ns.isEmpty()) channelTitle = readText(parser) else skip(parser)
                        "description" -> if (ns.isEmpty()) channelDesc = readText(parser) else skip(parser)
                        "author" -> channelAuthor = readText(parser)
                        "image" -> if (ns.isEmpty()) channelArt = readChannelImage(parser)
                            else skip(parser)
                        "item" -> readItem(parser, feedUrl)?.let(episodes::add)
                        else -> {
                            // iTunes namespace
                            if (ns == NS_ITUNES) {
                                when (parser.name) {
                                    "image" -> {
                                        val href = parser.getAttributeValue(null, "href")
                                        if (!href.isNullOrBlank()) {
                                            channelArt = href
                                            skip(parser)
                                        } else {
                                            // Some feeds put the URL as text content instead of href.
                                            val text = readText(parser)
                                            if (text.isNotBlank()) channelArt = text
                                        }
                                    }
                                    "author" -> channelAuthor = readText(parser)
                                    "summary" -> if (channelDesc == null) channelDesc = readText(parser) else skip(parser)
                                    else -> skip(parser)
                                }
                            } else skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Podcast(
            feedUrl = feedUrl,
            title = channelTitle.ifBlank { feedUrl },
            author = channelAuthor,
            description = channelDesc,
            // Defensive fallback: if channel-level art is missing/unparseable for any
            // reason, use the first episode's per-episode artwork. Covers feeds with
            // unusual XML quirks where our channel parser misses the URL.
            artworkUrl = channelArt
                ?: episodes.firstOrNull { !it.episodeArtworkUrl.isNullOrBlank() }
                    ?.episodeArtworkUrl,
            episodes = episodes
        )
    }

    private fun readItem(parser: XmlPullParser, feedUrl: String): Episode? {
        var title = ""
        var guid: String? = null
        var pubDate: String? = null
        var desc: String? = null
        var audioUrl: String? = null
        var audioType: String? = null
        var audioBytes: Long? = null
        var duration: String? = null
        var epArt: String? = null

        parser.require(XmlPullParser.START_TAG, null, "item")
        parser.next()
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "item")) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                parser.next(); continue
            }
            val ns = parser.namespace
            when (parser.name) {
                "title" -> if (ns.isEmpty()) title = readText(parser) else skip(parser)
                "guid" -> guid = readText(parser)
                "pubDate" -> pubDate = readText(parser)
                "description" -> if (ns.isEmpty() && desc == null) desc = readText(parser) else skip(parser)
                "enclosure" -> {
                    audioUrl = parser.getAttributeValue(null, "url")
                    audioType = parser.getAttributeValue(null, "type")
                    audioBytes = parser.getAttributeValue(null, "length")?.toLongOrNull()
                    skip(parser)
                }
                else -> {
                    if (ns == NS_ITUNES) {
                        when (parser.name) {
                            "duration" -> duration = readText(parser)
                            "image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) {
                                    epArt = href
                                    skip(parser)
                                } else {
                                    val text = readText(parser)
                                    if (text.isNotBlank()) epArt = text
                                }
                            }
                            "summary" -> if (desc == null) desc = readText(parser) else skip(parser)
                            else -> skip(parser)
                        }
                    } else skip(parser)
                }
            }
        }
        if (audioUrl.isNullOrBlank()) return null

        return Episode(
            guid = guid ?: audioUrl!!,        // fallback: enclosure URL is unique enough
            feedUrl = feedUrl,
            title = title.ifBlank { "(untitled)" },
            description = desc,
            pubDateMillis = parsePubDate(pubDate),
            audioUrl = audioUrl!!,
            audioMimeType = audioType,
            durationSeconds = parseDuration(duration),
            episodeArtworkUrl = epArt,
            audioByteSize = audioBytes,
        )
    }

    private fun readChannelImage(parser: XmlPullParser): String? {
        var url: String? = null
        parser.require(XmlPullParser.START_TAG, null, "image")
        parser.next()
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "image")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "url") {
                url = readText(parser)
            } else if (parser.eventType == XmlPullParser.START_TAG) {
                skip(parser)
            } else {
                parser.next()
            }
        }
        return url
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result.trim()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            parser.next(); return
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    // pubDate format menagerie. Different feeds emit different shapes:
    //   - RFC 822 / RFC 1123: "Sun, 26 Apr 1998 00:00:00 GMT" (most podcasts)
    //   - ISO 8601 with offset: "1998-04-26T00:00:00+00:00"
    //   - ISO 8601 with Z: "1998-04-26T00:00:00Z" (Megaphone, Castos)
    //   - ISO 8601 with milliseconds + Z: "1998-04-26T00:00:00.000Z"
    //   - Date only: "1998-04-26" (some kabod-pack-style sources)
    // SimpleDateFormat doesn't accept 'Z' as a literal, so for the Z variants
    // we strip/replace it before parsing rather than adding more format
    // strings. Order matters: we try the most-common first to avoid
    // unnecessary exception throws.
    private val pubDateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd",
    )

    private fun parsePubDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        // Normalize ISO 'Z' (UTC) to '+00:00' so the XXX format specifier
        // parses cleanly. Keeps the format list short.
        val normalized = s.trim().let { raw ->
            if (raw.endsWith("Z") && raw.length >= 11 && raw[10] == 'T') {
                raw.dropLast(1) + "+00:00"
            } else raw
        }
        for (fmt in pubDateFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(normalized)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    // itunes:duration may be "HH:MM:SS", "MM:SS", or just seconds
    private fun parseDuration(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val parts = s.split(":")
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
