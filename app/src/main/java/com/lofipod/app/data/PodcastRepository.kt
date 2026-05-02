package com.lofipod.app.data

import android.content.Context
import android.net.Uri
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.parser.RssParser
import com.lofipod.app.parser.SourceEntry
import com.lofipod.app.parser.SourcesFileParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the user's sources file via SAF, parses it, then fetches each feed.
 * Keeps a tiny in-memory cache so we don't re-hit the network on every screen change.
 */
class PodcastRepository(
    private val context: Context
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var cache: Map<String, Podcast> = emptyMap()

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
        val results = sources.map { src ->
            async(Dispatchers.IO) {
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
        }.awaitAll().filterNotNull()
        cache = results.associateBy { it.feedUrl }
        results
    }

    enum class FeedStatus { LOADING, OK, FAILED, TIMEOUT }

    suspend fun fetchOne(src: SourceEntry): Podcast = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(src.feedUrl)
            .header("User-Agent", "LofiPod/0.1")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for ${src.feedUrl}")
            val body = resp.body ?: error("Empty body")
            val parsed = body.byteStream().use { RssParser.parse(src.feedUrl, it) }
            // Override title if user provided a display name
            if (src.displayName != null) parsed.copy(title = src.displayName) else parsed
        }
    }

    fun cached(feedUrl: String): Podcast? = cache[feedUrl]
}
