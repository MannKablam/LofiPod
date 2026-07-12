package com.lofipod.app.data

import android.content.Context
import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.data.db.EpisodeKabodEntity
import com.lofipod.app.data.db.KabodPackEntity
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.parser.KabodPack
import com.lofipod.app.parser.KabodPackParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Loads bundled Kabod Packs from the APK assets and ingests them into the
 * database. Idempotent — already-installed packs (matched by [KabodPackEntity.packId])
 * are skipped on re-run.
 *
 * Bundled packs live under `assets/kabod/` (one `.kabod` per pack). File name
 * convention: `<packId>.kabod` (informational; the pack's own `<kabod:packId>`
 * tag is authoritative).
 *
 * Also serves as a [Podcast]-cache hydrator: [loadIntoCache] returns the parsed
 * Podcast (without DB side effects) so [PodcastRepository.fetchOne] can route
 * `kabod://` synthetic feed URLs through this loader rather than HTTP.
 */
class KabodAssetLoader(
    private val context: Context,
    private val db: AppDatabase,
) {

    /** Cache of parsed packs keyed by `kabod://<packId>` so repeated cache hydrations are free. */
    private val parsedCache = mutableMapOf<String, KabodPack>()
    private val mutex = Mutex()

    /**
     * Scan `assets/kabod/` for `.kabod` files. For each: parse, install if not
     * already present in the DB. Safe to call on every app launch.
     *
     * Returns the list of [packId]s installed *or* already present (i.e. all
     * packs the app knows about after this call).
     */
    suspend fun installBundled(): List<String> = withContext(Dispatchers.IO) {
        val installed = mutableListOf<String>()
        val files = try {
            context.assets.list("kabod") ?: emptyArray()
        } catch (_: IOException) {
            emptyArray()
        }
        for (name in files) {
            if (!name.endsWith(".kabod")) continue
            val packPath = "kabod/$name"
            try {
                val parsed = parseAsset(packPath) ?: continue
                installed.add(parsed.packMeta.packId)
                upsertPack(parsed)
            } catch (e: Exception) {
                System.err.println("Kabod pack failed: $packPath -> ${e.message}")
            }
        }
        removeRetiredPacks()
        installed
    }

    /**
     * Purge DB rows for bundled packs that were retired from the app.
     * [installBundled] only ever adds what it finds under `assets/kabod/`,
     * so without this a pack whose asset was deleted in an update would
     * linger in `kabod_pack` / `episode_kabod` / `podcast_source` forever
     * and keep showing in the catalog with a feed that can no longer load.
     *
     * An explicit retired-ID list (rather than "delete anything without a
     * matching asset") so user-IMPORTED packs — which live in the same
     * tables but have no bundled asset by design — are never touched.
     * Per-episode user data (episode_state, notes, checkpoints) is
     * deliberately left in place; it's keyed by guid and harmless.
     */
    private suspend fun removeRetiredPacks() = withContext(Dispatchers.IO) {
        val packDao = db.kabodPackDao()
        for (packId in RETIRED_PACK_IDS) {
            if (packDao.get(packId) == null) continue
            packDao.remove(packId)
            db.episodeKabodDao().removeForPack(packId)
            db.podcastSourceDao().remove("kabod://$packId")
            com.lofipod.app.diagnostics.AppDiagnostics.recordOther(
                identifier = "kabod_pack_retired",
                detail = "Removed retired pack $packId from DB.",
            )
        }
    }

    /**
     * Parse and cache the bundled pack with the given feedUrl (e.g. `kabod://desiringgod-piper-romans`).
     * Returns the [Podcast] for in-memory cache use. Returns null if no bundled pack matches.
     *
     * The `feedUrl` is the synthetic `kabod://<packId>` URL that [PodcastRepository.fetchOne]
     * sees in [Sources.PODCASTS]. We map it back to the asset filename `<packId>.kabod`.
     */
    suspend fun loadIntoCache(feedUrl: String): Podcast? = mutex.withLock {
        parsedCache[feedUrl]?.let { return@withLock it.podcast }
        val packId = feedUrl.removePrefix("kabod://")
        val parsed = parseAsset("kabod/$packId.kabod") ?: return@withLock null
        parsedCache[feedUrl] = parsed
        parsed.podcast
    }

    /** True if [feedUrl] is the synthetic kabod:// scheme. */
    fun isKabodFeed(feedUrl: String): Boolean = feedUrl.startsWith("kabod://")

    private suspend fun parseAsset(path: String): KabodPack? = withContext(Dispatchers.IO) {
        try {
            context.assets.open(path).use { input -> KabodPackParser.parse("kabod://" + path.removePrefix("kabod/").removeSuffix(".kabod"), input) }
        } catch (e: Exception) {
            System.err.println("Kabod parse failed: $path -> ${e.message}")
            null
        }
    }

    private suspend fun upsertPack(parsed: KabodPack) = withContext(Dispatchers.IO) {
        val packDao = db.kabodPackDao()
        val epDao = db.episodeKabodDao()
        val sourceDao = db.podcastSourceDao()
        val pack = parsed.packMeta
        val existing = packDao.get(pack.packId)
        if (existing == null) {
            packDao.upsert(
                KabodPackEntity(
                    packId = pack.packId,
                    feedUrl = "kabod://${pack.packId}",
                    title = pack.title,
                    speaker = pack.speaker,
                    bookOfBible = pack.bookOfBible,
                    sourceSite = pack.sourceSite,
                    archived = pack.archived,
                    installedAt = System.currentTimeMillis(),
                )
            )
        }
        // Always upsert episode_kabod rows — pack content can change between
        // app versions when we ship a content update.
        val rows = parsed.episodeMeta.map { (guid, meta) ->
            EpisodeKabodEntity(
                guid = guid,
                packId = pack.packId,
                partNumber = meta.partNumber,
                scripture = meta.scripture,
                scriptureBook = meta.scriptureBook,
                scriptureStartCh = meta.scriptureStartCh,
                scriptureStartV = meta.scriptureStartV,
                scriptureEndCh = meta.scriptureEndCh,
                scriptureEndV = meta.scriptureEndV,
                transcriptUrl = meta.transcriptUrl,
                transcriptSelector = meta.transcriptSelector,
                speaker = meta.speaker ?: pack.speaker,
            )
        }
        if (rows.isNotEmpty()) epDao.upsertAll(rows)

        // Mirror into podcast_source so the catalog UI surfaces it alongside RSS feeds.
        sourceDao.insertIfAbsent(
            listOf(
                com.lofipod.app.data.db.PodcastSourceEntity(
                    feedUrl = "kabod://${pack.packId}",
                    displayName = pack.title,
                    addedAtMillis = System.currentTimeMillis(),
                )
            )
        )
    }

    /**
     * Parse a user-supplied .kabod from a SAF-opened InputStream. Used by the
     * "Import pack…" action in [com.lofipod.app.ui.screens.CatalogScreen].
     */
    suspend fun importStream(input: java.io.InputStream): KabodPack? = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        val parsed = try {
            KabodPackParser.parse("kabod://import-pending", bytes.inputStream())
        } catch (e: Exception) {
            System.err.println("Kabod import failed: ${e.message}")
            return@withContext null
        }
        // Re-parse to bind the synthetic feedUrl now that we know the packId.
        val realFeedUrl = "kabod://${parsed.packMeta.packId}"
        val rebound = try {
            KabodPackParser.parse(realFeedUrl, bytes.inputStream())
        } catch (_: Exception) {
            return@withContext null
        }
        upsertPack(rebound)
        parsedCache[realFeedUrl] = rebound
        rebound
    }

    companion object {
        /**
         * Bundled packs removed from the app. 2026-07 Leviticus curation:
         * only Mark Chanski's series stays; the William Still, Cities
         * Church (Parnell) and Andy Davis packs were retired along with
         * their .kabod asset files and Sources.KABOD_PACKS entries.
         * (No glob in this comment on purpose — Kotlin block comments
         * nest, so a slash-star path pattern would swallow the rest of
         * the file.) IDs stay on this list forever so any install that
         * ever had them gets cleaned up on next launch.
         */
        val RETIRED_PACK_IDS = setOf(
            "monergism-still-leviticus",
            "citieschurch-parnell-leviticus",
            "twojourneys-davis-leviticus",
        )
    }
}
