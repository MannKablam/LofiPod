package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-feed "I've seen this" timestamp. Drives the new-episodes badge on the
 * Catalog screen: any episode whose pubDate is after [lastVisitedAt] is "new"
 * relative to the user's last engagement with that feed. Updated when the user
 * opens the feed's EpisodesScreen and when an episode from the feed starts
 * playing — both are strong signals that the user is current with the feed.
 */
@Entity(tableName = "feed_visit")
data class FeedVisitEntity(
    @PrimaryKey val feedUrl: String,
    val lastVisitedAt: Long
)
