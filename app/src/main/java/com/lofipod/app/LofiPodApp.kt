package com.lofipod.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.lofipod.app.audio.AudioChainTelemetry
import com.lofipod.app.bible.ScriptureIndexer
import com.lofipod.app.data.BackupWorker
import com.lofipod.app.data.DownloadHolder
import com.lofipod.app.data.LofiPodDownloader
import com.lofipod.app.data.FeedDiskCache
import com.lofipod.app.data.KabodAssetLoader
import com.lofipod.app.data.PodcastRepository
import com.lofipod.app.data.Settings
import com.lofipod.app.data.TranscriptRepository
import com.lofipod.app.data.db.AppDatabase
import com.lofipod.app.diagnostics.StartupTimings
import com.lofipod.app.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class LofiPodApp : Application() {
    lateinit var repo: PodcastRepository
        private set
    lateinit var db: AppDatabase
        private set
    /**
     * Networking + DataSource plumbing for the player. Lightweight after
     * the v0.6.9 reset (no SimpleCache directory scan, no DownloadManager
     * boot). The shared OkHttp client lives here so the new
     * [LofiPodDownloader] can reuse the connection pool.
     */
    val downloads: DownloadHolder by lazy { DownloadHolder(this) }

    /**
     * App-owned downloader (v0.6.9 rewrite). Replaced Media3's
     * `DownloadManager` + `DownloadService` + `SimpleCache` after four
     * versions of trying to get them to behave. UI collects [byId] for
     * per-episode state; player calls `completedFile(guid)` to prefer
     * local files at MediaItem-construction time.
     */
    val downloadsApi: LofiPodDownloader by lazy {
        LofiPodDownloader(
            context = this,
            httpClient = downloads.httpClient,
            downloadDao = db.lofiDownloadDao(),
            autoDownloadDao = db.autoDownloadDao(),
        )
    }

    /**
     * Audio-file-size prober for episodes whose RSS feed reports
     * `length="0"` (Megaphone, some Castos shows). Uses HTTP HEAD with
     * Range fallback to learn the real size, caches results on disk so
     * subsequent launches render without re-probing. See
     * [com.lofipod.app.data.EpisodeSizeProber] for the strategy.
     */
    val episodeSizes: com.lofipod.app.data.EpisodeSizeProber by lazy {
        com.lofipod.app.data.EpisodeSizeProber(
            context = this,
            httpClient = downloads.httpClient,
        )
    }

    /**
     * Read-ahead episode analyzer: offline MediaCodec decode of an
     * episode's audio into pause marks + a waveform envelope, cached in
     * Room for the player's scrubber to draw. Scans run one at a time
     * on a dedicated lowest-priority thread, so live playback never
     * competes with them. The player screen calls `ensureAnalyzed(...)`
     * for the displayed episode and observes `analysisFor(guid)`; no
     * scan means the flow stays null and the UI draws no marks.
     */
    val episodeAnalysis: com.lofipod.app.data.EpisodeAnalysisRepository by lazy {
        com.lofipod.app.data.EpisodeAnalysisRepository(
            context = this,
            dao = db.episodeAnalysisDao(),
            downloader = downloadsApi,
        )
    }

    /**
     * Process-lifetime player controller. MainActivity borrows this
     * instance rather than constructing its own because everything the
     * controller runs in its coroutine scope — the STATE_ENDED autoplay
     * advance, the confirmation countdown, the sleep timer — has to keep
     * working after the activity is destroyed while [com.lofipod.app.player.PlaybackService]
     * plays on; swipe-from-recents mid-episode is the everyday shape of
     * that. An activity-owned controller took all of it down in
     * onDestroy, so episodes just stopped at their end whenever the app
     * wasn't on screen. Lazy: cold start pays nothing until the first
     * activity asks.
     */
    val playerController: com.lofipod.app.player.PlayerController by lazy {
        com.lofipod.app.player.PlayerController(this)
    }
    lateinit var kabodLoader: KabodAssetLoader
        private set
    lateinit var transcripts: TranscriptRepository
        private set
    lateinit var scriptureIndexer: ScriptureIndexer
        private set

    override fun onCreate() {
        val tOnCreate = System.nanoTime()
        super.onCreate()
        instance = this
        // Wire the API 31+ PerformanceHintManager bridge before anything in
        // the audio path runs. The audio thread reaches it via
        // AudioChainTelemetry on the first buffer it processes; installing
        // here means that first buffer already has a target+actual report
        // path. No-op on API <31 — the bridge stores null internally and
        // every call short-circuits cheaply.
        StartupTimings.phase("perf_hint_install") {
            AudioChainTelemetry.installPerformanceHintBridge(this)
        }
        repo = StartupTimings.phase("repo_init") { PodcastRepository(this) }
        db = StartupTimings.phase("db_get") { AppDatabase.get(this) }
        kabodLoader = StartupTimings.phase("kabod_loader_init") {
            KabodAssetLoader(this, db)
        }
        repo.kabodLoader = kabodLoader
        // Disk cache for parsed feeds — wires into the repo so cold-start
        // catalog reads hydrate from disk before re-fetching. Visible as
        // `feed_disk_cache_hydrate` in the Startup section of the
        // diagnostics screen, plus per-feed timing entries (one per
        // refresh) so the slow-feed problem can be triaged on device.
        repo.diskCache = StartupTimings.phase("feed_disk_cache_init") {
            FeedDiskCache(this)
        }
        transcripts = StartupTimings.phase("transcripts_init") {
            TranscriptRepository(db)
        }
        // Bible-scripture indexing: runs after every feed fetch so
        // RSS-tagged refs land in episode_scripture next to Kabod-imported
        // ones. Backfill from kabod packs runs in the background after
        // initialization (idempotent — REPLACE conflict policy).
        scriptureIndexer = ScriptureIndexer(
            scriptureDao = db.episodeScriptureDao(),
            kabodDao = db.episodeKabodDao(),
        )
        repo.afterFetchHook = { podcast -> scriptureIndexer.tagPodcast(podcast) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                StartupTimings.phase("scripture_kabod_backfill") {
                    scriptureIndexer.backfillFromKabod()
                }
            } catch (e: Exception) {
                System.err.println("Scripture kabod-backfill failed: ${e.message}")
            }
            // Tag any already-cached podcasts so the Bible index is
            // populated even if the user hasn't refreshed since
            // installing this version. Awaits disk hydration first; the
            // afterFetchHook will tag any subsequent network refreshes.
            try {
                StartupTimings.phase("scripture_warm_tag") {
                    repo.hydrateFromDisk()
                    for (pod in repo.allCached()) {
                        scriptureIndexer.tagPodcast(pod)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Scripture warm-tag failed: ${e.message}")
            }
        }

        // Warm up the lazy `downloads` / `downloadsApi` properties on a
        // background thread. After the v0.6.9 reset this is mostly a Room
        // hydrate of the lofi_download table — fast in steady state. Touch
        // both so the player and UI find them ready.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                StartupTimings.phase("downloads_warmup") {
                    downloadsApi.byId.value
                }
            } catch (e: Exception) {
                System.err.println("Downloads warmup failed: ${e.message}")
            }
        }

        // One-shot legacy cleanup: the old Media3 SimpleCache lived under
        // filesDir/downloads/ and the StandaloneDatabaseProvider's SQLite
        // db wraps the same directory. Both are obsolete after v0.6.9.
        // Wiping reclaims disk that's been holding stuck/partial cache
        // chunks. Idempotent — once gone, staying gone.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val legacyCache = java.io.File(filesDir, "downloads")
                if (legacyCache.exists()) {
                    legacyCache.deleteRecursively()
                }
                // Media3's StandaloneDatabaseProvider creates a SQLite db
                // named "exoplayer_internal.db" alongside the cache. Wipe.
                val legacyDb = java.io.File(filesDir, "exoplayer_internal.db")
                if (legacyDb.exists()) legacyDb.delete()
                java.io.File(filesDir, "exoplayer_internal.db-journal").let {
                    if (it.exists()) it.delete()
                }
            } catch (e: Exception) {
                System.err.println("Legacy download cleanup failed: ${e.message}")
            }
        }

        // Stale APK update cache cleanup. UpdateChecker writes downloaded
        // APKs to cacheDir/updates/lofipod-<versionCode>.apk and reuses
        // them across "Check now" presses, but never deletes older
        // versions. After several updates this can balloon to hundreds of
        // MB of stale APKs (each ~57 MB through the v0.6.x line) under
        // the user's "Storage → Data" reading. We delete every APK whose
        // versionCode != the currently-installed code, plus any leftover
        // .tmp files. The current versionCode's APK survives in case the
        // user is mid-install when this fires.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val updatesDir = java.io.File(cacheDir, "updates")
                if (!updatesDir.exists()) return@launch
                val installedCode = runCatching {
                    val pkg = packageManager.getPackageInfo(packageName, 0)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pkg.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION") pkg.versionCode
                    }
                }.getOrDefault(-1)
                val keep = "lofipod-$installedCode.apk"
                updatesDir.listFiles()?.forEach { f ->
                    if (f.name != keep) {
                        runCatching { f.delete() }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Stale APK cleanup failed: ${e.message}")
            }
        }

        // Coil's default OkHttp uses a generic UA that some podcast art hosts (e.g.
        // cloudfront fronts) reject. Force the same browser-ish UA we use for feed
        // fetches so artwork downloads succeed.
        val coilHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", PodcastRepository.BROWSER_UA)
                    .build()
                chain.proceed(req)
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(coilHttp)
                .crossfade(true)
                .build()
        )

        // Re-arm the workers from persisted Settings. WorkManager schedules
        // don't survive an uninstall, and even between launches re-issuing
        // the same schedule is a no-op under UPDATE policy — so it's safe
        // to do every time.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val settings = Settings(this@LofiPodApp)
            BackupWorker.schedule(this@LofiPodApp, settings.backupIntervalHours.first())
            UpdateWorker.schedule(this@LofiPodApp, settings.updateAutoCheckEnabled.first())
        }

        // Install bundled Kabod Packs (idempotent — packs already in the DB
        // are skipped). Runs on a background scope so app launch isn't blocked
        // even if the asset list grows.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                StartupTimings.phase("kabod_install_bundled") {
                    kabodLoader.installBundled()
                }
            } catch (e: Exception) {
                System.err.println("Kabod bundle install failed: ${e.message}")
            }
        }
        // Bookend marker for the synchronous portion of onCreate. Anything
        // after this still happens (Coil setup, background launches), but
        // the MAIN-THREAD blocking is bounded by this timestamp.
        StartupTimings.record("application_oncreate", tOnCreate)
    }

    companion object {
        @Volatile lateinit var instance: LofiPodApp
            private set
    }
}
