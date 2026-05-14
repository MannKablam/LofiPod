package com.lofipod.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Probes the audio file size for episodes whose RSS feed didn't include
 * a usable `<enclosure length="...">` attribute (some podcast platforms —
 * Megaphone especially — emit `length="0"` for every episode).
 *
 * **Strategy (cheap and reliable):**
 *
 *   1. **HTTP HEAD** request to the audio URL. The `Content-Length`
 *      response header gives the file size. Cost: ~500 bytes per probe.
 *   2. **HTTP GET with `Range: bytes=0-0`** if HEAD doesn't return
 *      `Content-Length` (some CDNs strip it; some return 405 to HEAD).
 *      The `Content-Range: bytes 0-0/<total>` response header contains
 *      the file size. Cost: ~1 KB per probe, but works on essentially
 *      every audio CDN since Range requests are universally supported
 *      for resumable downloads.
 *
 * **Caching:**
 *   - In-memory `StateFlow<Map<String, Long?>>` keyed by audio URL.
 *     `Long?` value distinguishes "probed and got size" from "probed
 *     but no size available" (null sentinel — don't retry indefinitely).
 *   - Persists to JSON file at `filesDir/episode_size_cache.json` so a
 *     subsequent launch renders sizes instantly without re-probing.
 *
 * **Concurrency:** semaphore-capped at [MAX_CONCURRENT] = 4 parallel
 * probes so we don't hammer a host with hundreds of HEAD requests
 * during a fast scroll. Probes are lazy / on-demand (called from
 * [EpisodeRow] when a row composes) — not eager.
 *
 * **Dedup:** in-session `Set<String>` of URLs we've already attempted.
 * Re-rendering the same row doesn't re-probe; cache reads short-circuit.
 *
 * **Why we care:** other podcast apps just hide the size when the
 * feed doesn't provide it. LofiPod showing the actual size for
 * podcasts like The Pour Over (Megaphone-hosted, all `length="0"`) is
 * a small but real differentiator.
 */
class EpisodeSizeProber(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** URLs we've already started or finished probing in this session.
     *  Concurrent because [probe] is called from the UI thread / many
     *  composables; the actual probe coroutine runs on IO. */
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    /** In-memory cache: audioUrl -> probed result. `null` value means
     *  "probed but host didn't return size — don't retry." Map absent =
     *  "not yet probed." */
    private val _sizes = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val sizes: StateFlow<Map<String, Long?>> = _sizes.asStateFlow()

    private val cacheFile: File by lazy { File(context.filesDir, CACHE_FILENAME) }

    init {
        // Hydrate the in-memory cache from disk. Synchronous in init so
        // first composable read sees cached values immediately.
        runCatching {
            if (cacheFile.exists()) {
                val text = cacheFile.readText()
                if (text.isNotBlank()) {
                    val obj = JSONObject(text)
                    val loaded = mutableMapOf<String, Long?>()
                    obj.keys().forEach { k ->
                        val v = obj.get(k)
                        loaded[k] = if (v is Number) v.toLong() else null
                    }
                    _sizes.value = loaded
                }
            }
        }
    }

    /**
     * Look up a previously-probed size. Returns null if not yet probed
     * OR if probed but host didn't return a size. Use [probe] to kick
     * off a new lookup.
     */
    fun cachedSize(audioUrl: String): Long? = _sizes.value[audioUrl]

    /**
     * Kick off a probe for [audioUrl]. Idempotent: re-calling with the
     * same URL in the same session is a no-op. Returns immediately; the
     * actual HTTP request runs on [scope]. When the probe completes,
     * the result is written to [sizes] (which any UI observing the flow
     * will see), and the JSON cache file is updated.
     */
    fun probe(audioUrl: String) {
        if (audioUrl.isBlank()) return
        if (!attempted.add(audioUrl)) return  // already probed/probing this session
        if (_sizes.value.containsKey(audioUrl)) return  // already cached from disk

        scope.launch {
            semaphore.withPermit {
                val size = headProbe(audioUrl) ?: rangeProbe(audioUrl)
                recordResult(audioUrl, size)
            }
        }
    }

    /** HTTP HEAD probe. Returns Content-Length when present and > 0,
     *  null otherwise. */
    private suspend fun headProbe(url: String): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** HTTP GET with Range: bytes=0-0. The server's 206 response includes
     *  `Content-Range: bytes 0-0/<total>` — the total is the file size.
     *  Falls back to checking Content-Length on a 200 response (server
     *  didn't honor Range; if the file is small enough it might have
     *  served the whole body, which we close immediately via .use). */
    private suspend fun rangeProbe(url: String): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use null
                val cr = resp.header("Content-Range")
                if (cr != null) {
                    // "bytes 0-0/12345" — take everything after the last "/"
                    cr.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0L }
                } else {
                    // Server ignored Range and sent the whole body OR
                    // populated Content-Length on the 206 anyway.
                    resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun recordResult(audioUrl: String, size: Long?) {
        _sizes.value = _sizes.value + (audioUrl to size)
        // Persist asynchronously. Multiple results landing in quick
        // succession all see the same StateFlow value at write time, so
        // each writes the up-to-date snapshot — last-write-wins is fine.
        scope.launch {
            persistCache()
        }
    }

    private suspend fun persistCache() = withContext(Dispatchers.IO) {
        runCatching {
            val obj = JSONObject()
            _sizes.value.forEach { (url, size) ->
                // Store null sentinels as JSON null so the sentinel meaning
                // round-trips through disk persistence.
                if (size != null) {
                    obj.put(url, size)
                } else {
                    obj.put(url, JSONObject.NULL)
                }
            }
            cacheFile.writeText(obj.toString())
        }
    }

    companion object {
        /** Max parallel probes. 4 is conservative enough that no podcast
         *  host should see us as abusive; high enough that a scroll-driven
         *  burst of ~15 probes drains in under 2 seconds. */
        private const val MAX_CONCURRENT = 4

        /** Cache filename under [filesDir]. JSON object: audioUrl -> size
         *  (or null for "probed but no size"). */
        private const val CACHE_FILENAME = "episode_size_cache.json"
    }
}
