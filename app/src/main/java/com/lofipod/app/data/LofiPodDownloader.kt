package com.lofipod.app.data

import android.content.Context
import com.lofipod.app.data.db.AutoDownloadDao
import com.lofipod.app.data.db.LofiDownloadDao
import com.lofipod.app.data.db.LofiDownloadEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.diagnostics.AppDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * App-owned podcast downloader. Replaces the entire Media3 offline stack
 * (`DownloadManager`, `DownloadService`, `SimpleCache`,
 * `StandaloneDatabaseProvider`) with a plain OkHttp + coroutines pipeline
 * writing flat MP3 files into [filesDir]/episode_audio/.
 *
 * **Why the rewrite.** Media3's offline framework gave us four straight
 * versions of bugs (resume-paused-by-default, listener that doesn't fire on
 * progress, cache-coordination races between the streaming path and the
 * downloader, and assorted "downloads silently stuck" reports). The user
 * called it: ditch Media3's downloader and use OkHttp directly. Media3 still
 * powers the player itself (ExoPlayer, MediaSession, AudioProcessor chain) —
 * we just swap out the offline-storage piece for something we fully own.
 *
 * **Architecture.**
 *   - One [LofiDownloadEntity] row per episode-download in Room. Persists
 *     across app restarts; the in-memory [byId] StateFlow rehydrates from it
 *     on first access.
 *   - One file per download at `filesDir/episode_audio/<sha256(guid)>.bin`.
 *     SHA-256-of-guid filename so any GUID (including ones with `/`, `:`,
 *     `?`, etc.) yields a filesystem-safe name. Truncated to 32 hex chars
 *     (128 bits) — collision-free for any realistic library size.
 *   - HTTP via OkHttp with the same client config as the rest of the app.
 *     Range-resume on retry: a partial download writes its byte count to the
 *     row; on resume the request adds `Range: bytes=N-` and seeks the
 *     RandomAccessFile to position N.
 *   - Concurrency cap via [Semaphore] (2 simultaneous downloads). Beyond
 *     that, jobs block on the semaphore in QUEUED state.
 *   - Each download runs as a coroutine on [downloadScope] (Dispatchers.IO).
 *     Cancel = coroutine cancel. Remove = cancel + delete file + delete row.
 *
 * **State flow.** [byId] is a `MutableStateFlow<Map<String, LofiDownload>>`.
 * Updated:
 *   1. On [start]: insert a QUEUED entry, kick the worker.
 *   2. Inside the worker, every [PROGRESS_TICK_MS] of byte progress: emit
 *      DOWNLOADING with new bytesDownloaded/contentLength.
 *   3. On completion: emit COMPLETED.
 *   4. On failure: emit FAILED with the exception's message.
 *   5. On [remove]: drop the key from the map.
 *
 * **What's gone vs Media3.**
 *   - No `Requirements.NETWORK` indirection — we just check the OkHttp call
 *     fails fast on offline. Reasonable.
 *   - No `DownloadService` / foreground notification for the download
 *     itself. The PlaybackService keeps the process alive while the user is
 *     playing audio (mediaPlayback FGS), which is the high-value time
 *     anyway. Background-while-screen-off downloads aren't a feature we
 *     need.
 *   - No Media3 `SimpleCache` — local files live as plain `.bin` files.
 *     ExoPlayer reads them directly via `FileDataSource` when given a
 *     `file://` URI.
 */
class LofiPodDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val downloadDao: LofiDownloadDao,
    private val autoDownloadDao: AutoDownloadDao,
) {

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parallelism = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /** Per-guid in-flight job, so [remove] can cancel the right coroutine. */
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeJobsLock = Any()

    private val _byId = MutableStateFlow<Map<String, LofiDownload>>(emptyMap())
    val byId: StateFlow<Map<String, LofiDownload>> = _byId.asStateFlow()

    /**
     * Downloads directory under app-private files dir. Created lazily on
     * first access. Survives uninstalls of itself (gone with app data) but
     * NOT a system-level "Clear app storage" (intentional — that's the user
     * asking for a fresh start).
     */
    private val downloadsDir: File by lazy {
        File(context.filesDir, DOWNLOAD_DIR_NAME).apply { mkdirs() }
    }

    init {
        // Hydrate from Room synchronously enough to seed the StateFlow before
        // the first UI collect lands. runBlocking on IO is acceptable here —
        // construction happens once at app startup, on a warmup coroutine
        // (LofiPodApp), so we're not stalling the main thread.
        cleanupScope.launch { hydrate() }
    }

    private suspend fun hydrate() {
        val rows = downloadDao.getAll()
        val map = mutableMapOf<String, LofiDownload>()
        var revivedCount = 0
        for (row in rows) {
            // If state was DOWNLOADING / QUEUED at app death, it's effectively
            // paused — Convert to FAILED with a "interrupted" hint so the UI
            // surfaces a retry affordance rather than a frozen spinner. The
            // user retries from the row UI; that fires start() again and
            // resume kicks via Range header from the saved bytesDownloaded.
            val effectiveState = when (row.state) {
                LofiDownload.State.DOWNLOADING.ordinal,
                LofiDownload.State.QUEUED.ordinal -> {
                    revivedCount++
                    LofiDownload.State.FAILED
                }
                LofiDownload.State.COMPLETED.ordinal -> LofiDownload.State.COMPLETED
                LofiDownload.State.FAILED.ordinal -> LofiDownload.State.FAILED
                else -> LofiDownload.State.FAILED
            }
            map[row.guid] = LofiDownload(
                guid = row.guid,
                state = effectiveState,
                bytesDownloaded = row.bytesDownloaded,
                contentLength = row.contentLength,
                filePath = row.filePath,
                errorMessage = if (effectiveState == LofiDownload.State.FAILED &&
                    row.state != LofiDownload.State.FAILED.ordinal
                ) {
                    "Interrupted on app exit — tap to resume."
                } else row.errorMessage,
            )
        }
        _byId.value = map
        if (revivedCount > 0) {
            // One-shot DB update so the next hydrate matches the StateFlow.
            for ((guid, dl) in map) {
                if (dl.state == LofiDownload.State.FAILED && dl.errorMessage?.startsWith("Interrupted") == true) {
                    downloadDao.updateProgress(
                        guid = guid,
                        bytes = dl.bytesDownloaded,
                        total = dl.contentLength,
                        state = LofiDownload.State.FAILED.ordinal,
                        error = dl.errorMessage,
                        now = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /**
     * Start (or resume) downloading [ep]'s audio. Idempotent against an
     * already-in-flight download for the same GUID. If a previous attempt
     * left a partial file on disk + bytes count in the row, the new attempt
     * resumes from that offset via HTTP Range.
     *
     * Safe to call from any thread. Returns immediately; the actual fetch
     * runs on [downloadScope].
     */
    fun start(ep: Episode) {
        synchronized(activeJobsLock) {
            if (activeJobs[ep.guid]?.isActive == true) return
        }
        // Snapshot a QUEUED entry into _byId immediately so the UI flips
        // state on the same tick. The worker will move it to DOWNLOADING
        // once the semaphore permit is acquired.
        val filePath = fileFor(ep.guid).absolutePath
        val now = System.currentTimeMillis()
        val existing = _byId.value[ep.guid]
        val resumedBytes = if (existing?.state == LofiDownload.State.FAILED) existing.bytesDownloaded else 0L
        val resumedTotal = if (existing?.state == LofiDownload.State.FAILED) existing.contentLength else -1L

        _byId.value = _byId.value.toMutableMap().apply {
            put(
                ep.guid,
                LofiDownload(
                    guid = ep.guid,
                    state = LofiDownload.State.QUEUED,
                    bytesDownloaded = resumedBytes,
                    contentLength = resumedTotal,
                    filePath = filePath,
                    errorMessage = null,
                ),
            )
        }
        val job = downloadScope.launch {
            // Persist the QUEUED row before the network attempt — survives
            // app death between start() and the first byte landing.
            downloadDao.upsert(
                LofiDownloadEntity(
                    guid = ep.guid,
                    audioUrl = ep.audioUrl,
                    mimeType = ep.audioMimeType,
                    filePath = filePath,
                    state = LofiDownload.State.QUEUED.ordinal,
                    contentLength = resumedTotal,
                    bytesDownloaded = resumedBytes,
                    errorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            try {
                parallelism.withPermit {
                    runDownload(ep, filePath)
                }
            } catch (t: Throwable) {
                // SupervisorJob cancellation cascades as CancellationException —
                // don't mark the row failed if the user just cancelled.
                if (t is kotlinx.coroutines.CancellationException) return@launch
                markFailed(ep.guid, "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}")
            } finally {
                synchronized(activeJobsLock) {
                    activeJobs.remove(ep.guid)
                }
            }
        }
        synchronized(activeJobsLock) {
            activeJobs[ep.guid] = job
        }
    }

    private suspend fun runDownload(ep: Episode, filePath: String) {
        val target = File(filePath)
        target.parentFile?.mkdirs()

        // Resume if we have prior bytes AND the file exists at that size or
        // greater. If file size doesn't match the row, it's a corrupt
        // partial — wipe and start over.
        val priorRow = downloadDao.get(ep.guid)
        val resumeFrom = if (priorRow != null && priorRow.bytesDownloaded > 0L && target.exists() &&
            target.length() == priorRow.bytesDownloaded
        ) {
            priorRow.bytesDownloaded
        } else {
            if (target.exists()) target.delete()
            0L
        }

        flipToDownloading(ep.guid, resumeFrom, priorRow?.contentLength ?: -1L)

        val reqBuilder = Request.Builder().url(ep.audioUrl)
        if (resumeFrom > 0L) reqBuilder.header("Range", "bytes=$resumeFrom-")
        // Some podcast hosts reject the OkHttp default UA; mirror the player.
        reqBuilder.header("User-Agent", "LofiPod/${BuildIdentifierProvider.versionLabel(context)}")

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
            }
            // 206 means server accepted Range; 200 means it ignored Range
            // and is sending the full file from byte 0 — in that case we
            // restart the file rather than appending bytes from the wrong
            // offset.
            val isPartial = resp.code == 206
            val effectiveStart = if (isPartial) resumeFrom else 0L
            // Compute total content length. For 206, server's Content-Length
            // is the remaining; total = effectiveStart + that. For 200,
            // total = Content-Length directly. -1 if unknown.
            val responseLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val totalLen = when {
                responseLen < 0L -> -1L
                isPartial -> effectiveStart + responseLen
                else -> responseLen
            }

            // Reset the file if server ignored Range or we never had partial
            // bytes to resume from.
            if (effectiveStart == 0L && target.exists()) target.delete()

            val body = resp.body ?: throw IllegalStateException("Empty response body")
            val source = body.byteStream()
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(effectiveStart)
                val buf = ByteArray(BUFFER_SIZE)
                var totalWritten = effectiveStart
                var lastTickAt = System.currentTimeMillis()
                while (true) {
                    // Throws CancellationException if the user cancelled
                    // (remove() called) — propagates up to the launch's
                    // try/catch which knows to NOT mark completed/failed
                    // on cancellation. Critical: without this we'd silently
                    // exit the loop on cancel and then markCompleted() on
                    // a partial file. ensureActive() is the correct idiom
                    // here vs. an isActive boolean check that we'd then
                    // have to remember to handle separately.
                    coroutineContext.ensureActive()
                    val n = source.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    totalWritten += n
                    val now = System.currentTimeMillis()
                    if (now - lastTickAt >= PROGRESS_TICK_MS) {
                        emitProgress(ep.guid, totalWritten, totalLen)
                        lastTickAt = now
                    }
                }
                // Final position emit so the UI lands exactly on the
                // completion bytes, regardless of tick alignment.
                emitProgress(ep.guid, totalWritten, totalLen)
            }
        }

        // If we got here without throwing, the body fully drained.
        markCompleted(ep.guid, target.length(), filePath)
    }

    private suspend fun flipToDownloading(guid: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        downloadDao.updateProgress(
            guid = guid,
            bytes = bytes,
            total = total,
            state = LofiDownload.State.DOWNLOADING.ordinal,
            error = null,
            now = now,
        )
        _byId.value = _byId.value.toMutableMap().apply {
            val ex = get(guid)
            put(
                guid,
                LofiDownload(
                    guid = guid,
                    state = LofiDownload.State.DOWNLOADING,
                    bytesDownloaded = bytes,
                    contentLength = total,
                    filePath = ex?.filePath,
                    errorMessage = null,
                ),
            )
        }
    }

    private suspend fun emitProgress(guid: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        // Only every Nth tick hits the DB to avoid SQLite churn for fast
        // downloads on fast networks. The in-memory StateFlow always emits.
        downloadDao.updateProgress(
            guid = guid,
            bytes = bytes,
            total = total,
            state = LofiDownload.State.DOWNLOADING.ordinal,
            error = null,
            now = now,
        )
        _byId.value = _byId.value.toMutableMap().apply {
            val ex = get(guid)
            put(
                guid,
                LofiDownload(
                    guid = guid,
                    state = LofiDownload.State.DOWNLOADING,
                    bytesDownloaded = bytes,
                    contentLength = total,
                    filePath = ex?.filePath,
                    errorMessage = null,
                ),
            )
        }
    }

    private suspend fun markCompleted(guid: String, finalBytes: Long, filePath: String) {
        downloadDao.updateProgress(
            guid = guid,
            bytes = finalBytes,
            total = finalBytes,
            state = LofiDownload.State.COMPLETED.ordinal,
            error = null,
            now = System.currentTimeMillis(),
        )
        _byId.value = _byId.value.toMutableMap().apply {
            put(
                guid,
                LofiDownload(
                    guid = guid,
                    state = LofiDownload.State.COMPLETED,
                    bytesDownloaded = finalBytes,
                    contentLength = finalBytes,
                    filePath = filePath,
                    errorMessage = null,
                ),
            )
        }
    }

    private suspend fun markFailed(guid: String, message: String) {
        val priorRow = downloadDao.get(guid)
        downloadDao.updateProgress(
            guid = guid,
            bytes = priorRow?.bytesDownloaded ?: 0L,
            total = priorRow?.contentLength ?: -1L,
            state = LofiDownload.State.FAILED.ordinal,
            error = message,
            now = System.currentTimeMillis(),
        )
        _byId.value = _byId.value.toMutableMap().apply {
            val ex = get(guid)
            put(
                guid,
                LofiDownload(
                    guid = guid,
                    state = LofiDownload.State.FAILED,
                    bytesDownloaded = ex?.bytesDownloaded ?: 0L,
                    contentLength = ex?.contentLength ?: -1L,
                    filePath = ex?.filePath,
                    errorMessage = message,
                ),
            )
        }
        AppDiagnostics.recordDownloadFailure(guid, message)
    }

    /**
     * Cancel any in-flight download for [guid] and delete the file + row.
     * Used by both the manual "delete" button and the auto-archive sweep.
     */
    fun remove(guid: String) {
        val toCancel = synchronized(activeJobsLock) {
            activeJobs.remove(guid)
        }
        toCancel?.cancel()
        cleanupScope.launch {
            val row = downloadDao.get(guid)
            if (row != null) {
                runCatching { File(row.filePath).delete() }
            }
            downloadDao.delete(guid)
            autoDownloadDao.delete(guid)
            _byId.value = _byId.value.toMutableMap().apply {
                remove(guid)
            }
        }
    }

    /**
     * Resolve the on-disk file for [guid], or null if no completed download
     * exists. Used by [com.lofipod.app.player.PlayerController] to prefer
     * the local file when constructing a [androidx.media3.common.MediaItem].
     */
    fun completedFile(guid: String): File? {
        val state = _byId.value[guid] ?: return null
        if (state.state != LofiDownload.State.COMPLETED) return null
        val path = state.filePath ?: return null
        val f = File(path)
        return if (f.exists() && f.length() > 0L) f else null
    }

    /** Synchronously read state. Kept for the few callers (orphan sweeps,
     *  archive cleanups) that need a "right now" snapshot off the UI path. */
    fun snapshot(guid: String): LofiDownload? = _byId.value[guid]

    /**
     * Local file path for [guid], regardless of download state. Used by the
     * downloader internally and by callers that need to clean up by path.
     */
    private fun fileFor(guid: String): File {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(guid.toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return File(downloadsDir, "${hex.take(32)}.bin")
    }

    companion object {
        private const val DOWNLOAD_DIR_NAME = "episode_audio"
        private const val MAX_PARALLEL_DOWNLOADS = 2
        private const val BUFFER_SIZE = 64 * 1024
        /** Throttle DB writes + StateFlow emits to ~2 per second per
         *  download so a 10 MB/s connection doesn't churn 100+ tiny
         *  emits/sec. The UI's progress arc is human-perceptual; 500 ms
         *  is the sweet spot. */
        private const val PROGRESS_TICK_MS = 500L
    }
}

/**
 * Helper to read the app's versionName at construction time without coupling
 * the downloader to BuildConfig (which doesn't include versionName from a
 * gradle property override path consistently). Falls back to "0.x" if the
 * package query fails — only used in the User-Agent header so a stale
 * fallback doesn't break anything.
 */
private object BuildIdentifierProvider {
    fun versionLabel(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.x"
    }.getOrElse { "0.x" }
}
