package com.lofipod.app.audio

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
        synchronized(eventLock) {
            eventBuffer.fill(null)
            eventWritePos = 0
            eventTotal = 0
        }
    }
}
