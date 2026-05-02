package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-episode user state. Keyed by episode GUID (which we treat as stable).
 */
@Entity(tableName = "episode_state")
data class EpisodeStateEntity(
    @PrimaryKey val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val rating: Int = 0,             // 0 = unrated, 1..5 stars
    val isFavorite: Boolean = false,
    val positionMs: Long = 0L,       // last playback position
    val durationMs: Long = 0L,       // last known duration
    val lastPlayedMillis: Long = 0L,
    /** Total ms the user has actually played this episode (sum of ticker intervals). */
    val cumulativeListenMs: Long = 0L
)
