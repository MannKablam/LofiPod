package com.lofipod.app.audio

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Diagnostic snapshot of the live audio chain. Single-instance, thread-safe
 * (audio thread writes, UI thread reads); the UI surface
 * ([com.lofipod.app.ui.screens.AudioDiagnosticsScreen]) renders these values
 * for breadcrumb-style troubleshooting of "why does this sound wrong?"
 *
 * **Threading.** Live readouts are `@Volatile` so the audio thread can write
 * cheaply and the UI thread sees up-to-date values without locking. Counters
 * use [AtomicInteger]/[AtomicLong] for correct read-modify-write under
 * concurrent access. Events use a fixed-capacity ring buffer guarded by a
 * mutex; logEvent is called only from the cold paths (configure/flush/preset
 * change), never from the per-buffer hot loop, so the lock overhead is
 * negligible.
 *
 * **Allocation discipline.** No allocation in the hot loop — peak readouts
 * are decayed in-place. [logEvent] does allocate a small [Event] object, but
 * it's only called a handful of times per second at most.
 */
object AudioChainTelemetry {

    // ---- Chain configuration (one-shot per onConfigure) ----
    @Volatile var sampleRate: Int = 0
    @Volatile var channelCount: Int = 0
    @Volatile var encoding: String = "(unconfigured)"
    @Volatile var dcBlockerEnabled: Boolean = false
    @Volatile var firTaps: Int = 0
    @Volatile var lookAheadSamples2x: Int = 0
    @Volatile var thresholdDbfs: Double = 0.0
    @Volatile var totalLatencyFrames1x: Int = 0

    /** Total chain latency in milliseconds, computed from [sampleRate] and
     *  [totalLatencyFrames1x]. Returns 0 when not yet configured. */
    fun totalLatencyMs(): Double =
        if (sampleRate > 0) totalLatencyFrames1x * 1000.0 / sampleRate else 0.0

    // ---- Live readouts (updated every audio frame) ----
    /** Peak amplitude post-EQ + gain, pre-upsampler. Decayed for meter use. */
    @Volatile var inputPeak: Double = 0.0
    /** Peak amplitude at chain output (post-downsampler, pre-truncate). */
    @Volatile var outputPeak: Double = 0.0
    /** Limiter gain reduction in dB. 0 = no reduction. */
    @Volatile var reductionDb: Double = 0.0
    /** True when the EQ cross-fade window is mid-flight. */
    @Volatile var fading: Boolean = false
    /** True when the chain is in passthrough (FLAT + 0 dB gain + DC blocker off). */
    @Volatile var passthrough: Boolean = false
    /** True when TPDF dither is being applied this frame. */
    @Volatile var ditherActive: Boolean = false
    /** True when the audio chain master switch is on. */
    @Volatile var enabled: Boolean = false

    // Decay coefficients for peak meters (computed lazily on first configure).
    private var peakDecayCoef: Double = 1.0
    private const val PEAK_DECAY_HALFLIFE_SEC = 0.5

    /** Update the peak-meter decay coefficient on configure. */
    internal fun configurePeakDecay(rate: Int) {
        peakDecayCoef = if (rate > 0) exp(-ln(2.0) / (rate * PEAK_DECAY_HALFLIFE_SEC)) else 1.0
    }

    /** Hot-path peak update for one frame (linked across channels). Called from
     *  [EqAudioProcessor.queueInput]; decays the held value and replaces with
     *  abs(sample) when it's larger. */
    internal fun pushInputSample(value: Double) {
        val a = abs(value)
        val current = inputPeak
        inputPeak = if (a > current) a else current * peakDecayCoef
    }

    internal fun pushOutputSample(value: Double) {
        val a = abs(value)
        val current = outputPeak
        outputPeak = if (a > current) a else current * peakDecayCoef
    }

    // ---- Counters (cumulative since last [reset]) ----
    private val configures = AtomicInteger(0)
    private val flushes = AtomicInteger(0)
    private val crossFades = AtomicInteger(0)
    private val bandChanges = AtomicInteger(0)
    private val drains = AtomicInteger(0)
    private val passthroughBuffers = AtomicLong(0)
    private val dspBuffers = AtomicLong(0)
    private val framesProcessed = AtomicLong(0)

    fun configureCount(): Int = configures.get()
    fun flushCount(): Int = flushes.get()
    fun crossFadeCount(): Int = crossFades.get()
    fun bandChangeCount(): Int = bandChanges.get()
    fun drainCount(): Int = drains.get()
    fun passthroughBufferCount(): Long = passthroughBuffers.get()
    fun dspBufferCount(): Long = dspBuffers.get()
    fun framesProcessedCount(): Long = framesProcessed.get()

    internal fun incConfigures() { configures.incrementAndGet() }
    internal fun incFlushes() { flushes.incrementAndGet() }
    internal fun incCrossFades() { crossFades.incrementAndGet() }
    internal fun incBandChanges() { bandChanges.incrementAndGet() }
    internal fun incDrains() { drains.incrementAndGet() }
    internal fun incPassthroughBuffers() { passthroughBuffers.incrementAndGet() }
    internal fun incDspBuffers() { dspBuffers.incrementAndGet() }
    internal fun addFrames(n: Int) { framesProcessed.addAndGet(n.toLong()) }

    // ---- Audio thread identity + scheduler hinting ----
    /**
     * Linux thread-id (TID) of the audio sink thread that's currently
     * pumping the EQ chain. Captured on the first [recordBufferTiming] call
     * — and re-captured on the rare event of the audio sink swapping
     * threads (e.g. format change, sink rebuild). Compared each buffer to
     * detect the swap; the actual update only fires when the value changes,
     * so the cost is one volatile read per buffer.
     */
    @Volatile var audioThreadId: Int = -1
    /** Java thread name of the audio thread (e.g. "ExoPlayer:Playback"). */
    @Volatile var audioThreadName: String = ""
    /**
     * Most recent observed thread priority for [audioThreadId], read via
     * `android.os.Process.getThreadPriority`. Audio threads in Media3 should
     * report `THREAD_PRIORITY_AUDIO = -16`; if this reads `0` (default) or
     * any positive number, something has gone wrong with audio prioritization
     * and underruns at low CPU budgets become much more likely.
     */
    @Volatile var audioThreadPriority: Int = Int.MIN_VALUE

    /**
     * Tracks whether we've already emitted a `priority_demoted` breadcrumb
     * for the current demotion. Reset to false when the priority recovers
     * (back to <0). Without this gate, a sustained demotion would log a new
     * event every buffer (~50/sec) and flood the 50-slot ring within one
     * second, evicting the rest of the chain history. With it, exactly two
     * events are logged per demotion-recovery cycle: the transition into
     * the wrong state and the transition back out.
     */
    @Volatile private var priorityDemotionLogged: Boolean = false

    /**
     * Cumulative count of priority-demotion events observed since process
     * start (or last [resetCountersAndEvents]). One increment per demotion
     * transition (not per buffer). Surfaced in the diagnostics Scheduler
     * section so a user can see at a glance whether OEM thermal demotion
     * has been a recurring issue this session.
     */
    private val priorityDemotions = AtomicInteger(0)
    fun priorityDemotionCount(): Int = priorityDemotions.get()

    /**
     * Current playback speed, as last observed via PlayerController's
     * Player.Listener.onPlaybackParametersChanged or PlayerController.setSpeed.
     * Used to scale the wall-clock budget passed to the
     * [PerformanceHintManager] target: each buffer holds N frames of audio
     * worth `audioNs` of media time, but at 2× playback that audio drains in
     * `audioNs / 2` of wall-clock, so the OS scheduler's deadline budget is
     * `audioNs / speed` — without this scaling, the governor picks a CPU
     * frequency for a deadline twice as generous as reality and the DSP
     * thread starts missing deadlines when the screen goes off and the
     * compositor stops masking the slack.
     *
     * @Volatile so a Main-thread setter is visible to the audio thread
     * without synchronization. Default 1.0f matches Media3's default
     * PlaybackParameters.speed so first-buffer math is correct before any
     * onPlaybackParametersChanged event has fired.
     */
    @Volatile var playbackSpeed: Float = 1.0f

    /**
     * Application-scoped bridge to [android.os.PerformanceHintManager], lazily
     * installed once at process start by [installPerformanceHintBridge]. Held
     * here (rather than in a separate singleton) so the audio thread can hop
     * straight from telemetry into a hint update without an extra lookup.
     */
    @Volatile private var perfHint: PerformanceHintBridge? = null

    /** True if the device exposes PerformanceHintManager (Android 12+). */
    val perfHintApiSupported: Boolean
        get() = perfHint?.isSupported == true

    /** True once a hint session has been successfully created. */
    val perfHintSessionActive: Boolean
        get() = perfHint?.isActive == true

    /** Current target work duration set on the hint session, ns. */
    val perfHintTargetNs: Long
        get() = perfHint?.currentTargetNs ?: 0L

    /** Last error message from the perf-hint bridge, or null if no error. */
    val perfHintLastError: String?
        get() = perfHint?.lastError

    /**
     * Install the performance-hint bridge once, at app startup. Idempotent —
     * a second call replaces the previous bridge (closing any active session
     * to avoid leaks). Safe to call before any audio plays.
     */
    fun installPerformanceHintBridge(context: Context) {
        perfHint?.close()
        perfHint = PerformanceHintBridge(context)
        logEvent(
            "perf_hint_install",
            if (perfHint?.isSupported == true) "supported" else "unsupported"
        )
    }

    // ---- Per-buffer processing time (wallclock around queueInput's DSP path) ----
    /**
     * Wallclock nanoseconds spent in the DSP path for the most recent buffer.
     * Read together with [lastBufferAudioNs] to compute a load factor — values
     * close to 1.0 mean the audio thread is barely keeping up with real time
     * (= imminent underrun if the OS throttles further).
     */
    @Volatile var lastBufferProcessingNs: Long = 0L
    /** Wallclock duration of audio represented by the most recent buffer, ns. */
    @Volatile var lastBufferAudioNs: Long = 0L
    /** Maximum DSP-path wallclock observed since the last counters reset. */
    @Volatile var maxBufferProcessingNs: Long = 0L

    private const val BUFFER_TIMING_RING_SIZE = 64
    private val recentBufferProcessingNs = LongArray(BUFFER_TIMING_RING_SIZE)
    private val recentBufferAudioNs = LongArray(BUFFER_TIMING_RING_SIZE)
    private var recentBufferPos = 0
    private var recentBufferCount = 0
    private val recentBufferLock = Any()

    /**
     * Record one buffer's DSP-path wallclock + audio duration. Called once
     * per buffer (not per frame) — overhead is one synchronized block on a
     * tiny lock plus a few @Volatile writes, contention with the UI thread's
     * [computeBufferTimingStats] reads is rare.
     *
     * Also captures audio-thread identity + priority on the first call (or
     * when the audio sink swaps threads, which is rare), and forwards
     * target / actual durations to the [PerformanceHintBridge] so the OS
     * scheduler can keep the CPU at the right frequency for the workload.
     * Both are cheap: a TID compare + (on change) a priority read, plus
     * one JNI hop into the perf hint service per buffer when supported.
     */
    fun recordBufferTiming(processingNs: Long, audioNs: Long) {
        lastBufferProcessingNs = processingNs
        lastBufferAudioNs = audioNs
        if (processingNs > maxBufferProcessingNs) maxBufferProcessingNs = processingNs
        synchronized(recentBufferLock) {
            recentBufferProcessingNs[recentBufferPos] = processingNs
            recentBufferAudioNs[recentBufferPos] = audioNs
            recentBufferPos = (recentBufferPos + 1) % BUFFER_TIMING_RING_SIZE
            if (recentBufferCount < BUFFER_TIMING_RING_SIZE) recentBufferCount++
        }

        // Capture audio-thread identity. Re-runs only when the TID changes
        // (process start, sink rebuild, format change after a flush). The
        // priority is re-read every buffer because OEM kernels can demote
        // audio threads under thermal pressure or aggressive power saving;
        // diagnostics needs to surface that if it happens.
        //
        // try/catch (not runCatching) to honor this file's hot-loop
        // allocation discipline: a runCatching lambda that captures `tid`
        // would allocate a new lambda instance each buffer.
        val tid = android.os.Process.myTid()
        if (tid != audioThreadId) {
            audioThreadId = tid
            audioThreadName = try {
                Thread.currentThread().name
            } catch (_: Throwable) { "?" }
            logEvent("audio_thread", "tid=$tid name=$audioThreadName")
        }
        val priority = try {
            android.os.Process.getThreadPriority(tid)
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
        audioThreadPriority = priority

        // Detect priority demotion: anything >= 0 means the audio thread
        // is no longer above normal scheduler priority. This is a real bug
        // (Media3 should set THREAD_PRIORITY_AUDIO = -16) or an OEM kernel
        // demoting the thread under thermal/power pressure. Log on
        // transition only — once per demotion, once per recovery — so we
        // don't flood the 50-slot event ring at ~50 buffers/sec. Counter
        // bumps once per demotion event so the diagnostics screen can show
        // the cumulative count at a glance.
        if (priority != Int.MIN_VALUE) {
            val isWrong = priority >= 0
            if (isWrong && !priorityDemotionLogged) {
                priorityDemotionLogged = true
                priorityDemotions.incrementAndGet()
                // Active recovery: try to push the priority back up to
                // THREAD_PRIORITY_AUDIO before logging. This is a free
                // mitigation when the demotion is a pure nice-value issue
                // (e.g. Media3 didn't elevate the thread post-recreate). It
                // will NOT fix OEM cgroup migrations — those cap CPU at the
                // cpuset/cpu group level regardless of thread nice. We log
                // the attempted-priority value alongside so the diagnostics
                // dump shows what we tried.
                val reelevated = try {
                    android.os.Process.setThreadPriority(
                        tid, android.os.Process.THREAD_PRIORITY_AUDIO
                    )
                    val readback = try {
                        android.os.Process.getThreadPriority(tid)
                    } catch (_: Throwable) { priority }
                    audioThreadPriority = readback
                    readback < 0
                } catch (_: Throwable) {
                    false
                }
                logEvent(
                    "priority_demoted",
                    "tid=$tid priority=$priority " +
                        (if (reelevated) "(re-elevated to AUDIO)" else "(re-elevation failed; likely cgroup-bound)")
                )
            } else if (!isWrong && priorityDemotionLogged) {
                priorityDemotionLogged = false
                logEvent(
                    "priority_recovered",
                    "tid=$tid priority=$priority"
                )
            }
        }

        // Performance hint: target = wall-clock budget for this buffer's
        // worth of audio, ADJUSTED for playback speed. audioNs is the audio
        // duration the buffer represents; at speed=2.0 the audio thread has
        // only audioNs/2 of wall-clock to finish before the next buffer is
        // due. Without this division the OS gets a deadline twice as
        // generous as reality at 2× and downclocks the CPU mid-buffer,
        // producing the screen-off chop. On API <31 or when the service is
        // unavailable, both calls no-op cheaply.
        perfHint?.let { bridge ->
            val speed = playbackSpeed
            val targetNs = if (speed > 0f && speed != 1.0f) {
                (audioNs.toDouble() / speed).toLong()
            } else {
                audioNs
            }
            bridge.ensureSession(tid, targetNs)
            bridge.reportActual(processingNs)
        }
    }

    /** Aggregated buffer-timing snapshot for the diagnostics screen. */
    data class BufferTimingStats(
        val sampleCount: Int,
        val avgProcessingNs: Long,
        val p95ProcessingNs: Long,
        val maxProcessingNs: Long,
        val avgLoadFactor: Double,
        val maxLoadFactor: Double,
    )

    /**
     * Aggregate stats over the last ~[BUFFER_TIMING_RING_SIZE] buffers. Called
     * only from the UI thread on the diagnostics screen's 250 ms tick —
     * allocates a small copy + sort, never runs on the audio thread.
     */
    fun computeBufferTimingStats(): BufferTimingStats {
        synchronized(recentBufferLock) {
            val n = recentBufferCount
            if (n == 0) return BufferTimingStats(0, 0L, 0L, 0L, 0.0, 0.0)
            val proc = LongArray(n)
            val audio = LongArray(n)
            for (i in 0 until n) {
                val src = (recentBufferPos - 1 - i + BUFFER_TIMING_RING_SIZE) % BUFFER_TIMING_RING_SIZE
                proc[i] = recentBufferProcessingNs[src]
                audio[i] = recentBufferAudioNs[src]
            }
            val sortedProc = proc.copyOf().apply { sort() }
            val sumProc = sortedProc.sum()
            var sumLoad = 0.0
            var maxLoad = 0.0
            for (i in 0 until n) {
                val a = audio[i]
                if (a > 0) {
                    val load = proc[i].toDouble() / a
                    sumLoad += load
                    if (load > maxLoad) maxLoad = load
                }
            }
            val p95Idx = ((n - 1) * 0.95).toInt().coerceIn(0, n - 1)
            return BufferTimingStats(
                sampleCount = n,
                avgProcessingNs = sumProc / n,
                p95ProcessingNs = sortedProc[p95Idx],
                maxProcessingNs = sortedProc.last(),
                avgLoadFactor = sumLoad / n,
                maxLoadFactor = maxLoad,
            )
        }
    }

    // ---- Event log (recent breadcrumbs) ----
    /** One captured event in the ring buffer. */
    data class Event(val timestampMs: Long, val kind: String, val detail: String)

    private const val EVENT_CAPACITY = 50
    private val eventBuffer = arrayOfNulls<Event>(EVENT_CAPACITY)
    private var eventWritePos = 0
    private var eventTotal = 0
    private val eventLock = Any()

    /** Append a one-line event. Safe to call from any thread. Cheap enough
     *  for the cold paths (configure/flush/preset change) but should NOT be
     *  used in the per-frame hot loop. */
    fun logEvent(kind: String, detail: String = "") {
        synchronized(eventLock) {
            eventBuffer[eventWritePos % EVENT_CAPACITY] =
                Event(System.currentTimeMillis(), kind, detail)
            eventWritePos++
            eventTotal++
        }
    }

    /** Snapshot the event log, most-recent first. UI thread only. */
    fun snapshotEvents(): List<Event> {
        synchronized(eventLock) {
            val count = minOf(eventTotal, EVENT_CAPACITY)
            if (count == 0) return emptyList()
            val out = ArrayList<Event>(count)
            // Walk backwards from the most recent slot.
            for (i in 0 until count) {
                val pos = ((eventWritePos - 1 - i) % EVENT_CAPACITY + EVENT_CAPACITY) % EVENT_CAPACITY
                eventBuffer[pos]?.let { out.add(it) }
            }
            return out
        }
    }

    /** Reset all counters and clear the event log. Live readouts (which are
     *  overwritten continuously by the audio thread) are left alone. UI
     *  thread only — used by the diagnostic screen's "Reset" affordance. */
    fun resetCountersAndEvents() {
        configures.set(0)
        flushes.set(0)
        crossFades.set(0)
        bandChanges.set(0)
        drains.set(0)
        passthroughBuffers.set(0)
        dspBuffers.set(0)
        framesProcessed.set(0)
        priorityDemotions.set(0)
        priorityDemotionLogged = false
        maxBufferProcessingNs = 0L
        synchronized(eventLock) {
            eventBuffer.fill(null)
            eventWritePos = 0
            eventTotal = 0
        }
        synchronized(recentBufferLock) {
            recentBufferPos = 0
            recentBufferCount = 0
        }
    }
}
