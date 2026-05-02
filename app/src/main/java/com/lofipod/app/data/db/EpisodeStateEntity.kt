package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-episode user state. Keyed by episode GUID (which we treat as stable).
 *
 * The favorite system is a single 3-state tier (replacing the old 1-5 star
 * rating + boolean isFavorite). A heart-tap cycles 0 → 1 (Excellent) → 2
 * (Most-excellent) → 0. Two tiers in the UI hit the sweet spot of "I liked
 * this" vs "this one was a standout" without the visual weight of a 5-star row.
 */
@Entity(tableName = "episode_state")
data class EpisodeStateEntity(
    @PrimaryKey val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val artworkUrl: String?,
    /** 0 = none, 1 = Excellent, 2 = Most-excellent. */
    val favoriteTier: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastPlayedMillis: Long = 0L,
    val cumulativeListenMs: Long = 0L,
    /**
     * When true, audio enhancement (EQ + boost) is forced off for this episode
     * regardless of the global setting. Useful for guest-heavy episodes whose
     * voice profile doesn't match the podcast's usual host-tuned EQ.
     */
    val eqDisabled: Boolean = false,
    /**
     * Epoch ms when the episode was archived. 0 = not archived. Archived
     * episodes are filtered out of the per-podcast Episodes list by default
     * but still surface in the My-Lists Excellent / Most-excellent tabs so
     * favorited content never disappears on the user. The auto-archive sweep
     * sets this for played episodes whose lastPlayedMillis is older than the
     * configured grace period.
     */
    val archivedAt: Long = 0L
)

object FavoriteTier {
    const val NONE = 0
    const val EXCELLENT = 1
    const val MOST_EXCELLENT = 2
}
