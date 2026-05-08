package com.lofipod.app.bible

import com.lofipod.app.data.db.EpisodeKabodDao
import com.lofipod.app.data.db.EpisodeScriptureDao
import com.lofipod.app.data.db.EpisodeScriptureEntity
import com.lofipod.app.data.model.Podcast

/**
 * Owns the read+write side of `episode_scripture`. Two population paths:
 *
 *   - **Kabod-pack backfill** ([backfillFromKabod]) — copies the
 *     authoritative structured refs out of `episode_kabod` into
 *     `episode_scripture` on every app start. Idempotent (REPLACE
 *     conflict policy). Source = `'kabod'`, confidence = 100.
 *
 *   - **RSS auto-tagging** ([tagPodcast]) — runs [ScriptureTagger] over
 *     each episode's title + description, upserts the result. Source =
 *     `'rss-title'` or `'rss-desc'` depending on which field hit. Skips
 *     guids that already have a Kabod-sourced row (we don't overwrite
 *     authoritative data with a regex guess).
 *
 * Re-tagging: [retagAllRss] drops the rss-* rows and the caller can
 * re-invoke [tagPodcast] for every cached podcast. Useful when the
 * regex rules change.
 */
class ScriptureIndexer(
    private val scriptureDao: EpisodeScriptureDao,
    private val kabodDao: EpisodeKabodDao,
) {

    /**
     * Copy every `episode_kabod` row that has a structured book+chapter
     * into `episode_scripture`. Skips rows missing `scriptureBook`
     * (those are kabod entries without explicit refs — won't have a
     * canonical book name to key off of). Runs on first launch and
     * after each Kabod pack import.
     */
    suspend fun backfillFromKabod() {
        val rows = kabodDao.getAll()
        for (kab in rows) {
            val book = kab.scriptureBook?.takeIf { it.isNotBlank() } ?: continue
            // Only commit canonical book names — the canon-browse UI keys
            // off of [BibleCanon.BOOKS] entries. If a pack uses a name we
            // don't know about (typo or extracanonical), drop it and let
            // the pack author fix the metadata.
            if (book !in BibleCanon.BY_NAME) continue
            scriptureDao.upsert(
                EpisodeScriptureEntity(
                    guid = kab.guid,
                    book = book,
                    startCh = kab.scriptureStartCh,
                    startV = kab.scriptureStartV,
                    endCh = kab.scriptureEndCh,
                    endV = kab.scriptureEndV,
                    source = SOURCE_KABOD,
                    confidence = 100,
                )
            )
        }
    }

    /**
     * Run [ScriptureTagger] over each episode of [podcast]. For matches,
     * upsert the result into `episode_scripture` — but only if there's
     * not already a Kabod-sourced row (we never overwrite authoritative
     * structured data with a regex guess). Runs after each successful
     * fetch in `PodcastRepository.fetchFeeds`.
     */
    suspend fun tagPodcast(podcast: Podcast) {
        for (ep in podcast.episodes) {
            val existing = scriptureDao.get(ep.guid)
            if (existing != null && existing.source == SOURCE_KABOD) {
                // Authoritative — leave alone.
                continue
            }
            // No detection: leave any stale rss-* row from a prior tagger
            // pass alone (could be a deliberate user edit in the future;
            // currently no UI for that, but harmless).
            val ref = ScriptureTagger.detect(ep.title, ep.description)
            if (ref == null) continue
            // Skip if the detected book isn't canonical (shouldn't happen
            // because the regex is built from BibleCanon, but defensive).
            if (ref.book !in BibleCanon.BY_NAME) continue
            scriptureDao.upsert(
                EpisodeScriptureEntity(
                    guid = ep.guid,
                    book = ref.book,
                    startCh = ref.startCh,
                    startV = ref.startV,
                    endCh = ref.endCh,
                    endV = ref.endV,
                    source = if (ref.source == ScriptureTagger.Source.TITLE) SOURCE_RSS_TITLE
                    else SOURCE_RSS_DESC,
                    confidence = ref.confidence,
                )
            )
        }
    }

    /**
     * Drop every RSS-tagged row so a re-run of [tagPodcast] over every
     * cached podcast re-populates with current rules. Kabod-sourced rows
     * are preserved.
     */
    suspend fun retagAllRss() {
        scriptureDao.deleteAllRssTagged()
    }

    companion object {
        const val SOURCE_KABOD = "kabod"
        const val SOURCE_RSS_TITLE = "rss-title"
        const val SOURCE_RSS_DESC = "rss-desc"
    }
}
