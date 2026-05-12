package com.lofipod.app.data

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lofipod.app.diagnostics.StartupTimings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Networking + DataSource plumbing for ExoPlayer playback.
 *
 * v0.6.9 reset: Media3's `DownloadManager`, `SimpleCache`, and
 * `StandaloneDatabaseProvider` are gone. Downloads are now app-owned (see
 * [LofiPodDownloader]); local files live as plain `.bin` files under
 * `filesDir/episode_audio/`. ExoPlayer reads them directly via
 * [DefaultDataSource]'s built-in `FileDataSource` for `file://` URIs, and
 * via [OkHttpDataSource] for `http://` / `https://` URIs.
 *
 * No more streaming-cache: a scrub-back during streaming will re-fetch the
 * relevant byte range from the network. Acceptable for podcasts (rare) and
 * a worthy trade for the simplicity of not coordinating two caching layers
 * with the downloader.
 */
class DownloadHolder(context: Context) {

    /**
     * Shared OkHttp client used for both player streaming and the new
     * downloader. One client = one connection pool, fewer sockets, fewer
     * resolver lookups for podcasts whose audio + manifest live on the
     * same CDN.
     */
    val httpClient: OkHttpClient = StartupTimings.phase("download_http_init") {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * DataSource.Factory used by the ExoPlayer media source. Auto-routes
     * by URI scheme: `file://` → [androidx.media3.datasource.FileDataSource],
     * `http(s)://` → [OkHttpDataSource]. The player ends up holding a
     * [androidx.media3.datasource.FileDataSource] when we point a MediaItem
     * at a downloaded local file, which avoids HTTP entirely for offline
     * playback.
     */
    val dataSourceFactory: DataSource.Factory by lazy {
        val httpFactory = OkHttpDataSource.Factory(httpClient).setUserAgent("LofiPod/0.6.9")
        DefaultDataSource.Factory(context, httpFactory)
    }
}
