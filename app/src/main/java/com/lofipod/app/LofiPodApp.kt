package com.lofipod.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.lofipod.app.data.BackupWorker
import com.lofipod.app.data.DownloadHolder
import com.lofipod.app.data.Downloads
import com.lofipod.app.data.KabodAssetLoader
import com.lofipod.app.data.PodcastRepository
import com.lofipod.app.data.Settings
import com.lofipod.app.data.TranscriptRepository
import com.lofipod.app.data.db.AppDatabase
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
     * Lazy because `SimpleCache`'s constructor synchronously scans the
     * download directory — for users with many downloaded episodes on
     * slow eMMC this can take seconds and visibly delays first-frame
     * render if done eagerly in [onCreate]. Deferred to first access;
     * a background coroutine in [onCreate] warms it up off-thread so the
     * typical first access (PlaybackService.onCreate or the first UI
     * collect of `byId`) finds it ready.
     */
    val downloads: DownloadHolder by lazy { DownloadHolder(this) }
    val downloadsApi: Downloads by lazy {
        Downloads(this, downloads.downloadManager, db.autoDownloadDao())
    }
    lateinit var kabodLoader: KabodAssetLoader
        private set
    lateinit var transcripts: TranscriptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = PodcastRepository(this)
        db = AppDatabase.get(this)
        kabodLoader = KabodAssetLoader(this, db)
        repo.kabodLoader = kabodLoader
        transcripts = TranscriptRepository(db)

        // Warm up the lazy `downloads` / `downloadsApi` properties on a
        // background thread. The expensive bits — SimpleCache's directory
        // scan + Downloads' refreshAll over the download index — run here
        // in parallel with MainActivity init, so the user sees the
        // Catalog screen render immediately instead of waiting on disk
        // I/O. A read of `byId.value` is enough to trigger the full chain
        // (downloadsApi → downloads.downloadManager → cache).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                downloadsApi.byId.value
            } catch (e: Exception) {
                System.err.println("Downloads warmup failed: ${e.message}")
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
                kabodLoader.installBundled()
            } catch (e: Exception) {
                System.err.println("Kabod bundle install failed: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile lateinit var instance: LofiPodApp
            private set
    }
}
