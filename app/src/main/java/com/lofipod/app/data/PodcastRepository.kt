package com.lofipod.app.data

import android.content.Context
import android.net.Uri
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.diagnostics.StartupTimings
import com.lofipod.app.parser.RssParser
import com.lofipod.app.parser.SourceEntry
import com.lofipod.app.parser.SourcesFileParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Fetches the user's sources file via SAF, parses it, then fetches each feed.
 * Keeps a tiny in-memory cache so we don't re-hit the network on every screen change.
 */
class PodcastRepository(
    private val context: Context
) {
    /**
     * Some podcast hosts (e.g. ccmodesto.com fronted by mod_security) reject
     * non-browser-like User-Agents with HTTP 406. A Mozilla-style UA gets through
     * everywhere we've tested without any other change.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", BROWSER_UA)
                .build()
            chain.proceed(req)
        }
        .build()

    /**
     * Optional Kabod Pack loader. When set, [fetchOne] routes feeds with the
     * `kabod://` scheme through it instead of HTTP. Wired up by [com.lofipod.app.LofiPodApp].
     */
    @Volatile var kabodLoader: KabodAssetLoader? = null

    /**
     * Disk cache for parsed feeds. Wired up by [com.lofipod.app.LofiPodApp].
     * When set, [hydrateFromDisk] populates [cache] from disk on first call,
     * and [fetchOne] persists each successful fetch back to disk. Lets the
     * Catalog render instantly on cold start with stale-but-valid data
     * before the network refresh completes.
     */
    @Volatile var diskCache: FeedDiskCache? = null

    @Volatile private var cache: Map<String, Podcast> = emptyMap()

    /**
     * Resolved on first hydration attempt — even if hydration finds no disk
     * cache (clean install), this completes so callers can stop awaiting.
     * Subsequent calls are no-ops.
     */
    private val hydrationDone = CompletableDeferred<Unit>()
    @Volatile private var hydrationStarted = false

    /**
     * Wait for the disk cache to be loaded into [cache]. Idempotent — first
     * call kicks off the read; subsequent calls await the same Deferred.
     * Safe to call before [diskCache] is set; in that case completes
     * immediately without populating. Caller blocks on this before calling
     * [cached] to ensure cold-start reads see disk-cached entries.
     */
    suspend fun hydrateFromDisk() {
        if (hydrationDone.isCompleted) return
        if (!hydrationStarted) {
            hydrationStarted = true
            val dc = diskCache
            if (dc == null) {
                hydrationDone.complete(Unit)
                return
            }
            withContext(Dispatchers.IO) {
                StartupTimings.phase("feed_disk_cache_hydrate") {
                    val loaded = dc.readAll()
                    if (loaded.isNotEmpty()) {
                        cache = loaded
                    }
                }
            }
            hydrationDone.complete(Unit)
            return
        }
        hydrationDone.await()
    }

    suspend fun loadSourcesFile(uri: Uri): List<SourceEntry> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            SourcesFileParser.parse(ins.bufferedReader().readText())
        } ?: emptyList()
    }

    /**
     * Fetch all feeds in parallel. Each feed has a 60s hard timeout; feeds that fail or
     * time out are logged and skipped. We keep going so one bad feed doesn't sink the list.
     *
     * [onProgress] is invoked from the IO thread each time a feed transitions state. The
     * caller is responsible for marshalling to whatever thread its state holder requires —
     * [kotlinx.coroutines.flow.MutableStateFlow.update] is fine as-is.
     */
    suspend fun fetchFeeds(
        sources: List<SourceEntry>,
        onProgress: (SourceEntry, FeedStatus, errorMessage: String?) -> Unit = { _, _, _ -> }
    ): List<Podcast> = coroutineScope {
        // Cap concurrent in-flight fetches. Without this, 16+ simultaneous
        // TLS handshakes + parses can starve a flaky network or slow CPU
        // (Pixel 7 reports vs Pixel 8). 8 keeps most of the parallel speedup
        // while reducing contention for users on weaker connections.
        val sem = Semaphore(MAX_CONCURRENT_FETCHES)
        val results = sources.map { src ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    try {
                        val pod = withTimeoutOrNull(60_000) { fetchOne(src) }
                        if (pod != null) {
                            onProgress(src, FeedStatus.OK, null)
                        } else {
                            onProgress(src, FeedStatus.TIMEOUT, "Timed out after 60s")
                        }
                        pod
                    } catch (e: Exception) {
                        System.err.println("Feed failed: ${src.feedUrl} -> ${e.message}")
                        onProgress(src, FeedStatus.FAILED, e.message ?: e.javaClass.simpleName)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        // Merge fresh fetches over the existing cache (which may have
        // disk-hydrated entries we didn't refresh). Anything missing from
        // results stays as-is so the catalog doesn't lose feeds on
        // network failure.
        cache = cache + results.associateBy { it.feedUrl }
        results
    }

    enum class FeedStatus { LOADING, OK, FAILED, TIMEOUT }

    /**
     * Runs the blocking HTTP + parse on Dispatchers.IO via [runInterruptible], so coroutine
     * cancellation (e.g. from [withTimeoutOrNull]) actually interrupts the worker thread —
     * which OkHttp's [okhttp3.Call.execute] honors and aborts the call.
     *
     * Synthetic `kabod://` URLs short-circuit to the bundled-asset loader (no HTTP).
     */
    suspend fun fetchOne(src: SourceEntry): Podcast {
        val tStart = System.nanoTime()
        val phaseName = feedPhaseName(src.feedUrl)
        val loader = kabodLoader
        if (loader != null && loader.isKabodFeed(src.feedUrl)) {
            val pod = loader.loadIntoCache(src.feedUrl)
                ?: error("Kabod pack not found for ${src.feedUrl}")
            val out = if (src.displayName != null) pod.copy(title = src.displayName) else pod
            StartupTimings.record(phaseName, tStart)
            return out
        }
        val parsed = runInterruptible(Dispatchers.IO) {
            val req = Request.Builder()
                .url(src.feedUrl)
                .build()    // UA is set globally by the http interceptor
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} for ${src.feedUrl}")
                val body = resp.body ?: error("Empty body")
                body.byteStream().use { RssParser.parse(src.feedUrl, it) }
            }
        }
        // Override title if user provided a display name
        val titled = if (src.displayName != null) parsed.copy(title = src.displayName) else parsed
        // Merge with disk-cached version to preserve historical episodes
        // that may have dropped off the upstream feed (CCM Sunday services
        // appear capped at the most recent ~200 — anything older drops out
        // each refresh). Build guid → Episode map; fresh entries win on
        // collision so updates land, but older episodes from previous
        // fetches stay around indefinitely.
        val merged = mergeWithCachedHistory(titled)
        // Persist to disk cache (best-effort; failures don't fail the fetch).
        diskCache?.let { dc ->
            try {
                withContext(Dispatchers.IO) { dc.write(merged) }
            } catch (e: Exception) {
                System.err.println("Feed disk-cache write failed for ${src.feedUrl}: ${e.message}")
            }
        }
        StartupTimings.record(phaseName, tStart)
        return merged
    }

    /**
     * Merge a freshly-parsed Podcast with whatever the in-memory cache or
     * disk cache holds for the same feedUrl. Fresh episodes win on guid
     * collision (so titles, durations, audio URLs update); older episodes
     * not in the fresh fetch are preserved.
     *
     * Why: some feeds (e.g. CCM Sunday Morning / Sunday Evening services)
     * cap their RSS output at the most-recent N items. A naive cache that
     * overwrites on each fetch loses anything older than the current
     * window. Merge-and-keep means once an episode has been seen, it
     * stays in the user's local catalog forever even if the upstream
     * drops it.
     */
    private fun mergeWithCachedHistory(fresh: Podcast): Podcast {
        val previous: Podcast? = cache[fresh.feedUrl] ?: diskCache?.read(fresh.feedUrl)
        if (previous == null || previous.episodes.isEmpty()) return fresh
        val byGuid = LinkedHashMap<String, com.lofipod.app.data.model.Episode>(
            previous.episodes.size + fresh.episodes.size
        )
        // Seed with previous so older episodes survive…
        for (ep in previous.episodes) byGuid[ep.guid] = ep
        // …then layer fresh on top so any updated metadata replaces.
        for (ep in fresh.episodes) byGuid[ep.guid] = ep
        // Sort newest-first by pubDate, falling back to insertion order
        // for episodes without a date (rare).
        val sorted = byGuid.values.sortedByDescending { it.pubDateMillis ?: 0L }
        return fresh.copy(episodes = sorted)
    }

    /** Short, host-derived label for per-feed timing entries. */
    private fun feedPhaseName(feedUrl: String): String {
        val host = try {
            URI(feedUrl).host?.removePrefix("www.")?.replace('.', '_')
        } catch (e: Exception) { null } ?: "unknown"
        return "feed_$host"
    }

    fun cached(feedUrl: String): Podcast? = cache[feedUrl]

    /** Snapshot of every currently-cached podcast. Used by EpisodeSearchScreen. */
    fun allCached(): List<Podcast> = cache.values.toList()

    companion object {
        /**
         * Standard browser-ish UA. Some podcast hosts (mod_security-fronted ones)
         * 406 anything that doesn't look like a browser. Shared with Coil so artwork
         * URLs from the same hosts also load.
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; LofiPod) AppleWebKit/537.36 (KHTML, like Gecko) Mobile"

        /**
         * Cap on simultaneous in-flight feed fetches. 8 is a compromise:
         * full parallelism (16+) was causing reproducible slowness on
         * weaker hardware (Pixel 7 reports), serial (1) leaves throughput
         * on the table. 8 keeps connection-pool reuse healthy without
         * saturating the network or CPU.
         */
        private const val MAX_CONCURRENT_FETCHES = 8
    }
}
