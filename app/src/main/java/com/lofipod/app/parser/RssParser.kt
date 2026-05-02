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
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

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
            artworkUrl = channelArt,
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
            episodeArtworkUrl = epArt
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

    // Common feed pubDate format: RFC 822 / RFC 1123
    private val pubDateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )

    private fun parsePubDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (fmt in pubDateFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(s)?.time
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
