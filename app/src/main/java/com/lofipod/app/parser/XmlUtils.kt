package com.lofipod.app.parser

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pull-parser helpers shared between [RssParser] and [KabodPackParser].
 * Extracted so both parsers stay focused on their tag dispatch logic without
 * re-inventing pubDate / duration parsing or the skip + readText primitives.
 */
internal object XmlUtils {

    fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result.trim()
    }

    fun skip(parser: XmlPullParser) {
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

    private val pubDateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd"
    )

    fun parsePubDate(s: String?): Long? {
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

    /** itunes:duration may be "HH:MM:SS", "MM:SS", or just seconds. */
    fun parseDuration(s: String?): Long? {
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
