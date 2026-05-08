package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Marks a download as "auto-fired" (created by [PlayerController.playEpisode]
 * starting an episode the user hadn't manually downloaded). Presence of a
 * row implies the download is on a 1-hour-after-finished expiration clock;
 * absence means the download is either user-triggered (kept until manual
 * remove) or doesn't exist.
 *
 * Inserted by the auto-download path in `playEpisode`. Deleted by:
 *   - manual download triggers (user pressing the download button — converts
 *     the download from "auto" to "manual")
 *   - manual remove (user removing the download)
 *   - the periodic expiration sweep when the episode has finished playing
 *     and the user hasn't engaged with it for >1 hour
 *
 * Lives as its own table rather than a column on [EpisodeStateEntity] so
 * the auto-vs-manual distinction is purely additive — no schema migration
 * for episode_state, no risk of stale flags surviving an episode_state
 * upsert.
 */
@Entity(tableName = "auto_download")
data class AutoDownloadEntity(
    @PrimaryKey val guid: String,
    val createdAt: Long,
)
