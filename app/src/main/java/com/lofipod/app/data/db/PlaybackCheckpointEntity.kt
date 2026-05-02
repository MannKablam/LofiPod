package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Snapshot of a playback position recorded when the user is "leaving where they were."
 * Two trigger reasons:
 *   - "jump_from": user jumped via a note (or history) — captures the FROM position so
 *     the Return chip / History view can take them back.
 *   - "session_end": session boundary — either resumed an episode after [SESSION_GAP_MS]
 *     of inactivity, or switched to a different episode while one was loaded.
 *
 * Capped globally at 200 rows; oldest evicted on insert.
 */
@Entity(tableName = "playback_checkpoint")
data class PlaybackCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val guid: String,
    val positionMs: Long,
    val recordedAt: Long,    // epoch ms — UTC by definition
    val reason: String       // "jump_from" | "session_end"
)
