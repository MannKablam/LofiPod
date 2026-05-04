package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per podcast in the user's in-app catalog. Imports from a sources file
 * upsert into this table; future fetches read from it directly. Keyed by feed URL.
 */
@Entity(tableName = "podcast_source")
data class PodcastSourceEntity(
    @PrimaryKey val feedUrl: String,
    val displayName: String?,
    val addedAtMillis: Long
)
