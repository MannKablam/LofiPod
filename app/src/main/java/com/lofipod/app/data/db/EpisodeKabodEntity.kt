package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-episode Kabod-specific metadata: scripture passage, transcript URL,
 * sermon part number, speaker. Lives alongside [EpisodeStateEntity] keyed
 * by the same `guid`, so playback state and kabod metadata are independently
 * queryable but share identity.
 *
 * Scripture is stored both as a display string ([scripture]) and in
 * structured form (book + start/end ch/v) so we can build deep links to
 * Bible apps (e.g. Logos via ref.ly) without re-parsing display text.
 */
@Entity(tableName = "episode_kabod")
data class EpisodeKabodEntity(
    @PrimaryKey val guid: String,
    val packId: String,
    val partNumber: Int?,
    val scripture: String?,
    val scriptureBook: String?,
    val scriptureStartCh: Int?,
    val scriptureStartV: Int?,
    val scriptureEndCh: Int?,
    val scriptureEndV: Int?,
    val transcriptUrl: String?,
    val transcriptSelector: String?,
    val speaker: String?,
)
