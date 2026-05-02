package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One slot in the user's playback queue. Identity is the episode [guid] (you can
 * only queue an episode once at a time). [position] gives ordering — smaller
 * means "play sooner". Positions are kept dense by reorder operations but can
 * be sparse between operations; sort by [position] ASC at read time.
 *
 * Snapshot fields ([title], [feedUrl], [audioUrl], [artworkUrl]) let the queue
 * page render and play episodes whose feed isn't currently loaded into the
 * in-memory cache (e.g. on a fresh app start). They mirror the same idea as
 * EpisodeStateEntity — favor self-sufficiency for offline-friendly browsing.
 */
@Entity(tableName = "queue_entry")
data class QueueEntryEntity(
    @PrimaryKey val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val position: Long,
    val addedAt: Long,
)
