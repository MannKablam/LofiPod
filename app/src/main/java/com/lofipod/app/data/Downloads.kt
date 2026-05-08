package com.lofipod.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.lofipod.app.data.db.AutoDownloadDao
import com.lofipod.app.data.model.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin wrapper around Media3's [DownloadManager] that exposes a [StateFlow] of
 * downloads keyed by episode GUID. UI can collect this to render per-episode state.
 *
 * **Why we bypass [androidx.media3.exoplayer.offline.DownloadService.sendAddDownload].**
 * Pre-v0.5.6 we routed adds + removes through `DownloadService.sendAddDownload`
 * / `sendRemoveDownload`. On Android 12+ those calls trigger a foreground-
 * service start, which can throw `ForegroundServiceStartNotAllowedException`
 * from background-restricted states; on Android 15+ the `dataSync`
 * foreground-service-type has a hard daily timeout (~6 h cumulative) that
 * causes the service to be killed by the system mid-download, leaving
 * downloads silently stuck. Both symptoms match the user's "auto-download
 * hangs / generally doesn't work" report. Calling
 * [DownloadManager.addDownload] / [DownloadManager.removeDownload] directly
 * skips the service start and runs the work on the manager's own executor —
 * which is alive as long as the process is alive. The [PlaybackService]
 * (mediaPlayback foreground type) keeps the process alive during active
 * playback, which is precisely when auto-download is most likely to fire,
 * so this trades the (unused) download foreground notification for
 * reliability. Reference: androidx/media#2614, #1239, #831.
 */
class Downloads(
    private val context: Context,
    private val manager: DownloadManager,
    private val autoDownloadDao: AutoDownloadDao,
) {

    /**
     * Off-thread scope for fire-and-forget DB cleanup that pairs with
     * download operations. Uses [SupervisorJob] so a single failed cleanup
     * (e.g. DB locked transient) doesn't cancel future cleanups.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private val _byId = MutableStateFlow<Map<String, Download>>(emptyMap())
    val byId: StateFlow<Map<String, Download>> = _byId.asStateFlow()

    init {
        // Seed with whatever Media3 already knows about (e.g. after process
        // restart) on a background thread. The cursor walk over the download
        // index is small but synchronous; running it inline blocks whichever
        // thread constructed Downloads, which on a cold start was the main
        // thread (LofiPodApp.onCreate). Deferring it lets [byId] start empty
        // and fill in a tick later — UI collectors see an empty map
        // initially, the same as if no downloads existed yet, and update
        // when refreshAll lands.
        cleanupScope.launch { refreshAll() }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                _byId.value = _byId.value.toMutableMap().apply {
                    put(download.request.id, download)
                }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _byId.value = _byId.value.toMutableMap().apply {
                    remove(download.request.id)
                }
            }

            override fun onInitialized(downloadManager: DownloadManager) {
                refreshAll()
            }
        })
    }

    private fun refreshAll() {
        val cursor = manager.downloadIndex.getDownloads()
        val map = mutableMapOf<String, Download>()
        cursor.use {
            while (it.moveToNext()) {
                val d = it.download
                map[d.request.id] = d
            }
        }
        _byId.value = map
    }

    fun start(ep: Episode) {
        val request = DownloadRequest.Builder(ep.guid, Uri.parse(ep.audioUrl))
            .setMimeType(ep.audioMimeType ?: "audio/mpeg")
            .build()
        try {
            manager.addDownload(request)
        } catch (t: Throwable) {
            // Defensive — DownloadManager.addDownload can throw if the
            // index is in a bad state. We'd rather log and continue
            // playback than crash the play action.
            Log.e(TAG, "addDownload(${ep.guid}) failed", t)
        }
    }

    fun remove(episodeGuid: String) {
        try {
            manager.removeDownload(episodeGuid)
        } catch (t: Throwable) {
            Log.e(TAG, "removeDownload($episodeGuid) failed", t)
        }
        // Always cleanup any auto_download row so the table doesn't accumulate
        // stale entries — irrespective of whether this removal was a manual
        // user action, an auto-archive sweep, or the post-finish auto-download
        // expiration. Cheap (single-row delete) and runs off-thread so it
        // doesn't compete with the manager's removal callback.
        cleanupScope.launch { autoDownloadDao.delete(episodeGuid) }
    }

    companion object {
        private const val TAG = "LofiPodDownloads"
    }
}
