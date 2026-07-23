package com.lofipod.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Completed read-ahead scan of one episode's audio.
 *
 * [pauses] are the audible-silence spans an offline decode found — the
 * same spans [PauseTapProcessor] would record live, but covering the WHOLE
 * file instead of only the regions the decoder has visited this session.
 * [envelope] is a fixed-size max-|amplitude| waveform sketch: exactly
 * [EpisodeAudioAnalyzer.ENVELOPE_BUCKETS] values in 0..1, mono-mixed
 * (peak across channels), normalized to the file's loudest moment so
 * quiet recordings still render full-height. Buckets divide [durationMs]
 * evenly, so bucket i covers media time
 * `[i, i+1) * durationMs / ENVELOPE_BUCKETS`.
 * [lsterPerSec] is one byte per media second: the second's LSTER
 * (low short-time energy ratio — the fraction of its 20 ms mono-RMS
 * frames below half the second's mean) scaled by
 * [EpisodeAudioAnalyzer.LSTER_SCALE], or [EpisodeAudioAnalyzer.LSTER_SILENT]
 * for a near-silent second. Clean speech scores high (deep gaps between
 * syllables and phrases); music and speech over a music bed score low
 * (the bed fills the gaps). Powers SectionSkip's "skip past
 * sponsor/music section" texture scan.
 *
 * Deliberately a plain class, not a data class: the array fields
 * would give generated equals/copy reference semantics that read like
 * value semantics.
 */
class EpisodeAnalysis(
    val durationMs: Long,
    val pauses: List<PauseTapProcessor.PauseSpan>,
    val envelope: FloatArray,
    val lsterPerSec: ByteArray,
)

/**
 * Offline decoder that scans an episode's audio front to back and
 * produces an [EpisodeAnalysis] — the first (and only) MediaExtractor +
 * MediaCodec path in the app; everything else decodes inside Media3.
 *
 * **Never competes with live playback.** All scans run on one dedicated
 * thread at [android.os.Process.THREAD_PRIORITY_LOWEST], so the OS
 * scheduler starves the scan — not the realtime audio chain — whenever
 * they'd contend. The single thread also serializes scans: at most one
 * decode is in flight no matter how many episodes ask, and a 2-hour
 * sermon queued behind another simply waits its turn.
 *
 * **Memory stays flat regardless of file length.** PCM is consumed
 * buffer-by-buffer as the codec emits it; nothing accumulates except a
 * per-quarter-second peak table (an IntArray — about 115 KB for 2 hours,
 * capped at 24 hours) that gets max-pooled down to [ENVELOPE_BUCKETS]
 * values at the end. Bucketizing against a running slice table instead
 * of the container's declared duration also sidesteps VBR MP3s whose
 * header duration lies — the envelope is mapped against the duration the
 * decode actually measured.
 *
 * **Pause detection mirrors [PauseTapProcessor], not
 * [SilenceSkippingProcessor].** The offline decode sees the raw source
 * signal, which is exactly what the pause tap sees (it sits first in the
 * live chain); the silence-skipper's thresholds are tuned against the
 * EQ-treated signal and would disagree. Sharing the tap's sensitivity
 * tables ([PauseTapProcessor.minSilenceMsFor] / [thresholdFor]) means a
 * precomputed mark and a live-recorded mark for the same gap land in the
 * same place. One deliberate divergence: a quiet run still open at
 * end-of-stream is dropped, matching the live tap (which only records
 * when a loud frame closes the run) — trailing fade-outs aren't pauses
 * anyone skips back to.
 *
 * **MP3 decoder choice** applies the same `c2.android.mp3.decoder`
 * avoidance as [com.lofipod.app.player.EqRenderersFactory]: this device's
 * Codec2 software MP3 decoder has demonstrated buffer-accounting bugs in
 * field logs, so we prefer the older OMX software decoder when present.
 *
 * Errors are the caller's problem by design: anything unexpected —
 * missing audio track, non-16-bit PCM output, codec stall, deleted file —
 * throws, and the repository above translates every throw into "no
 * analysis," which the UI renders as simply no marks.
 */
class EpisodeAudioAnalyzer {

    /**
     * Decode the source configured by [setDataSource] and return its
     * analysis. Suspends until the full file has been decoded (minutes
     * for a long sermon over the network); honors cancellation between
     * codec buffers. [setDataSource] receives a fresh [MediaExtractor]
     * and must point it at the audio — a local path, a `content://` URI
     * via a Context overload, or a progressive http(s) URL (the extractor
     * streams those itself; no OkHttp involved).
     */
    suspend fun analyze(
        sensitivity: Int,
        setDataSource: (MediaExtractor) -> Unit,
    ): EpisodeAnalysis = withContext(scanDispatcher) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setDataSource(extractor)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("no mime type")

            // Updated again on INFO_OUTPUT_FORMAT_CHANGED — decoders may
            // normalize (e.g. mono MP3 upmixed to stereo by some codecs),
            // and chained streams can switch rates mid-file.
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0 || channelCount <= 0) error("degenerate track format")

            codec = createDecoder(mime)
            codec.configure(format, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
            codec.start()

            // Detection state shared across output buffers. Times are
            // stream-absolute microseconds derived from each buffer's
            // presentation timestamp plus a per-frame offset, so a pause
            // spanning a buffer boundary is measured seamlessly.
            val thresholdPcm = PauseTapProcessor.thresholdFor(sensitivity)
            val minSilenceUs = PauseTapProcessor.minSilenceMsFor(sensitivity) * 1_000L
            val pauses = ArrayList<PauseTapProcessor.PauseSpan>()
            var quietStartUs = -1L

            var slicePeaks = IntArray(INITIAL_SLICE_CAPACITY)
            var sliceCount = 0
            var globalPeak = 0
            var lastEndUs = 0L

            // LSTER accumulation (see EpisodeAnalysis.lsterPerSec): mono
            // sum-of-squares per 20 ms frame, frames grouped per media
            // second. Streams into a byte builder — memory stays flat.
            var l20Key = -1L
            var l20SumSq = 0.0
            var l20N = 0
            var lSecKey = -1L
            val lSecRms = FloatArray(64)
            var lSecN = 0
            val lsterOut = ByteArrayOutputStream()

            fun writeCurrentSecond() {
                if (lSecKey < 0 || lSecN == 0) return
                var mean = 0.0
                for (i in 0 until lSecN) mean += lSecRms[i]
                mean /= lSecN
                val b = if (mean < LSTER_SILENT_RMS) LSTER_SILENT else {
                    var low = 0
                    val half = 0.5 * mean
                    for (i in 0 until lSecN) if (lSecRms[i] < half) low++
                    low * LSTER_SCALE / lSecN
                }
                lsterOut.write(b)
            }

            fun closeL20() {
                if (l20Key < 0 || l20N == 0) return
                val rms = sqrt(l20SumSq / l20N).toFloat()
                val sec = l20Key / L20_PER_SECOND
                if (sec != lSecKey) {
                    // Seconds must stay index-aligned with media time:
                    // close the finished second, backfill any timestamp
                    // gap with silence sentinels, start the new one.
                    writeCurrentSecond()
                    var fillFrom = if (lSecKey < 0) 0L else lSecKey + 1
                    while (fillFrom < sec) {
                        lsterOut.write(LSTER_SILENT)
                        fillFrom++
                    }
                    lSecKey = sec
                    lSecN = 0
                }
                if (lSecN < lSecRms.size) {
                    lSecRms[lSecN++] = rms
                }
                l20SumSq = 0.0
                l20N = 0
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            // Consecutive iterations where the codec offered neither an
            // input slot nor output. A healthy codec never idles long;
            // a wedged one would otherwise spin this loop forever.
            var idleSpins = 0

            while (!outputDone) {
                ensureActive()
                var progressed = false

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        progressed = true
                        val inBuf = codec.getInputBuffer(inIdx) ?: error("null input buffer")
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        progressed = true
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx) ?: error("null output buffer")
                            // getOutputBuffer already arrives positioned at
                            // offset/limit; set both anyway — cheap insurance
                            // against decoder quirks.
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pcm = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = info.size / (2 * channelCount)
                            val baseUs =
                                if (info.presentationTimeUs >= 0) info.presentationTimeUs
                                else lastEndUs

                            for (frame in 0 until frames) {
                                // Peak abs amplitude across channels — the
                                // same per-frame statistic the live
                                // processors use — plus the signed channel
                                // sum for the LSTER mono mix.
                                var peak = 0
                                var sum = 0
                                for (ch in 0 until channelCount) {
                                    val s = pcm.get().toInt()
                                    val a = abs(s)
                                    if (a > peak) peak = a
                                    sum += s
                                }
                                val tUs = baseUs + frame * 1_000_000L / sampleRate

                                // LSTER: accumulate mono^2 into the 20 ms
                                // frame under this timestamp.
                                val mono = sum / (channelCount * 32768.0)
                                val key20 = tUs / L20_US
                                if (key20 != l20Key) {
                                    closeL20()
                                    l20Key = key20
                                }
                                l20SumSq += mono * mono
                                l20N++

                                if (peak > globalPeak) globalPeak = peak
                                val slice = (tUs / SLICE_US).toInt()
                                if (slice in 0 until MAX_SLICES) {
                                    if (slice >= slicePeaks.size) {
                                        slicePeaks = slicePeaks.copyOf(
                                            max(slicePeaks.size * 2, slice + 1)
                                        )
                                    }
                                    if (peak > slicePeaks[slice]) slicePeaks[slice] = peak
                                    if (slice + 1 > sliceCount) sliceCount = slice + 1
                                }

                                if (peak < thresholdPcm) {
                                    if (quietStartUs < 0) quietStartUs = tUs
                                } else {
                                    if (quietStartUs >= 0 && tUs - quietStartUs >= minSilenceUs) {
                                        pauses.add(
                                            PauseTapProcessor.PauseSpan(
                                                startMs = quietStartUs / 1_000L,
                                                endMs = tUs / 1_000L,
                                            )
                                        )
                                    }
                                    quietStartUs = -1L
                                }
                            }
                            lastEndUs = baseUs + frames * 1_000_000L / sampleRate
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (sampleRate <= 0 || channelCount <= 0) error("degenerate output format")
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) !=
                            AudioFormat.ENCODING_PCM_16BIT
                        ) {
                            // Every live processor requires 16-bit PCM and so
                            // do we; a float-emitting decoder would silently
                            // misread amplitudes. Bail instead.
                            error("decoder emitted non-16-bit PCM")
                        }
                    }
                }

                idleSpins = if (progressed) 0 else idleSpins + 1
                if (idleSpins > MAX_IDLE_SPINS) error("decoder stalled")
            }

            val durationMs = lastEndUs / 1_000L
            if (durationMs <= 0L || sliceCount <= 0 || globalPeak <= 0) {
                error("decode produced no audio")
            }

            // Flush the trailing partial LSTER frame + second.
            closeL20()
            writeCurrentSecond()

            EpisodeAnalysis(
                durationMs = durationMs,
                pauses = capPauses(pauses),
                envelope = resampleEnvelope(slicePeaks, sliceCount, globalPeak),
                lsterPerSec = lsterOut.toByteArray(),
            )
        } finally {
            // Release order matters: stop can throw if the codec died
            // mid-scan; swallow so release and the extractor still run.
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Max-pool the quarter-second slice table down (or replicate it up,
     * for episodes shorter than [ENVELOPE_BUCKETS] slices) to exactly
     * [ENVELOPE_BUCKETS] values, normalized to the file's global peak.
     */
    private fun resampleEnvelope(slicePeaks: IntArray, sliceCount: Int, globalPeak: Int): FloatArray {
        val denom = globalPeak.toFloat()
        return FloatArray(ENVELOPE_BUCKETS) { i ->
            val lo = (i.toLong() * sliceCount / ENVELOPE_BUCKETS).toInt()
            val hi = ((i + 1).toLong() * sliceCount / ENVELOPE_BUCKETS).toInt()
            var peak = slicePeaks[lo.coerceAtMost(sliceCount - 1)]
            for (s in lo until hi.coerceAtMost(sliceCount)) {
                if (slicePeaks[s] > peak) peak = slicePeaks[s]
            }
            (peak / denom).coerceIn(0f, 1f)
        }
    }

    /**
     * Bound the mark list for pathological inputs (a high-sensitivity
     * scan of choppy audio can flag thousands of micro-gaps). Keep the
     * LONGEST pauses — they're the ones worth skipping back to — then
     * restore chronological order for the renderer.
     */
    private fun capPauses(
        pauses: List<PauseTapProcessor.PauseSpan>,
    ): List<PauseTapProcessor.PauseSpan> {
        if (pauses.size <= MAX_PAUSES) return pauses
        return pauses
            .sortedByDescending { it.endMs - it.startMs }
            .subList(0, MAX_PAUSES)
            .sortedBy { it.startMs }
    }

    /**
     * Decoder selection with the EqRenderersFactory precedent applied:
     * for MP3, prefer the OMX software decoder over `c2.android.*`
     * (known buffer-accounting bugs on this device), falling back to any
     * non-Codec2 decoder, then to the platform default. Name-prefix
     * heuristics rather than `MediaCodecInfo.isSoftwareOnly` because
     * that API is 29+ and minSdk is 28.
     */
    private fun createDecoder(mime: String): MediaCodec {
        if (mime.equals(MediaFormat.MIMETYPE_AUDIO_MPEG, ignoreCase = true)) {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
            val pick = candidates.firstOrNull { it.name.startsWith("OMX.google.") }
                ?: candidates.firstOrNull { !it.name.startsWith("c2.android.") }
            if (pick != null) return MediaCodec.createByCodecName(pick.name)
        }
        return MediaCodec.createDecoderByType(mime)
    }

    companion object {
        /** Envelope resolution. ~2000 buckets keeps a bucket around one
         *  horizontal pixel even on a wide landscape scrubber, and the
         *  persisted BLOB at one byte per bucket stays a flat 2 KB/row. */
        const val ENVELOPE_BUCKETS = 2000

        /** Raw peak-table granularity: one slice per 250 ms. For a
         *  2-hour sermon that's 28,800 ints (~115 KB) — the scan's whole
         *  memory footprint — and still 14+ slices per envelope bucket,
         *  so max-pooling loses nothing visible. */
        private const val SLICE_US = 250_000L

        /** Slice-table ceiling, 24 hours of audio (~1.4 MB). Frames past
         *  it stop contributing to the envelope; a longer "episode" is a
         *  degenerate input, not a use case. */
        private const val MAX_SLICES = 24 * 60 * 60 * 4

        /** Initial slice capacity: 45 minutes, a typical sermon. Longer
         *  files double the array a handful of times — cheap. */
        private const val INITIAL_SLICE_CAPACITY = 45 * 60 * 4

        /** Persisted-mark cap. 2000 spans is far beyond any real sermon
         *  at the default sensitivity and bounds the row to ~30 KB of
         *  CSV in the worst case. */
        private const val MAX_PAUSES = 2000

        /** LSTER frame length (20 ms) and frames per second. */
        private const val L20_US = 20_000L
        private const val L20_PER_SECOND = 50L

        /** lsterPerSec byte semantics: value = LSTER * [LSTER_SCALE]
         *  (0..200), or [LSTER_SILENT] for a near-silent second. Shared
         *  with SectionSkip's reader. */
        const val LSTER_SCALE = 200
        const val LSTER_SILENT = 255

        /** Mean 20 ms RMS (full-scale = 1.0) below which a second is
         *  "silent" — LSTER of near-nothing is noise, not texture. */
        private const val LSTER_SILENT_RMS = 3.0e-5

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** Stall guard: ~60 s of the codec offering no input slot and no
         *  output (two 10 ms timeouts per spin) means it's wedged, not
         *  slow — network stalls block inside readSampleData and never
         *  spin here. */
        private const val MAX_IDLE_SPINS = 3_000

        /**
         * The one thread all scans share. LOWEST priority (below
         * THREAD_PRIORITY_BACKGROUND) so a scan concurrent with live
         * playback loses every scheduling contest to the audio chain; a
         * single thread so two episodes can't decode simultaneously.
         * Priority is set inside the runnable because setThreadPriority
         * applies to the CALLING thread — a factory-side call would
         * retune whichever thread built the executor.
         */
        private val scanDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_LOWEST
                )
                r.run()
            }, "episode-analyzer")
        }.asCoroutineDispatcher()
    }
}
