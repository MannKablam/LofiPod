package com.lofipod.app.data

import com.lofipod.app.parser.SourceEntry

/**
 * The canon. The runtime library reads from this list — there is no in-app way
 * to add or remove podcasts. To change the canon, edit this file, commit, build,
 * sideload. That deliberate friction is the feature.
 *
 * Feed URLs were originally verified via the iTunes Lookup API. The IDs below
 * are recorded so a host migration can be re-resolved by re-querying Apple:
 *
 *   Thru the Bible (Damian Kyle)        — iTunes 1465962073
 *   Alpha and Omega Ministries          — iTunes 1004561753
 *   BibleThinker (Mike Winger)          — iTunes 1358056327
 *   Ask Pastor John                     — iTunes 618132843
 *   Solid Joys (audio)                  — iTunes 1315817340
 *   Light + Truth                       — iTunes 955100693
 *   Just Thinking                       — iTunes 1328733796
 *   Bethany Bible Church                — iTunes 1688817318
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
    )

    val PODCASTS: List<SourceEntry> = listOf(
        SourceEntry(
            feedUrl = "https://ccmodesto.com/?feed=seriesengine&enmse_pid=4",
            displayName = "Thru the Bible with Damian Kyle"
        ),
        SourceEntry(
            feedUrl = "https://www.sermonaudio.com/rss_source.rss?sourceid=aominorg&filter=mp3&sortby=date",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://feeds.castos.com/41z28",
            displayName = null
        ),
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
        SourceEntry(
            feedUrl = "https://anchor.fm/s/3463c2f0/podcast/rss",
            displayName = null
        ),
        SourceEntry(
            feedUrl = "https://www.bethanyto.org/feeds/media-libraries/b9b11184-9a80-11ec-b043-0614187498c1",
            displayName = null
        )
    )

    /** All sources that should appear in the catalog — kabod packs + RSS feeds. */
    val ALL: List<SourceEntry> = KABOD_PACKS + PODCASTS

    /** Convenience: look up a podcast's display name (or null) by its feed URL. */
    fun displayNameOf(feedUrl: String): String? =
        ALL.firstOrNull { it.feedUrl == feedUrl }?.displayName
}
