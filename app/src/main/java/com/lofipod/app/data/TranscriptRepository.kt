package com.lofipod.app.data

import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeTranscriptEntity
import com.lofipod.app.parser.TranscriptHtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Cache-first transcript loader. Reads from the [EpisodeTranscriptEntity] cache
 * if a row exists; otherwise fetches the source URL with the same browser-ish
 * UA the rest of the app uses, parses with [TranscriptHtmlParser], persists,
 * and returns.
 *
 * jsoup runs purely on the fetched HTML string — no WebView, no embedded
 * browser. The app's no-WebView invariant stands.
 */
class TranscriptRepository(
    private val db: AppDatabase,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", PodcastRepository.BROWSER_UA)
                .build()
            chain.proceed(req)
        }
        .build()

    suspend fun loadFor(
        guid: String,
        sourceUrl: String,
        selectorHint: String? = null,
        forceRefresh: Boolean = false,
    ): TranscriptResult? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = db.episodeTranscriptDao().get(guid)
            if (cached != null && cached.sourceUrl == sourceUrl) {
                return@withContext TranscriptResult(
                    paragraphs = decodeParagraphs(cached.paragraphsJson),
                    sourceUrl = cached.sourceUrl,
                    fetchedAt = cached.fetchedAt,
                    fromCache = true,
                )
            }
        }

        val html = runInterruptible(Dispatchers.IO) {
            val req = Request.Builder().url(sourceUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty response body")
            }
        }
        val paragraphs = TranscriptHtmlParser.parse(sourceUrl, html, selectorHint)
        if (paragraphs.isEmpty()) return@withContext null

        val now = System.currentTimeMillis()
        db.episodeTranscriptDao().upsert(
            EpisodeTranscriptEntity(
                guid = guid,
                sourceUrl = sourceUrl,
                paragraphsJson = encodeParagraphs(paragraphs),
                fetchedAt = now,
            )
        )
        TranscriptResult(
            paragraphs = paragraphs,
            sourceUrl = sourceUrl,
            fetchedAt = now,
            fromCache = false,
        )
    }

    private fun encodeParagraphs(paragraphs: List<String>): String {
        val arr = JSONArray()
        paragraphs.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeParagraphs(json: String): List<String> {
        val arr = JSONArray(json)
        return List(arr.length()) { arr.getString(it) }
    }
}

data class TranscriptResult(
    val paragraphs: List<String>,
    val sourceUrl: String,
    val fetchedAt: Long,
    val fromCache: Boolean,
)
