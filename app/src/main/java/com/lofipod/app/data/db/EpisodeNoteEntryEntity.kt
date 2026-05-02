package com.lofipod.app.data.db

import androidx.room.Entity

/**
 * One timestamped note entry per (episode, instant). Multiple entries per episode
 * accumulate over time like a journal — append/edit/delete supported, but the original
 * [createdAt] (epoch UTC ms) and [playbackPosMs] are immutable for citation stability.
 */
@Entity(
    tableName = "episode_note_entry",
    primaryKeys = ["guid", "createdAt"]
)
data class EpisodeNoteEntryEntity(
    val guid: String,
    val createdAt: Long,        // epoch ms — UTC by definition
    val playbackPosMs: Long,    // playback position when the note was logged
    val text: String
)
