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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    /**
     * Scope for the progress-poll loop. Separate from [cleanupScope] so a
     * stuck poll (shouldn't happen, but defensive) doesn't block cleanup.
     */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

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
                // Surface STATE_FAILED + finalException to the diagnostics
                // screen so the user can see "why didn't this download" at
                // a glance instead of just an error icon on the row.
                if (download.state == Download.STATE_FAILED) {
                    val reason = finalException?.let {
                        "${it.javaClass.simpleName}: ${it.message ?: "(no message)"}"
                    } ?: "STATE_FAILED (no exception attached)"
                    com.lofipod.app.diagnostics.AppDiagnostics
                        .recordDownloadFailure(download.request.id, reason)
                }
                // State just changed; if anything's now active, kick the
                // progress poll. Self-deduping if already running.
                ensureProgressPolling()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _byId.value = _byId.value.toMutableMap().apply {
                    remove(download.request.id)
                }
            }

            override fun onInitialized(downloadManager: DownloadManager) {
                refreshAll()
                ensureProgressPolling()
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
        // After a cold-start refresh, anything that was DOWNLOADING in the
        // index needs the poll loop running to ride its progress out to the
        // UI — otherwise we'd freeze at the index's last persisted bytes.
        ensureProgressPolling()
    }

    /**
     * Periodic poll that re-emits [byId] with a fresh snapshot of
     * [DownloadManager.currentDownloads] so the UI sees live progress.
     *
     * **Why this is needed.** Media3's [DownloadManager.Listener.onDownloadChanged]
     * fires only on STATE transitions (QUEUED → DOWNLOADING → COMPLETED /
     * FAILED). It does NOT fire during DOWNLOADING for byte / percentage
     * progress updates. Without this poll the UI's spinner would freeze at
     * the percent value captured the moment DOWNLOADING first fired
     * (typically 0%) for the entire duration of the download — exactly the
     * "infinitely in progress" symptom.
     *
     * The poll also acts as a safety net for state transitions in case the
     * listener's fan-out is delayed (Compose snapshot scheduling, or
     * Media3's internal handler thread being throttled). The same 500 ms
     * cadence picks up any state change within half a second.
     *
     * **Lifecycle.** Self-starts on [start], on [refreshAll], and on any
     * [DownloadManager.Listener.onDownloadChanged] callback. Self-stops
     * when no downloads are in DOWNLOADING / QUEUED / RESTARTING. Running
     * a second time is a no-op while the loop is alive.
     *
     * **Cost.** ~2 reads/sec of an in-memory `currentDownloads` list while
     * any download is active; idle when nothing's downloading. Map equality
     * uses reference equality on Download values (no equals override in
     * Media3), so any progress-field difference produces a structurally-
     * different map and triggers Compose recomposition end-to-end.
     */
    private fun ensureProgressPolling() {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            try {
                while (isActive) {
                    val snapshot = manager.currentDownloads
                    if (snapshot.isNotEmpty()) {
                        _byId.value = _byId.value.toMutableMap().apply {
                            for (d in snapshot) put(d.request.id, d)
                        }
                    }
                    val hasActive = snapshot.any {
                        it.state == Download.STATE_DOWNLOADING ||
                            it.state == Download.STATE_QUEUED ||
                            it.state == Download.STATE_RESTARTING
                    }
                    if (!hasActive) break
                    delay(POLL_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                // Defensive — a poll failure must not crash the app or take
                // out the manager. Log and let a future state change kick a
                // fresh loop via ensureProgressPolling().
                Log.e(TAG, "progress poll loop failed", t)
            }
        }
    }

    fun start(ep: Episode) {
        val request = DownloadRequest.Builder(ep.guid, Uri.parse(ep.audioUrl))
            .setMimeType(ep.audioMimeType ?: "audio/mpeg")
            .build()
        try {
            manager.addDownload(request)
            // Kick the poll immediately so the UI sees this new download's
            // progress without waiting for the listener's first DOWNLOADING
            // state transition (which fires before bytes start flowing).
            ensureProgressPolling()
        } catch (t: Throwable) {
            // Defensive — DownloadManager.addDownload can throw if the
            // index is in a bad state. We'd rather log and continue
            // playback than crash the play action.
            Log.e(TAG, "addDownload(${ep.guid}) failed", t)
            com.lofipod.app.diagnostics.AppDiagnostics
                .recordDownloadFailure(ep.guid, "addDownload threw: ${t.javaClass.simpleName} ${t.message ?: ""}")
        }
    }

    fun remove(episodeGuid: String) {
        try {
            manager.removeDownload(episodeGuid)
        } catch (t: Throwable) {
            Log.e(TAG, "removeDownload($episodeGuid) failed", t)
            com.lofipod.app.diagnostics.AppDiagnostics
                .recordDownloadFailure(episodeGuid, "removeDownload threw: ${t.javaClass.simpleName} ${t.message ?: ""}")
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

        /**
         * 500 ms cadence for the progress poll. Tradeoffs: at 250 ms the
         * UI's percent label looks fluid but burns ~4 reads/sec/active for
         * what's mostly imperceptible motion on a phone-sized progress
         * arc. At 1 s the percent feels laggy. 500 ms is the sweet spot —
         * humans read ~100 ms quanta as "instant," and 500 ms keeps the
         * arc moving without churning.
         */
        private const val POLL_INTERVAL_MS = 500L
    }
}
