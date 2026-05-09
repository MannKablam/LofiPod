package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-podcast user state. Keyed by feedUrl (treated as stable, the same way
 * episode_state treats episode GUIDs as stable).
 *
 * Carries:
 *   - [defaultSpeed]: per-podcast playback speed override (null = no override).
 *   - [eqDisabled]: **deprecated as of v0.6.12.** No code reads this. The
 *     "disable EQ" concept was redundant with "set bands to flat"; the
 *     master "Audio enhancement" toggle in Settings still gates the whole
 *     DSP chain globally. Column kept on schema only so v0.6.11 → v0.6.12
 *     doesn't need a migration to drop it.
 *   - [eqBandsCsvOverride]: per-podcast EQ tuning (CSV of dB floats in
 *     band order). Null = the podcast hasn't been tuned yet → chain runs
 *     flat for its episodes. Non-null = these gains apply to every episode
 *     of this podcast unless that specific episode has a one-off override
 *     of its own (`episode_state.eqBandsCsvOverride`).
 *
 * Inheritance model (v0.6.12): each podcast owns its own EQ — there is NO
 * global EQ. An episode normally inherits its podcast's tuning. The
 * per-episode override is the one branch point, scoped to a single episode.
 */
@Entity(tableName = "podcast_state")
data class PodcastStateEntity(
    @PrimaryKey val feedUrl: String,
    val defaultSpeed: Float? = null,
    val eqDisabled: Boolean = false,
    val eqBandsCsvOverride: String? = null,
)
