package com.lofipod.app.data

import com.lofipod.app.parser.CatalogItem
import com.lofipod.app.parser.SourceEntry
import com.lofipod.app.parser.SourceGroup

/**
 * The canon. The runtime library reads from this list — there is no in-app way
 * to add or remove podcasts. To change the canon, edit this file, commit, build,
 * sideload. That deliberate friction is the feature.
 *
 * Feed URLs were resolved via Podcast Index (api.podcastindex.org/api/podcasts/byfeedid).
 * The IDs below are recorded so a host migration can be re-resolved by re-querying.
 *
 *   Bethany Bible Church                — podcastindex 6442090
 *   CCM — Topical Studies               — podcastindex 638072
 *   CCM — Thru the Bible                — podcastindex 1173954
 *   CCM — Sunday Morning Service        — podcastindex 6316408
 *   CCM — Sunday Evening Service        — podcastindex 6316409
 *   BibleThinker (Mike Winger)          — podcastindex 5128808
 *   Compelled (Paul Hastings)           — iTunes 1412479643
 *   Now That We're A Family             — podcastindex 4469282
 *   Simple Farmhouse Life               — podcastindex 691589
 *   Ask Pastor John                     — iTunes 618132843
 *   Solid Joys (audio)                  — iTunes 1315817340
 *   Light + Truth                       — iTunes 955100693
 *   Just Thinking Podcast               — podcastindex 227760
 *   Alpha and Omega Ministries          — podcastindex 990
 *   Homeschool Made Simple              — podcastindex 277416
 *   The Briefing with Albert Mohler     — podcastindex 511163
 *   The Pour Over                       — podcastindex 4148603
 */
object Sources {

    /**
     * Bundled Kabod Packs. Each entry uses the synthetic `kabod://<packId>`
     * scheme — [PodcastRepository.fetchOne] sees the scheme and routes to
     * [KabodAssetLoader] (asset file read + parse) instead of HTTP. The same
     * packId must match the bundled file at `assets/kabod/<packId>.kabod`.
     */
    val KABOD_PACKS: List<SourceEntry> = listOf(
        SourceEntry(
            feedUrl = "kabod://desiringgod-piper-romans",
            displayName = null  // pack file's own title wins
        ),
        // Verse-by-verse Old-Testament expositions, ordered Pentateuch →
        // Prophets. One pack per book: the 2026-07 curation kept only Mark
        // Chanski's Leviticus series and retired the William Still, Cities
        // Church (Parnell) and Andy Davis Leviticus packs — see
        // [com.lofipod.app.data.KabodAssetLoader.RETIRED_PACK_IDS] for the
        // DB cleanup that removes them from existing installs.
        SourceEntry(
            feedUrl = "kabod://sermonaudio-chanski-leviticus",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "kabod://sermonaudio-allen-ezekiel",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "kabod://monergism-still-hosea",
            displayName = null
        ),
    )

    /**
     * The canon, hierarchical. [SourceEntry]s render as a single card; a
     * [SourceGroup] renders as one expandable card-stack whose children show on
     * tap. Order here is the order shown on the catalog screen.
     */
    val PODCASTS: List<CatalogItem> = listOf(
        SourceEntry(
            feedUrl = "https://www.bethanyto.org/feeds/media-libraries/b9b11184-9a80-11ec-b043-0614187498c1",
            displayName = null,
            // Pinned to the church's own CDN copy — same image the feed exposes
            // via <itunes:image>, but hardcoded here so the card always renders
            // even if the feed parse skips the tag for any reason.
            customArtworkUrl = "https://d2114hmso7dut1.cloudfront.net/customers/b644a1dc-9a80-11ec-9c45-0614187498c1/sites/b65149dc-9a80-11ec-b904-0614187498c1/files/3ca82950-fb5c-11ed-b463-69be73fccae6/original/file.jpg",
        ),
        SourceGroup(
            groupId = "ccmodesto",
            groupName = "Calvary Chapel Modesto",
            // Banner for the collapsed CCM cluster. Uses the Topical Studies
            // 1400×1400 podcast cover — it's high-res, CCM-branded, and
            // available on CCM's own DigitalOcean Spaces bucket. Better than
            // letting GroupRow fall back to whichever child happens to load
            // first.
            groupArtworkUrl = "https://ccmodesto.sfo2.digitaloceanspaces.com/2026/05/04101315/2026-Topical-podcast-itunes-1400.jpg",
            children = listOf(
                // Children get short labels so the expanded stack reads cleanly —
                // every CCM feed's own <title> begins with "Calvary Chapel
                // Modesto", which would be redundant under a group header that
                // already says so.
                SourceEntry(
                    feedUrl = "https://ccmodesto.com/?feed=seriesengine&enmse_pid=5",
                    displayName = "Topical Studies"
                ),
                SourceEntry(
                    feedUrl = "https://ccmodesto.com/?feed=seriesengine&enmse_pid=4",
                    displayName = "Thru the Bible"
                ),
                SourceEntry(
                    feedUrl = "https://www.ccmodesto.com/podcast/podcastam.xml",
                    displayName = "Sunday Morning Service",
                    // The feed's <itunes:image> points at
                    // ccmodesto.com/podcast/podcast-cc.jpg, which is currently
                    // uploaded as a zero-byte file. Override with the working
                    // Topical cover so the expanded child still has CCM-branded
                    // art instead of a placeholder.
                    customArtworkUrl = "https://ccmodesto.sfo2.digitaloceanspaces.com/2026/05/04101315/2026-Topical-podcast-itunes-1400.jpg",
                ),
                SourceEntry(
                    feedUrl = "https://www.ccmodesto.com/podcast/podcast.xml",
                    displayName = "Sunday Evening Service",
                    // Same broken `podcast-cc.jpg` upstream; same override.
                    customArtworkUrl = "https://ccmodesto.sfo2.digitaloceanspaces.com/2026/05/04101315/2026-Topical-podcast-itunes-1400.jpg",
                ),
            )
        ),
        SourceEntry(
            feedUrl = "https://feeds.castos.com/41z28",
            displayName = null
        ),
        // Compelled (Paul Hastings) — long-form conversion testimonies, one
        // guest per episode. This is the Megaphone feed behind
        // compelledpodcast.com; the feed's own title carries the full
        // "Christian Stories & Testimonies" subtitle.
        SourceEntry(
            feedUrl = "https://feeds.megaphone.fm/compelled",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://app.kajabi.com/podcasts/2147488434/feed",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://feeds.megaphone.fm/TNM1365824398",
            displayName = null
        ),
        SourceGroup(
            groupId = "john-piper",
            groupName = "John Piper",
            children = listOf(
                SourceEntry(
                    feedUrl = "https://feed.desiringgod.org/ask-pastor-john.rss",
                    displayName = null
                ),
                SourceEntry(
                    feedUrl = "https://feed.desiringgod.org/solid-joys-audio.rss",
                    displayName = null
                ),
                SourceEntry(
                    feedUrl = "https://feed.desiringgod.org/light-and-truth.rss",
                    displayName = null
                ),
            )
        ),
        SourceEntry(
            feedUrl = "https://feeds.podcastmirror.com/just-thinking-podcast",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://www.sermonaudio.com/rss_source.rss?sourceid=aominorg&filter=mp3&sortby=date",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://feeds.captivate.fm/homeschool-made-simple/",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://albertmohler.com/category/the-briefing/feed/",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://feeds.megaphone.fm/TPOR8396956007",
            displayName = null
        ),
    )

    /**
     * Flattened list of every feed under [PODCASTS] — groups are expanded into
     * their children. The fetch path (CatalogViewModel, PodcastRepository)
     * works at the per-feed level and ignores the grouping; only the catalog
     * screen needs the hierarchical [PODCASTS].
     */
    val PODCAST_FEEDS: List<SourceEntry> = PODCASTS.flatMap { item ->
        when (item) {
            is SourceEntry -> listOf(item)
            is SourceGroup -> item.children
        }
    }

    /** All sources that should be fetched on launch — kabod packs + RSS feeds. */
    val ALL: List<SourceEntry> = KABOD_PACKS + PODCAST_FEEDS

    /**
     * Look up a feed's display name. Children of a [SourceGroup] are returned
     * with the group name prefixed (e.g. "Calvary Chapel Modesto — Topical
     * Studies") so screens outside the catalog (history, metrics) get
     * unambiguous labels. The catalog screen itself reads child names directly
     * off the [SourceEntry] and renders them under the group header without
     * the prefix.
     *
     * Returns null when the entry has no [SourceEntry.displayName] — callers
     * fall back to the feed's own <title>.
     */
    fun displayNameOf(feedUrl: String): String? {
        for (item in PODCASTS) {
            when (item) {
                is SourceEntry -> if (item.feedUrl == feedUrl) return item.displayName
                is SourceGroup -> {
                    val child = item.children.firstOrNull { it.feedUrl == feedUrl }
                    if (child != null) {
                        val childName = child.displayName ?: return null
                        return "${item.groupName} — $childName"
                    }
                }
            }
        }
        return KABOD_PACKS.firstOrNull { it.feedUrl == feedUrl }?.displayName
    }
}
