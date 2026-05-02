package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeNoteEntity
import com.lofipod.app.data.db.EpisodeStateEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single-file JSON backup of all user-generated state. Includes episode states
 * (favorites, ratings, positions, cumulative listening) and notes. Forward-compatible:
 * older backups missing newer fields default cleanly; the schemaVersion gate refuses
 * backups from a strictly newer schema.
 */
object Backup {

    /** Bumped any time the export shape changes incompatibly. */
    const val CURRENT_SCHEMA = 3

    fun export(
        episodes: List<EpisodeStateEntity>,
        notes: List<EpisodeNoteEntity>,
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
            put("notes", JSONArray().apply {
                notes.forEach { n ->
                    put(JSONObject().apply {
                        put("guid", n.guid)
                        put("text", n.text)
                        put("updatedAt", n.updatedAt)
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
        val noteDao = db.episodeNoteDao()

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
        obj.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                noteDao.upsert(
                    EpisodeNoteEntity(
                        guid = n.getString("guid"),
                        text = n.getString("text"),
                        updatedAt = n.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
                notesRestored++
            }
        }
        return ImportResult(epRestored, notesRestored)
    }

    data class ImportResult(val episodeCount: Int, val noteCount: Int)
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotEmpty() }
}
