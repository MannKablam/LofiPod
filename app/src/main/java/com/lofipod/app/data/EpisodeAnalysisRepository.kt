package com.lofipod.app.data

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import com.lofipod.app.audio.EpisodeAnalysis
import com.lofipod.app.audio.EpisodeAudioAnalyzer
import com.lofipod.app.audio.PauseTapProcessor
import com.lofipod.app.data.db.EpisodeAnalysisDao
import com.lofipod.app.data.db.EpisodeAnalysisEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Read-ahead analysis cache: hands the UI a completed [EpisodeAnalysis]
 * for any episode that has one, and quietly arranges scans for episodes
 * that don't. The player screen calls [ensureAnalyzed] whenever an
 * episode is displayed and observes [analysisFor]; everything between —
 * source resolution, deduplication, decoding, persistence — is this
 * class's business.
 *
 * **Local sources only.** The scan reads the downloaded local file when
 * one exists (via the DAO-fallback-aware `completedFile`, never the racy
 * snapshot), or the SAF `content://` URI for device-file episodes (whose
 * guid IS that URI). It deliberately does NOT stream the enclosure over
 * the network: a second full-file HTTP fetch of an episode that's still
 * live-streaming (and auto-downloading) is exactly the second-socket CDN
 * contention this app fought off with `AUTOPLAY_DOWNLOAD_DELAY_MS`, and a
 * blocked network read has no cancellation path off the analyzer's single
 * decode thread. Since playing an episode auto-downloads it, the local
 * file lands within a minute and the scan follows; until then the guid
 * stays eligible (a no-op, never a failure) and the UI simply shows no
 * precomputed marks, falling back to the live pause tap's this-session
 * spans.
 *
 * **Failure is silent by contract.** A failed decode logs one line to
 * stderr, marks the guid as failed for this session (a decode failure on
 * a completed local file is structural — unsupported codec, corrupt
 * audio — so retrying on every screen visit is pointless), and persists
 * nothing; the UI observing [analysisFor] keeps seeing null and draws no
 * marks. "No local file yet" is NOT a failure: the guid stays eligible,
 * so an episode that finishes downloading later scans on the next
 * [ensureAnalyzed].
 *
 * **Dedup + cancellation** follow the LofiPodDownloader house pattern:
 * a per-guid Job map under a lock makes concurrent [ensureAnalyzed]
 * calls for the same episode idempotent, and [cancel] can abandon a
 * scan mid-decode (the analyzer checks for cancellation between codec
 * buffers). Scans are NOT resumable — the process can die mid-decode
 * whenever playback stops holding it alive — but since only completed
 * analyses are persisted, a killed scan just reruns from the top next
 * time it's asked for.
 */
class EpisodeAnalysisRepository(
    private val context: Context,
    private val dao: EpisodeAnalysisDao,
    private val downloader: LofiPodDownloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analyzer = EpisodeAudioAnalyzer()
    private val settings by lazy { Settings(context) }

    /** Per-guid in-flight scan, so a second request is a no-op and
     *  [cancel] can abandon the right coroutine. */
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeJobsLock = Any()

    /** Guids whose scan failed this session — don't retry until the next
     *  app launch (a decode failure on a completed local file is usually
     *  structural: unreadable file, unsupported codec), but DO forget
     *  across launches in case the world improved. */
    private val failedThisSession = ConcurrentHashMap.newKeySet<String>()

    /**
     * The episode's completed analysis, or null while none exists. Room
     * re-emits when a scan lands, so collecting this alongside a prior
     * [ensureAnalyzed] call is the whole UI contract: draw nothing on
     * null, draw the layers when it arrives.
     */
    fun analysisFor(episodeKey: String): Flow<EpisodeAnalysis?> =
        // distinctUntilChanged on the ENTITY, before decode mints a fresh
        // EpisodeAnalysis instance: Room re-runs and re-emits a query's
        // result on EVERY invalidation of episode_analysis (any episode's
        // scan landing), not only when this row changes. EpisodeAnalysisEntity
        // carries content-equality equals precisely so that dedup is
        // possible — but it is dead weight unless something applies it here.
        // Without this, a sibling scan completing hands the UI a "new" but
        // identical analysis, which resets the waveform panel's viewport
        // (its state is keyed on the analysis instance) and kills any pan or
        // playhead drag in flight.
        dao.observe(episodeKey).distinctUntilChanged().map { row -> row?.let(::decode) }

    /**
     * Kick off a scan for [guid] unless a current one already exists.
     * Returns immediately; work happens on [scope] and, for the decode
     * itself, the analyzer's dedicated lowest-priority thread. Safe to
     * call repeatedly at UI cadence — a present row, an in-flight scan,
     * or a this-session failure all short-circuit cheaply.
     *
     * Scans only run against a LOCAL source (a downloaded file or a device
     * `content://` guid); an episode with neither present yet is a no-op
     * that stays eligible, so callers should re-invoke when its download
     * completes.
     */
    fun ensureAnalyzed(guid: String) {
        if (guid.isBlank()) return
        if (guid in failedThisSession) return
        synchronized(activeJobsLock) {
            if (activeJobs.containsKey(guid)) return
            val job = scope.launch { runScan(guid) }
            activeJobs[guid] = job
            job.invokeOnCompletion {
                synchronized(activeJobsLock) {
                    if (activeJobs[guid] === job) activeJobs.remove(guid)
                }
            }
        }
    }

    /** Abandon an in-flight scan (episode removed, download deleted).
     *  No-op when none is running; the guid stays eligible to rescan. */
    fun cancel(guid: String) {
        val job = synchronized(activeJobsLock) { activeJobs.remove(guid) }
        job?.cancel()
    }

    private suspend fun runScan(guid: String) {
        try {
            // Marks must agree with the live pause tap, so scan at the
            // user's current pause-skip sensitivity. A row scanned at a
            // stale sensitivity is rescanned; the envelope comes along
            // for free.
            val sensitivity = settings.pauseSkipSensitivity.first()
            val existing = dao.get(guid)
            if (existing != null && existing.sensitivity == sensitivity) return

            val local = downloader.completedFile(guid)
            val setDataSource: (MediaExtractor) -> Unit = when {
                local != null -> { ex -> ex.setDataSource(local.absolutePath) }
                guid.startsWith("content://") -> { ex ->
                    ex.setDataSource(context, Uri.parse(guid), null)
                }
                // No local source yet — NOT a failure. The guid stays
                // eligible so the next ensureAnalyzed (fired when the
                // download reaches COMPLETED) scans the file. We never
                // stream the enclosure: a second full-file HTTP read would
                // contend with the live stream and the auto-download (the
                // documented BUFFERING oscillation), and a blocked network
                // read can't be cancelled off the single decode thread.
                else -> return
            }

            val result = analyzer.analyze(sensitivity, setDataSource)
            dao.upsert(encode(guid, sensitivity, result))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            failedThisSession.add(guid)
            System.err.println("Episode analysis failed for $guid: ${e.message}")
        }
    }

    private fun encode(guid: String, sensitivity: Int, result: EpisodeAnalysis): EpisodeAnalysisEntity {
        val blob = ByteArray(result.envelope.size) { i ->
            (result.envelope[i].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return EpisodeAnalysisEntity(
            guid = guid,
            durationMs = result.durationMs,
            sensitivity = sensitivity,
            pausesCsv = result.pauses.joinToString(",") { "${it.startMs}:${it.endMs}" },
            envelope = blob,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Tolerant decode, [EpisodeHeatRecorder.decode] spirit: a row whose
     *  payload doesn't parse is served as absent, never as garbage. */
    private fun decode(row: EpisodeAnalysisEntity): EpisodeAnalysis? {
        if (row.durationMs <= 0L) return null
        if (row.envelope.size != EpisodeAudioAnalyzer.ENVELOPE_BUCKETS) return null
        val envelope = FloatArray(row.envelope.size) { i ->
            (row.envelope[i].toInt() and 0xFF) / 255f
        }
        return EpisodeAnalysis(
            durationMs = row.durationMs,
            pauses = decodePauses(row.pausesCsv),
            envelope = envelope,
        )
    }

    private fun decodePauses(csv: String): List<PauseTapProcessor.PauseSpan> {
        if (csv.isBlank()) return emptyList()
        return csv.split(',').mapNotNull { pair ->
            val sep = pair.indexOf(':')
            if (sep <= 0) return@mapNotNull null
            val start = pair.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
            val end = pair.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null
            if (end <= start) null else PauseTapProcessor.PauseSpan(start, end)
        }
    }
}
