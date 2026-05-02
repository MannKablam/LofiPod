package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.db.PlaybackCheckpointEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single-file JSON backup of all user-generated state. Includes episode states
 * (favorites, ratings, positions, cumulative listening) and timestamped note entries.
 * Forward-compatible: older backups missing newer fields default cleanly; the
 * schemaVersion gate refuses backups from a strictly newer schema.
 */
object Backup {

    /** Bumped any time the export shape changes incompatibly. */
    const val CURRENT_SCHEMA = 5

    fun export(
        episodes: List<EpisodeStateEntity>,
        noteEntries: List<EpisodeNoteEntryEntity>,
        checkpoints: List<PlaybackCheckpointEntity>,
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
                        put("rating", e.rating)
                        put("isFavorite", e.isFavorite)
                        put("positionMs", e.positionMs)
                        put("durationMs", e.durationMs)
                        put("lastPlayedMillis", e.lastPlayedMillis)
                        put("cumulativeListenMs", e.cumulativeListenMs)
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

        var epRestored = 0
        obj.optJSONArray("episodeState")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                stateDao.upsert(
                    EpisodeStateEntity(
                        guid = e.getString("guid"),
                        feedUrl = e.getString("feedUrl"),
                        title = e.getString("title"),
                        audioUrl = e.getString("audioUrl"),
                        artworkUrl = e.optStringOrNull("artworkUrl"),
                        rating = e.optInt("rating", 0),
                        isFavorite = e.optBoolean("isFavorite", false),
                        positionMs = e.optLong("positionMs", 0L),
                        durationMs = e.optLong("durationMs", 0L),
                        lastPlayedMillis = e.optLong("lastPlayedMillis", 0L),
                        cumulativeListenMs = e.optLong("cumulativeListenMs", 0L)
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

        return ImportResult(epRestored, notesRestored, checkpointsRestored)
    }

    data class ImportResult(
        val episodeCount: Int,
        val noteCount: Int,
        val checkpointCount: Int = 0
    )
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotEmpty() }
}
