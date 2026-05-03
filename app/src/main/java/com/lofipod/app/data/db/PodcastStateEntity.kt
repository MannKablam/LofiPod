package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-podcast user state. Keyed by feedUrl (treated as stable, the same way
 * episode_state treats episode GUIDs as stable).
 *
 * Currently only carries the per-podcast default playback speed. The pattern
 * is intentionally generic — anything that's "show-level, persists across
 * episodes" lands here (e.g. a future per-podcast EQ override).
 *
 * `defaultSpeed = null` means "no override — use the player default 1.0x."
 * That's distinct from `defaultSpeed = 1.0f`, which the user explicitly chose
 * (and stays as their choice if they later change the global default).
 */
@Entity(tableName = "podcast_state")
data class PodcastStateEntity(
    @PrimaryKey val feedUrl: String,
    val defaultSpeed: Float? = null,
)
