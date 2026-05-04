package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached transcript for an episode. Populated lazily when the user opens the
 * Transcript tab — fetch the [sourceUrl], parse with [TranscriptHtmlParser],
 * persist here. Subsequent opens read from this row instead of re-hitting
 * the network.
 *
 * Paragraphs are stored as a JSON array of strings rather than newline-joined
 * text so the renderer can preserve paragraph boundaries (and we don't have
 * to escape any sentinel character that might appear in transcript prose).
 */
@Entity(tableName = "episode_transcript")
data class EpisodeTranscriptEntity(
    @PrimaryKey val guid: String,
    val sourceUrl: String,
    val paragraphsJson: String,
    val fetchedAt: Long,
)
