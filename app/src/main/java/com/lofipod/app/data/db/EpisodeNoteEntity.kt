package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One free-text note per episode. Keyed by episode GUID. Notes survive backup/restore
 * and are forward-compatible with a future "search notes" UI.
 */
@Entity(tableName = "episode_note")
data class EpisodeNoteEntity(
    @PrimaryKey val guid: String,
    val text: String,
    val updatedAt: Long
)
