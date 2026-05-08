package com.lofipod.app.data.db

import androidx.room.Entity
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
 */
@Entity(tableName = "episode_scripture")
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
