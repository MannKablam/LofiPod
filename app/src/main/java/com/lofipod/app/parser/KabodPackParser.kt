package com.lofipod.app.parser

import android.util.Xml
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Parses a Kabod Pack — RSS 2.0 with the `kabod:` namespace overlay.
 *
 * A Kabod Pack is a packageable pointer to a *completed* body of work (e.g.,
 * a verse-by-verse preaching series). Standard RSS tags carry audio + dates
 * so a generic podcast app could still play the audio; `kabod:` tags carry
 * sermon-specific metadata (scripture, transcript URL, part number, speaker).
 *
 * Returns a [KabodPack] containing:
 *  - The standard [Podcast] (so catalog/episode UI renders without changes)
 *  - [KabodPackMeta] — channel-level kabod tags
 *  - A `Map<guid, KabodEpisodeMeta>` — per-item kabod tags
 *
 * Implementation note: kept independent of [RssParser] rather than refactored
 * into a shared base class — the existing RSS parser is non-virtual and one
 * extra namespace doesn't justify the churn. Shared helpers live in [XmlUtils].
 */
object KabodPackParser {

    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val NS_KABOD = "https://lofipod.app/ns/kabod/1"

    fun parse(feedUrl: String, input: InputStream): KabodPack {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        var channelTitle = ""
        var channelDesc: String? = null
        var channelAuthor: String? = null
        var channelArt: String? = null
        // kabod-namespace channel-level fields
        var packId: String? = null
        var archived = false
        var speaker: String? = null
        var bookOfBible: String? = null
        var sourceSite: String? = null
        var seriesStart: String? = null
        var seriesEnd: String? = null
        var itunesCollectionId: String? = null

        val episodes = mutableListOf<Episode>()
        val episodeMeta = mutableMapOf<String, KabodEpisodeMeta>()

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
                        "title" -> if (ns.isEmpty()) channelTitle = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                        "description" -> if (ns.isEmpty()) channelDesc = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                        "author" -> channelAuthor = XmlUtils.readText(parser)
                        "image" -> if (ns.isEmpty()) channelArt = readChannelImage(parser) else XmlUtils.skip(parser)
                        "item" -> {
                            val (ep, meta) = readItem(parser, feedUrl) ?: continue
                            episodes.add(ep)
                            episodeMeta[ep.guid] = meta
                        }
                        else -> when (ns) {
                            NS_ITUNES -> when (parser.name) {
                                "image" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrBlank()) {
                                        channelArt = href
                                        XmlUtils.skip(parser)
                                    } else {
                                        val text = XmlUtils.readText(parser)
                                        if (text.isNotBlank()) channelArt = text
                                    }
                                }
                                "author" -> channelAuthor = XmlUtils.readText(parser)
                                "summary" -> if (channelDesc == null) channelDesc = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                                else -> XmlUtils.skip(parser)
                            }
                            NS_KABOD -> when (parser.name) {
                                "packId" -> packId = XmlUtils.readText(parser)
                                "archived" -> archived = XmlUtils.readText(parser).equals("true", ignoreCase = true)
                                "speaker" -> speaker = XmlUtils.readText(parser)
                                "bookOfBible" -> bookOfBible = XmlUtils.readText(parser)
                                "sourceSite" -> sourceSite = XmlUtils.readText(parser)
                                "seriesStart" -> seriesStart = XmlUtils.readText(parser)
                                "seriesEnd" -> seriesEnd = XmlUtils.readText(parser)
                                "itunesCollectionId" -> itunesCollectionId = XmlUtils.readText(parser)
                                else -> XmlUtils.skip(parser)
                            }
                            else -> XmlUtils.skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }

        require(packId != null) { "Kabod Pack is missing required <kabod:packId> tag" }

        val podcast = Podcast(
            feedUrl = feedUrl,
            title = channelTitle.ifBlank { feedUrl },
            author = channelAuthor ?: speaker,
            description = channelDesc,
            artworkUrl = channelArt
                ?: episodes.firstOrNull { !it.episodeArtworkUrl.isNullOrBlank() }?.episodeArtworkUrl,
            episodes = episodes
        )

        val packMeta = KabodPackMeta(
            packId = packId!!,
            title = podcast.title,
            speaker = speaker,
            bookOfBible = bookOfBible,
            sourceSite = sourceSite,
            archived = archived,
            seriesStart = seriesStart,
            seriesEnd = seriesEnd,
            itunesCollectionId = itunesCollectionId,
        )

        return KabodPack(
            podcast = podcast,
            packMeta = packMeta,
            episodeMeta = episodeMeta,
        )
    }

    private fun readItem(parser: XmlPullParser, feedUrl: String): Pair<Episode, KabodEpisodeMeta>? {
        var title = ""
        var guid: String? = null
        var pubDate: String? = null
        var desc: String? = null
        var audioUrl: String? = null
        var audioType: String? = null
        var duration: String? = null
        var epArt: String? = null
        // kabod fields
        var partNumber: Int? = null
        var scripture: String? = null
        var scriptureBook: String? = null
        var scriptureStartCh: Int? = null
        var scriptureStartV: Int? = null
        var scriptureEndCh: Int? = null
        var scriptureEndV: Int? = null
        var transcriptUrl: String? = null
        var transcriptSelector: String? = null
        var itemSpeaker: String? = null
        var itunesTrackId: String? = null

        parser.require(XmlPullParser.START_TAG, null, "item")
        parser.next()
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "item")) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                parser.next(); continue
            }
            val ns = parser.namespace
            when (parser.name) {
                "title" -> if (ns.isEmpty()) title = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                "guid" -> guid = XmlUtils.readText(parser)
                "pubDate" -> pubDate = XmlUtils.readText(parser)
                "description" -> if (ns.isEmpty() && desc == null) desc = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                "enclosure" -> {
                    audioUrl = parser.getAttributeValue(null, "url")
                    audioType = parser.getAttributeValue(null, "type")
                    XmlUtils.skip(parser)
                }
                else -> when (ns) {
                    NS_ITUNES -> when (parser.name) {
                        "duration" -> duration = XmlUtils.readText(parser)
                        "image" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) { epArt = href; XmlUtils.skip(parser) }
                            else {
                                val text = XmlUtils.readText(parser)
                                if (text.isNotBlank()) epArt = text
                            }
                        }
                        "summary" -> if (desc == null) desc = XmlUtils.readText(parser) else XmlUtils.skip(parser)
                        else -> XmlUtils.skip(parser)
                    }
                    NS_KABOD -> when (parser.name) {
                        "partNumber" -> partNumber = XmlUtils.readText(parser).toIntOrNull()
                        "scripture" -> scripture = XmlUtils.readText(parser)
                        "scriptureRef" -> {
                            scriptureBook = parser.getAttributeValue(null, "book")
                            scriptureStartCh = parser.getAttributeValue(null, "startCh")?.toIntOrNull()
                            scriptureStartV = parser.getAttributeValue(null, "startV")?.toIntOrNull()
                            scriptureEndCh = parser.getAttributeValue(null, "endCh")?.toIntOrNull()
                            scriptureEndV = parser.getAttributeValue(null, "endV")?.toIntOrNull()
                            XmlUtils.skip(parser)
                        }
                        "transcriptUrl" -> transcriptUrl = XmlUtils.readText(parser)
                        "transcriptSelector" -> transcriptSelector = XmlUtils.readText(parser)
                        "speaker" -> itemSpeaker = XmlUtils.readText(parser)
                        "itunesTrackId" -> itunesTrackId = XmlUtils.readText(parser)
                        else -> XmlUtils.skip(parser)
                    }
                    else -> XmlUtils.skip(parser)
                }
            }
        }
        if (audioUrl.isNullOrBlank()) return null

        val episode = Episode(
            guid = guid ?: audioUrl!!,
            feedUrl = feedUrl,
            title = title.ifBlank { "(untitled)" },
            description = desc,
            pubDateMillis = XmlUtils.parsePubDate(pubDate),
            audioUrl = audioUrl!!,
            audioMimeType = audioType,
            durationSeconds = XmlUtils.parseDuration(duration),
            episodeArtworkUrl = epArt
        )
        val meta = KabodEpisodeMeta(
            partNumber = partNumber,
            scripture = scripture,
            scriptureBook = scriptureBook,
            scriptureStartCh = scriptureStartCh,
            scriptureStartV = scriptureStartV,
            scriptureEndCh = scriptureEndCh,
            scriptureEndV = scriptureEndV,
            transcriptUrl = transcriptUrl,
            transcriptSelector = transcriptSelector,
            speaker = itemSpeaker,
            itunesTrackId = itunesTrackId,
        )
        return episode to meta
    }

    private fun readChannelImage(parser: XmlPullParser): String? {
        var url: String? = null
        parser.require(XmlPullParser.START_TAG, null, "image")
        parser.next()
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "image")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "url") {
                url = XmlUtils.readText(parser)
            } else if (parser.eventType == XmlPullParser.START_TAG) {
                XmlUtils.skip(parser)
            } else {
                parser.next()
            }
        }
        return url
    }
}

data class KabodPack(
    val podcast: com.lofipod.app.data.model.Podcast,
    val packMeta: KabodPackMeta,
    val episodeMeta: Map<String, KabodEpisodeMeta>,
)

data class KabodPackMeta(
    val packId: String,
    val title: String,
    val speaker: String?,
    val bookOfBible: String?,
    val sourceSite: String?,
    val archived: Boolean,
    val seriesStart: String?,
    val seriesEnd: String?,
    val itunesCollectionId: String?,
)

data class KabodEpisodeMeta(
    val partNumber: Int?,
    val scripture: String?,
    val scriptureBook: String?,
    val scriptureStartCh: Int?,
    val scriptureStartV: Int?,
    val scriptureEndCh: Int?,
    val scriptureEndV: Int?,
    val transcriptUrl: String?,
    val transcriptSelector: String?,
    val speaker: String?,
    val itunesTrackId: String?,
)
