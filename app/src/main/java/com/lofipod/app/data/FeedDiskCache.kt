package com.lofipod.app.data

import android.content.Context
import android.util.Log
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Simple disk cache for parsed [Podcast] objects. One JSON file per feed
 * under `<filesDir>/feeds/`. Files are named by SHA-256(feedUrl) so URLs
 * with weird characters don't trip filesystem rules.
 *
 * Why disk cache: cold starts on slower devices were spending tens of
 * seconds re-fetching + re-parsing every RSS feed before the Catalog
 * could render. With a disk cache the in-memory cache hydrates from
 * disk in milliseconds, the Catalog renders immediately with stale-but-
 * valid data, and the network refresh happens in the background.
 *
 * Format: a Podcast is encoded as a flat JSONObject. Episodes go in a
 * "episodes" JSONArray. Hand-rolled because the existing project uses
 * org.json (Backup.kt) and adding kotlinx.serialization would mean a
 * gradle plugin + dep. The encoder is intentionally lossy — it only
 * persists fields that the runtime reads back from cache; transient
 * fields can be skipped.
 *
 * Versioning: a top-level "version" int gates schema reads. If the on-
 * disk version doesn't match [CURRENT_VERSION], the file is ignored
 * (and overwritten on next fetch). No explicit migration — feeds will
 * just re-fetch once.
 */
class FeedDiskCache(context: Context) {

    private val dir: File = File(context.filesDir, "feeds").apply { mkdirs() }

    /** All cached podcasts, keyed by feedUrl. Reads every JSON file in the
     *  cache directory; expects O(N) files where N is the source count.
     *  Safe to call from any thread; uses blocking File I/O. */
    fun readAll(): Map<String, Podcast> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val map = HashMap<String, Podcast>(files.size)
        for (f in files) {
            try {
                val pod = decode(f.readText())
                map[pod.feedUrl] = pod
            } catch (e: Exception) {
                Log.w(TAG, "Bad cache file ${f.name}; deleting", e)
                runCatching { f.delete() }
            }
        }
        return map
    }

    /** Persist a single podcast. Overwrites any existing file. */
    fun write(podcast: Podcast) {
        try {
            val file = File(dir, fileName(podcast.feedUrl))
            file.writeText(encode(podcast))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist feed cache for ${podcast.feedUrl}", e)
        }
    }

    /** Read the cached Podcast for [feedUrl], or null if not present. */
    fun read(feedUrl: String): Podcast? {
        val file = File(dir, fileName(feedUrl))
        if (!file.isFile) return null
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Bad cache file ${file.name}; deleting", e)
            runCatching { file.delete() }
            null
        }
    }

    /** Drop the cache file for [feedUrl]. Used when a feed is removed. */
    fun delete(feedUrl: String) {
        runCatching { File(dir, fileName(feedUrl)).delete() }
    }

    private fun fileName(feedUrl: String): String =
        "${sha256(feedUrl)}.json"

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "LofiPodFeedCache"
        private const val CURRENT_VERSION = 1

        fun encode(p: Podcast): String {
            val obj = JSONObject().apply {
                put("version", CURRENT_VERSION)
                put("feedUrl", p.feedUrl)
                put("title", p.title)
                if (p.author != null) put("author", p.author)
                if (p.description != null) put("description", p.description)
                if (p.artworkUrl != null) put("artworkUrl", p.artworkUrl)
                put("episodes", JSONArray().apply {
                    for (e in p.episodes) put(encodeEpisode(e))
                })
            }
            return obj.toString()
        }

        fun decode(json: String): Podcast {
            val obj = JSONObject(json)
            val ver = obj.optInt("version", 0)
            if (ver != CURRENT_VERSION) {
                throw IllegalStateException("cache version mismatch: $ver != $CURRENT_VERSION")
            }
            val episodes = obj.optJSONArray("episodes")?.let { arr ->
                List(arr.length()) { i -> decodeEpisode(arr.getJSONObject(i)) }
            } ?: emptyList()
            return Podcast(
                feedUrl = obj.getString("feedUrl"),
                title = obj.getString("title"),
                author = obj.optStringOrNull("author"),
                description = obj.optStringOrNull("description"),
                artworkUrl = obj.optStringOrNull("artworkUrl"),
                episodes = episodes,
            )
        }

        private fun encodeEpisode(e: Episode): JSONObject = JSONObject().apply {
            put("guid", e.guid)
            put("feedUrl", e.feedUrl)
            put("title", e.title)
            if (e.description != null) put("description", e.description)
            if (e.pubDateMillis != null) put("pubDateMillis", e.pubDateMillis)
            put("audioUrl", e.audioUrl)
            if (e.audioMimeType != null) put("audioMimeType", e.audioMimeType)
            if (e.durationSeconds != null) put("durationSeconds", e.durationSeconds)
            if (e.episodeArtworkUrl != null) put("episodeArtworkUrl", e.episodeArtworkUrl)
            if (e.audioByteSize != null) put("audioByteSize", e.audioByteSize)
        }

        private fun decodeEpisode(o: JSONObject): Episode = Episode(
            guid = o.getString("guid"),
            feedUrl = o.getString("feedUrl"),
            title = o.getString("title"),
            description = o.optStringOrNull("description"),
            pubDateMillis = if (o.has("pubDateMillis")) o.getLong("pubDateMillis") else null,
            audioUrl = o.getString("audioUrl"),
            audioMimeType = o.optStringOrNull("audioMimeType"),
            durationSeconds = if (o.has("durationSeconds")) o.getLong("durationSeconds") else null,
            episodeArtworkUrl = o.optStringOrNull("episodeArtworkUrl"),
            // 0 here means "host emitted length=\"0\" in RSS, which we
            // pre-filter to null at parse time as of v0.10.7 — but older
            // cached feeds might still contain 0 from before that fix.
            // takeIf { > 0 } makes the cache reader self-healing so users
            // don't have to force-refresh every podcast post-upgrade.
            audioByteSize = if (o.has("audioByteSize")) {
                o.getLong("audioByteSize").takeIf { it > 0L }
            } else null,
        )

        /** org.json's optString returns "" for missing keys; for our
         *  null-aware data classes we want a real null instead. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotEmpty() } else null
    }
}
