package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Channel-level metadata for a Kabod Pack — an "archived RSS"-style packaged
 * feed for completed bodies of work (preaching series, etc.) that no longer
 * receive new entries. The pack is a packageable pointer: it references audio
 * URLs and transcript URLs but doesn't bundle the media itself.
 *
 * `feedUrl` uses a synthetic `kabod://<packId>` scheme so it shares the same
 * identity space as RSS feeds throughout the app — episodes, queue, favorites,
 * notes, and downloads all key off `(feedUrl, guid)` without needing to know
 * whether the source is RSS or a Kabod Pack.
 */
@Entity(tableName = "kabod_pack")
data class KabodPackEntity(
    @PrimaryKey val packId: String,
    val feedUrl: String,
    val title: String,
    val speaker: String?,
    val bookOfBible: String?,
    val sourceSite: String?,
    val archived: Boolean,
    val installedAt: Long,
)
