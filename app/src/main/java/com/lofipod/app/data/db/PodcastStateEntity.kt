package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-podcast user state. Keyed by feedUrl (treated as stable, the same way
 * episode_state treats episode GUIDs as stable).
 *
 * Carries:
 *   - [defaultSpeed]: per-podcast playback speed override (null = no override).
 *   - [eqDisabled]: when true, the audio enhancement chain is forced off for
 *     EVERY episode of this podcast. Distinct from the master "Audio
 *     enhancement" toggle in Settings, which gates everything; this one
 *     overrides FOR this podcast only.
 *   - [eqBandsCsvOverride]: when non-null, these per-band gains (CSV of
 *     dB floats in band order) replace the global Settings.eqBandsCsv for
 *     EVERY episode of this podcast. Useful for podcasts whose tonal balance
 *     is consistently different from the user's preferred default.
 *
 * The EQ fields used to live on `episode_state` (per-episode) — moved up to
 * the podcast level in v0.6.11 because the user expected "tweaking the EQ
 * for one episode of a podcast" to apply to that whole podcast's catalog,
 * not just that single episode. Per-episode granularity wasn't useful in
 * practice.
 */
@Entity(tableName = "podcast_state")
data class PodcastStateEntity(
    @PrimaryKey val feedUrl: String,
    val defaultSpeed: Float? = null,
    val eqDisabled: Boolean = false,
    val eqBandsCsvOverride: String? = null,
)
