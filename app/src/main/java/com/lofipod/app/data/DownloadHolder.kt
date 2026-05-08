package com.lofipod.app.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import com.lofipod.app.diagnostics.StartupTimings
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One-time setup of Media3's download/cache infrastructure.
 *
 * - [cache] is a [SimpleCache] under app-private storage. Holds both fully downloaded
 *   episodes and any range-cached bytes from streaming playback.
 * - [downloadManager] tracks progress + persists state to [databaseProvider]'s SQLite db.
 * - [cacheDataSourceFactory] is what playback uses: hits the cache first, falls back to HTTP.
 *
 * Constructed once by [com.lofipod.app.LofiPodApp].
 */
class DownloadHolder(context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val httpDataSourceFactory: DataSource.Factory =
        OkHttpDataSource.Factory(httpClient).setUserAgent("LofiPod/0.1")

    private val downloadDirectory: File = File(context.filesDir, "downloads").apply { mkdirs() }

    val databaseProvider: StandaloneDatabaseProvider = StartupTimings.phase("download_db_provider") {
        StandaloneDatabaseProvider(context)
    }

    val cache: SimpleCache = StartupTimings.phase("simple_cache_init") {
        // Slow on devices with many downloaded files; the constructor
        // synchronously scans the entire download directory + cross-
        // references with the cache db. Surfaced in the Startup section
        // of the diagnostics screen so a slow user can see "is this what
        // ate my cold-start time?"
        SimpleCache(
            downloadDirectory,
            NoOpCacheEvictor(),     // never evict — user manages downloads explicitly
            databaseProvider
        )
    }

    val downloadManager: DownloadManager = StartupTimings.phase("download_manager_init") {
        DownloadManager(
            context,
            databaseProvider,
            cache,
            httpDataSourceFactory,
            Executors.newFixedThreadPool(2)
        ).apply {
            // Allow up to 2 concurrent downloads; podcast episodes are typically <100 MB each.
            maxParallelDownloads = 2
            // CRITICAL: when a DownloadManager is used directly (without a
            // DownloadService driving it), it is constructed in a PAUSED
            // state — downloadsPaused = true by default. Without this flip,
            // every addDownload() request lands in STATE_QUEUED and stays
            // there forever, which is exactly the symptom we've been chasing
            // since v0.5.6 (when we stopped routing through DownloadService
            // to dodge the Android 12+/14+/15 ForegroundServiceStartNotAllowed
            // crash). resumeDownloads() flips downloadsPaused to false and
            // syncs the task queue once; subsequent addDownload() calls then
            // auto-start as soon as Requirements.NETWORK is satisfied. Single
            // call at construction is sufficient — the flag persists. See
            // androidx/media DownloadManager.java javadoc: "Normally a
            // download manager should be accessed via a DownloadService.
            // When a download manager is used directly instead, downloads
            // will be initially paused and so must be resumed by calling
            // resumeDownloads()."
            resumeDownloads()
        }
    }

    /** Cache-first DataSource.Factory for ExoPlayer. */
    val cacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
