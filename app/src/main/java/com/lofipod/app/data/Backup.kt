package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.db.PlaybackCheckpointEntity
import com.lofipod.app.data.db.PodcastStateEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single-file JSON backup of all user-generated state. Includes episode states
 * (favorites, positions, cumulative listening), timestamped note entries,
 * playback checkpoints, and per-podcast preferences.
 *
 * Forward-compatible: older backups missing newer fields default cleanly; the
 * schemaVersion gate refuses backups from a strictly newer schema.
 */
object Backup {

    /**
     * Bumped any time the export shape changes incompatibly.
     * - v6 swapped per-episode rating + isFavorite for favoriteTier.
     * - v7 adds podcastState[] (per-podcast prefs, currently default speed).
     *   v7 backups are still readable on v6 builds — the array is ignored.
     */
    const val CURRENT_SCHEMA = 7

    fun export(
        episodes: List<EpisodeStateEntity>,
        noteEntries: List<EpisodeNoteEntryEntity>,
        checkpoints: List<PlaybackCheckpointEntity>,
        podcastStates: List<PodcastStateEntity>,
        appVersion: String
    ): String {
        val obj = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA)
            put("appVersion", appVersion)
            put("exportedAt", System.currentTimeMillis())
            put("episodeState", JSONArray().apply {
                episodes.forEach { e ->
                    put(JSONObject().apply {
                        put("guid", e.guid)
                        put("feedUrl", e.feedUrl)
                        put("title", e.title)
                        put("audioUrl", e.audioUrl)
                        if (e.artworkUrl != null) put("artworkUrl", e.artworkUrl)
                        put("favoriteTier", e.favoriteTier)
                        put("positionMs", e.positionMs)
                        put("durationMs", e.durationMs)
                        put("lastPlayedMillis", e.lastPlayedMillis)
                        put("cumulativeListenMs", e.cumulativeListenMs)
                        if (e.eqDisabled) put("eqDisabled", true)
                        if (e.archivedAt > 0) put("archivedAt", e.archivedAt)
                    })
                }
            })
            put("noteEntries", JSONArray().apply {
                noteEntries.forEach { n ->
                    put(JSONObject().apply {
                        put("guid", n.guid)
                        put("createdAt", n.createdAt)
                        put("playbackPosMs", n.playbackPosMs)
                        put("text", n.text)
                    })
                }
            })
            put("playbackCheckpoints", JSONArray().apply {
                checkpoints.forEach { c ->
                    put(JSONObject().apply {
                        // id intentionally omitted — restored as a fresh autoincrement row
                        put("guid", c.guid)
                        put("positionMs", c.positionMs)
                        put("recordedAt", c.recordedAt)
                        put("reason", c.reason)
                    })
                }
            })
            put("podcastState", JSONArray().apply {
                podcastStates.forEach { p ->
                    put(JSONObject().apply {
                        put("feedUrl", p.feedUrl)
                        if (p.defaultSpeed != null) put("defaultSpeed", p.defaultSpeed.toDouble())
                    })
                }
            })
        }
        return obj.toString(2)
    }

    suspend fun importInto(json: String, db: AppDatabase): ImportResult {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not valid JSON: ${e.message}")
        }
        val schema = obj.optInt("schemaVersion", 0)
        if (schema == 0) throw IllegalArgumentException("Not a LofiPod backup file")
        if (schema > CURRENT_SCHEMA) {
            throw IllegalArgumentException(
                "Backup is from a newer app version (schema v$schema, this app supports v$CURRENT_SCHEMA). Update the app and try again."
            )
        }

        val stateDao = db.episodeStateDao()
        val noteDao = db.episodeNoteEntryDao()
        val checkpointDao = db.playbackCheckpointDao()
        val podcastStateDao = db.podcastStateDao()

        var epRestored = 0
        obj.optJSONArray("episodeState")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                // Tier comes from the new field if present; otherwise convert
                // v5 rating + isFavorite using the same rule as the SQL migration:
                // rating==5 → 2, rating>=4 OR isFavorite → 1, else 0.
                val tier = if (e.has("favoriteTier")) {
                    e.optInt("favoriteTier", 0)
                } else {
                    val r = e.optInt("rating", 0)
                    val isFav = e.optBoolean("isFavorite", false)
                    when {
                        r >= 5 -> 2
                        r >= 4 || isFav -> 1
                        else -> 0
                    }
                }
                stateDao.upsert(
                    EpisodeStateEntity(
                        guid = e.getString("guid"),
                        feedUrl = e.getString("feedUrl"),
                        title = e.getString("title"),
                        audioUrl = e.getString("audioUrl"),
                        artworkUrl = e.optStringOrNull("artworkUrl"),
                        favoriteTier = tier,
                        positionMs = e.optLong("positionMs", 0L),
                        durationMs = e.optLong("durationMs", 0L),
                        lastPlayedMillis = e.optLong("lastPlayedMillis", 0L),
                        cumulativeListenMs = e.optLong("cumulativeListenMs", 0L),
                        eqDisabled = e.optBoolean("eqDisabled", false),
                        archivedAt = e.optLong("archivedAt", 0L)
                    )
                )
                epRestored++
            }
        }

        var notesRestored = 0
        // Schema 4+ shape — multi-entry notes.
        obj.optJSONArray("noteEntries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                noteDao.upsert(
                    EpisodeNoteEntryEntity(
                        guid = n.getString("guid"),
                        createdAt = n.getLong("createdAt"),
                        playbackPosMs = n.optLong("playbackPosMs", 0L),
                        text = n.getString("text")
                    )
                )
                notesRestored++
            }
        }
        // Schema 3 legacy "notes" array (single-text-per-guid). UI never shipped against
        // it, so in practice this is empty; we still translate any rows present so users
        // who manually authored backups don't lose data.
        if (schema <= 3) {
            obj.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.getJSONObject(i)
                    noteDao.upsert(
                        EpisodeNoteEntryEntity(
                            guid = n.getString("guid"),
                            createdAt = n.optLong("updatedAt", System.currentTimeMillis()),
                            playbackPosMs = 0L,
                            text = n.getString("text")
                        )
                    )
                    notesRestored++
                }
            }
        }
        var checkpointsRestored = 0
        obj.optJSONArray("playbackCheckpoints")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                checkpointDao.insert(
                    PlaybackCheckpointEntity(
                        guid = c.getString("guid"),
                        positionMs = c.getLong("positionMs"),
                        recordedAt = c.getLong("recordedAt"),
                        reason = c.getString("reason")
                    )
                )
                checkpointsRestored++
            }
            // Honor the global cap after a bulk import.
            checkpointDao.pruneToCount(200)
        }

        var podcastStatesRestored = 0
        obj.optJSONArray("podcastState")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val speed = if (p.has("defaultSpeed") && !p.isNull("defaultSpeed")) {
                    p.getDouble("defaultSpeed").toFloat()
                } else null
                podcastStateDao.upsert(
                    PodcastStateEntity(
                        feedUrl = p.getString("feedUrl"),
                        defaultSpeed = speed,
                    )
                )
                podcastStatesRestored++
            }
        }

        return ImportResult(epRestored, notesRestored, checkpointsRestored, podcastStatesRestored)
    }

    data class ImportResult(
        val episodeCount: Int,
        val noteCount: Int,
        val checkpointCount: Int = 0,
        val podcastStateCount: Int = 0,
    )
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotEmpty() }
}
