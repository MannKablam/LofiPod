package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.FavoriteTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Auto-generated promotion notes. Promoting an episode to Excellent or
 * Most-excellent drops a canned note into the episode's notes so the
 * moment of anointment surfaces everywhere notes do (Player Notes tab,
 * per-episode Notes screen, global notes browser) — not just in the
 * Playback History checkpoint stream.
 *
 * Demotions/clears (tier back to 0) intentionally write nothing: the
 * promotion notes stay behind as a historical record and can be deleted
 * by hand like any other note.
 *
 * At most one note per tier per episode: the heart cycles 0→1→2→0, so a
 * user tapping around the cycle (or through tier 1 on the way to tier 2)
 * would otherwise leave a fresh pair of promotion notes on every lap.
 */
suspend fun recordPromotionNote(
    db: AppDatabase,
    guid: String,
    newTier: Int,
    playbackPosMs: Long,
) = withContext(Dispatchers.IO) {
    val text = when (newTier) {
        FavoriteTier.EXCELLENT -> "Promoted to Excellent"
        FavoriteTier.MOST_EXCELLENT -> "Promoted to most-excellent"
        else -> return@withContext
    }
    if (db.episodeNoteEntryDao().getForEpisode(guid).any { it.text == text }) {
        return@withContext
    }
    db.episodeNoteEntryDao().upsert(
        EpisodeNoteEntryEntity(
            guid = guid,
            createdAt = System.currentTimeMillis(),
            playbackPosMs = playbackPosMs,
            text = text,
        )
    )
}
