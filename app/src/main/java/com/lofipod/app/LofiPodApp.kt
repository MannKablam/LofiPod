package com.lofipod.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.lofipod.app.data.BackupWorker
import com.lofipod.app.data.DownloadHolder
import com.lofipod.app.data.Downloads
import com.lofipod.app.data.PodcastRepository
import com.lofipod.app.data.Settings
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
    lateinit var downloads: DownloadHolder
        private set
    lateinit var downloadsApi: Downloads
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = PodcastRepository(this)
        db = AppDatabase.get(this)
        downloads = DownloadHolder(this)
        downloadsApi = Downloads(this, downloads.downloadManager)

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
    }

    companion object {
        @Volatile lateinit var instance: LofiPodApp
            private set
    }
}
