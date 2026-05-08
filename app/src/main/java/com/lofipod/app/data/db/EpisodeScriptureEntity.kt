package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Detected (or imported) Bible passage reference for an episode. Mirrors
 * the structured shape of `<kabod:scriptureRef book="..." startCh="..."
 * startV="..." endCh="..." endV="..."/>` from the Kabod schema, so a row
 * here from auto-tagged RSS and a row from a Kabod pack are queryable
 * with the same SQL.
 *
 * Population paths:
 *   - Kabod packs: backfilled from `episode_kabod` on import.
 *   - RSS feeds: produced by [com.lofipod.app.bible.ScriptureTagger] on
 *     the title and description after each fetch.
 *
 * Re-tagging: drop all rows where `source` starts with 'rss-' and re-run
 * the tagger. Kabod-sourced rows are not re-tagged (they're authoritative).
 *
 * Confidence + source columns let the UI show "auto-tagged from
 * description" vs "from Kabod metadata" if useful, and let queries filter
 * out low-confidence detections.
 *
 * Index `idx_episode_scripture_book_ch` covers the canon-browse queries
 * (`forBook`, `coveredChaptersIn`, `coveredVersesIn`, `nextInCanon`)
 * which all filter on book + startCh and sort by startV. The name
 * matches the explicit CREATE INDEX in MIGRATION_14_15 so Room's
 * post-migration schema validation finds the same index it expects.
 */
@Entity(
    tableName = "episode_scripture",
    indices = [
        Index(
            name = "idx_episode_scripture_book_ch",
            value = ["book", "startCh", "startV"],
        ),
    ],
)
data class EpisodeScriptureEntity(
    @PrimaryKey val guid: String,
    /** Canonical book name from [com.lofipod.app.bible.BibleCanon]. */
    val book: String,
    val startCh: Int? = null,
    val startV: Int? = null,
    val endCh: Int? = null,
    val endV: Int? = null,
    /** 'kabod' for Kabod-pack-imported rows; 'rss-title' or 'rss-desc'
     *  for tagger output. Drives re-tag eligibility and UI affordances. */
    val source: String,
    val confidence: Int,    // 0..100
)
