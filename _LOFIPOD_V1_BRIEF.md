# LofiPod v1.0.0 — Audio Subsystem Technical Brief

Target paths assumed (adjust if your tree differs):
- `app/src/main/java/com/lofipod/app/audio/LinearPhaseEq.kt`
- `app/src/main/java/com/lofipod/app/audio/EqAudioProcessor.kt`
- `app/src/main/java/com/lofipod/app/audio/EqRenderersFactory.kt`
- `app/src/main/java/com/lofipod/app/audio/Oversampler.kt`, `Limiter.kt`, `DcBlocker.kt`
- `app/src/main/java/com/lofipod/app/player/PlayerController.kt`
- `app/src/main/java/com/lofipod/app/download/LofiPodDownloader.kt`

Conventions: `// PATCH:` marks the recommended drop-in change. Pseudocode shows intent; the integrating agent must adapt method signatures and add imports.

---

## A. ROOT-CAUSE TAXONOMY — LINEAR-PHASE ARTIFACTS

### A1. ArrayDeque<Double> autoboxing → GC pressure on the audio thread

**Mechanism.** Kotlin's `kotlin.collections.ArrayDeque<Double>` (and the JDK `java.util.ArrayDeque<Double>`) is a generic container whose element storage is `Object[]`. Every `addLast(value: Double)` invokes `Double.valueOf(double)` (autoboxing), producing a heap `java.lang.Double` object; every `removeFirst()` returns a boxed `Double` that is then unboxed at the call site. The Kotlin stdlib documents that generic collections force boxing of primitives; the recommended primitive containers are `DoubleArray`/`IntArray`.

**Quantification at 44.1 kHz, FRAME_SIZE=1024, stereo, FIR output queue:**
- Per audio chunk (`23.22 ms` wall-clock): up to `FRAME_SIZE × channels = 2048` `addLast` calls plus the same number of `removeFirst()` calls when draining. Even counting one direction only (current spec says per-channel `outputQueue: Array<ArrayDeque<Double>>`): **≥ 2048 boxed Double allocations per 23.22 ms ≈ 88,200 boxed allocations / second**.
- 88,200 × ~16 B (boxed `Double` header + payload on ART 64-bit) ≈ **1.4 MB/s of short-lived heap garbage on the audio thread**, sustained.
- ART's concurrent copying GC (Android 8+) is generational and usually fast, but the audio thread is the producer; any allocation slow path (TLAB refill), GC barrier hit, or background GC concurrent-mark phase can stretch a `queueInput` past its ~23 ms deadline. The AudioTrack ring (1.5–3.0 s) absorbs occasional spikes; sustained pressure produces **periodic clicks/pops correlated with GC activity**, worse on long sessions and under memory pressure.

**Symptom signature.** Sporadic clicks every 5–30 s on long playback, more frequent during memory pressure. `AudioChainTelemetry` ring shows occasional `processingNs` spikes 5–15× the median. **No correlation with band-slider activity** (which would point to A2 instead).

**Severity.** HIGH — most likely dominant cause of the "linear-phase audio issues" symptom.

**Code locus.** `LinearPhaseEq.kt`: `outputQueue: Array<ArrayDeque<Double>>`, `pushFrame`, `popFrame`, `outputFramesAvailable()`. Any path calling `outputQueue[ch].addLast(value)` or `removeFirst()`.

**Recommended fix.** Replace per-channel `ArrayDeque<Double>` with a primitive ring buffer over `DoubleArray`. Two-pointer head/tail with power-of-two capacity for cheap modular indexing.

```kotlin
// PATCH: LinearPhaseEq.kt — replace ArrayDeque<Double> with DoubleRing.
internal class DoubleRing(capacityPow2: Int) {
    init { require(capacityPow2 > 0 && (capacityPow2 and (capacityPow2 - 1)) == 0) }
    private val buf = DoubleArray(capacityPow2)
    private val mask = capacityPow2 - 1
    private var head = 0   // read index
    private var tail = 0   // write index
    var size = 0; private set

    fun push(v: Double) {
        buf[tail] = v
        tail = (tail + 1) and mask
        size++
    }
    fun pop(): Double {
        val v = buf[head]
        head = (head + 1) and mask
        size--
        return v
    }
    fun pushBulk(src: DoubleArray, srcOff: Int, n: Int) {
        var i = 0
        while (i < n) {
            buf[tail] = src[srcOff + i]
            tail = (tail + 1) and mask
            i++
        }
        size += n
    }
    fun popBulkInto(dst: ShortArray, dstOff: Int, n: Int, scale: Double) {
        var i = 0
        while (i < n) {
            val s = buf[head] * scale
            val q = if (s >= 32767.0) 32767 else if (s <= -32768.0) -32768 else s.toInt()
            dst[dstOff + i] = q.toShort()
            head = (head + 1) and mask
            i++
        }
        size -= n
    }
    fun clear() { head = 0; tail = 0; size = 0 }
}
```

Capacity sizing: largest expected residency = one output chunk + `KERNEL_LENGTH-1 = 4095` tail samples + safety. Pick `8192`. Per channel ≈ 64 KB. Negligible vs 19 MB load-control buffer.

**Even better: skip the queue entirely.** The OLA scheme already produces `FRAME_SIZE` valid output samples per processed chunk into `ws[0..FRAME_SIZE-1] + pending[0..FRAME_SIZE-1]`. Emit directly into the output `ByteBuffer` from `replaceOutputBuffer(...)` in one tight loop over `DoubleArray`. Eliminates queue AND boxing.

```kotlin
// PATCH (sketch) inside EqAudioProcessor.queueInputLinearPhase:
val out = replaceOutputBuffer(framesToEmit * channels * 2)  // 16-bit
val outShorts = out.asShortBuffer()
for (n in 0 until framesToEmit) {
    for (ch in 0 until channels) {
        val s = linearPhaseEq.popSample(ch)         // returns Double, no boxing
        val q = (s * 32768.0).toInt().coerceIn(-32768, 32767)
        outShorts.put(n * channels + ch, q.toShort())
    }
}
out.position(out.position() + framesToEmit * channels * 2)
out.flip()
```

If you must keep a queue (for output-rate decoupling from input), use `DoubleRing` above.

---

### A2. Mid-stream kernel-swap mismatch (`@Volatile` swap + stale OLA tail)

**Mechanism.** On `setBands`, the worker rebuilds `kernelSpectrum` and publishes via a single `@Volatile` reference swap. The audio thread reads the new spectrum on the next chunk, but `pending[0..tailLen-1]` (length `KERNEL_LENGTH-1 = 4095`) still contains the OLA tail of the *previous* kernel's convolution. The next chunk's output is:

```
out[n] = conv(in_now, K_new)[n] + tail_from(K_old)[n], n=0..FRAME_SIZE-1
```

This is **not** a valid convolution of any single LTI filter — it is a time-varying mix of two convolutions. The tail decays linearly over `ceil(tailLen / FRAME_SIZE) = ceil(4095/1024) = 4` chunks ≈ 93 ms at 44.1 kHz. Audible: soft chuff to click depending on slider delta.

The min-phase path is protected by its ~46 ms biquad cross-fade. The linear-phase path has nothing.

**Symptom signature.** Click/chuff/ring aligned with each band-slider tick in linear mode. Reproducible with white noise + slider drag. Not correlated with playback content.

**Severity.** MEDIUM.

**Code locus.** `LinearPhaseEq.kt`: `setBands(...)` synth job → `kernelSpectrum = newSpec`; `processChunk(...)` reads it.

**Recommended fix (preferred): parallel cross-fade over N chunks.** Keep two kernels live (`pathA`, `pathB`) and run two parallel convolutions for N chunks (N=4–8, i.e. 93–186 ms). Linear cross-fade output α from 0 → 1. After fade, drop A, atomically swap B→A, set `pathB = null`. Doubles per-chunk FFT cost during the fade window only.

```kotlin
// PATCH: LinearPhaseEq.kt — dual-path crossfade.
private class ConvPath(val kernelSpec: DoubleArray) {
    val pending = Array(channels) { DoubleArray(KERNEL_LENGTH - 1) }
    val ws = Array(channels) { DoubleArray(FFT_SIZE) }
}
@Volatile private var pathA: ConvPath = ConvPath(initialKernel)
@Volatile private var pathB: ConvPath? = null
private var fadeChunksRemaining = 0
private val FADE_CHUNKS = 4    // ~93 ms

fun publishKernel(newSpec: DoubleArray) {
    val nb = ConvPath(newSpec)
    // Optional: prime pathB.pending by feeding it the last KERNEL_LENGTH-1
    // input samples captured in a shadow buffer so its tail aligns with current audio.
    pathB = nb
    fadeChunksRemaining = FADE_CHUNKS
}

fun processChunk(inputCh: Array<DoubleArray>, outCh: Array<DoubleArray>) {
    val a = pathA
    val b = pathB
    convolveInto(a, inputCh, scratchA)
    if (b != null && fadeChunksRemaining > 0) {
        convolveInto(b, inputCh, scratchB)
        val fadeIdx = FADE_CHUNKS - fadeChunksRemaining   // 0..N-1
        for (ch in 0 until channels) {
            for (n in 0 until FRAME_SIZE) {
                val alpha0 = (fadeIdx.toDouble() + n.toDouble() / FRAME_SIZE) / FADE_CHUNKS
                outCh[ch][n] = (1 - alpha0) * scratchA[ch][n] + alpha0 * scratchB[ch][n]
            }
        }
        fadeChunksRemaining--
        if (fadeChunksRemaining == 0) { pathA = b; pathB = null }
    } else {
        for (ch in 0 until channels) System.arraycopy(scratchA[ch], 0, outCh[ch], 0, FRAME_SIZE)
    }
}
```

**Cheap fallback.** Zero-out `pending[ch][]` on publish + apply a 256-sample raised-cosine ramp to the first chunk's output. Cost: ~5.8 ms of attenuation. Audible "soft mute" on every tick — acceptable if slider is debounced (≥150 ms quiet period before publish).

**Long-term correct.** Switch to partitioned convolution (§B). With UPC, the FDL holds input history, not kernel-convolved history, so swapping kernel partition spectra atomically yields a clean LTI switch with a single-block boundary trivially masked by a small linear ramp.

---

### A3. CPU envelope at 2× playback; per-channel sequential FFTs

**Mechanism.** Per chunk (23.22 ms at 1×; **11.61 ms at 2×**) on the audio thread:
- 2× JTransforms `realForward(8192)` + 2× packed complex multiply (≈4097 muls/adds) + 2× `realInverse(8192, true)`. JTransforms is pure-Java, no NEON. Empirically an 8192-pt real FFT in JTransforms on a Cortex-A55 (1.8 GHz) typically takes **400–900 µs single-threaded**; on Cortex-A53 (~1.4 GHz) often **1.0–1.8 ms**. NEON-optimized libs (PFFFT, Ne10) run **3–5× faster** at this size.
- 2× Oversampler 128-tap FIR at 1× then 2× at 2× rate (~500 k MACs / chunk).
- 2× Limiter monotonic-deque windowed-max at 2× rate (cheap).
- DC blocker, gain, dither (negligible).

**Order-of-magnitude per-chunk DSP budget on mid-range A55 at 2× playback:**
- FIR FFT/IFFT: **1.0–2.0 ms**
- Oversampler: **0.3–0.6 ms**
- Other: **0.1–0.3 ms**
- Total median: **1.4–2.9 ms vs 11.6 ms budget**. Comfortable median, **but p99 spikes from JIT warmup, A1's GC, thermal throttling, or co-scheduled UI work easily blow the budget**. The AudioTrack ring masks underruns *partly because* of A1's contribution. Remove A1 and headroom is fine on modern devices; on low-end A53 it stays tight.

**Symptom signature.** Stuttering only at 2× with linear-phase ON; combined with A1, produces "running rough" character.

**Severity.** MEDIUM (interacts with A1).

**Code locus.** `LinearPhaseEq.kt` `processChunk`, `Oversampler.kt`, `Limiter.kt`. `EqRenderersFactory.kt` (chain wiring).

**Mitigations, ranked impact/cost:**

1. **(S, large win) Off-thread FFT pipeline via SPSC ring.** Move L+R `realForward → multiply → realInverse` off the audio thread into a single dedicated DSP worker bound to `THREAD_PRIORITY_AUDIO`. Audio thread → input SPSC ring; DSP worker → output SPSC ring. Adds one block of latency (~23 ms) that is already lost inside the 46 ms FIR group delay; audio thread sheds 60–90% of its DSP load.

   ```kotlin
   // PATCH (sketch): a non-blocking SPSC double ring.
   class SpscDoubleRing(capacityPow2: Int) {
       private val buf = DoubleArray(capacityPow2)
       private val mask = capacityPow2 - 1
       @Volatile private var head = 0  // single consumer
       @Volatile private var tail = 0  // single producer
       fun availableRead(): Int { val t = tail; val h = head; return (t - h) and mask }
       fun availableWrite(cap: Int): Int = cap - 1 - availableRead()
       fun write(src: DoubleArray, off: Int, n: Int): Int { /* memcpy in two segments */ }
       fun read(dst: DoubleArray, off: Int, n: Int): Int { /* memcpy in two segments */ }
   }
   ```

   Worker blocks on `LockSupport.parkNanos()` budget ~ `bufferForPlaybackMs / 4`. Use `Process.THREAD_PRIORITY_AUDIO` and call `PerformanceHintBridge.ensureSession(threadId=dspWorker.id, ...)` for it too.

2. **(Don't do this) Parallelize L/R via `Dispatchers.Default`.** Default scheduler isn't real-time; scheduling jitter > serial cost. Use the dedicated thread from (1).

3. **(M) Replace JTransforms with PFFFT via JNI.** See A5. ~3–5× FFT speedup. CPU headroom at 2× roughly doubles.

4. **(M) Switch to partitioned convolution.** See §B. At P=4 / FFT_SIZE=2048, per-block FFT cost drops ~3× net.

---

### A4. Position reporting does not account for FIR group delay

**Mechanism.** Audible output lags the frames `DefaultAudioSink` last wrote to AudioTrack by `≈46 ms` (linear-phase) or `≈6.4 ms` (min-phase). `MediaController.currentPosition` is derived from `AudioTrack.getPlaybackHeadPosition()` plus the sink's pending PCM, so the sink correctly accounts for the AudioTrack ring — but the Media3 chain does NOT subtract per-`AudioProcessor` latency unless each processor reports it (e.g., Sonic reports its duration scaling via `getMediaDuration`). Your custom processors do not. The progress bar runs ahead of audible by the algorithmic group delay.

**Symptom signature.** Progress bar 40+ ms ahead of audible in linear-phase. Seeks land an EQ-tail before the intended sample.

**Severity.** LOW (correctness/UX), MEDIUM if you ever add chapter markers or precise scrubbing.

**Watchdog interaction.** 46 ms is 5 orders of magnitude below the 6 s stall threshold. **Do not subtract chain latency from the watchdog input** — it tracks raw forward progress.

**Code locus.** `EqAudioProcessor.kt`, `PlayerController.kt`.

```kotlin
// PATCH: EqAudioProcessor.kt
fun getChainLatencyUs(): Long {
    val linUs = if (phaseModeLinear && active)
        ((KERNEL_LENGTH - 1) / 2) * 1_000_000L / sampleRate   // ≈ 46_440 µs @44.1k
    else 0L
    val osUs = ((OVERSAMPLER_TAPS - 1) / 2) * 1_000_000L / sampleRate   // ≈ 1_440 µs
    val limUs = 5_000L     // 5 ms look-ahead
    return linUs + osUs + limUs
}

// PATCH: PlayerController.kt
val displayPositionMs: Long
    get() {
        val raw = controller.currentPosition
        val latMs = eqProc.getChainLatencyUs() / 1000L
        return (raw - latMs).coerceAtLeast(0L)
    }
// Use displayPositionMs for UI; keep `controller.currentPosition` for the watchdog.

// On seek, compensate so audible lands at requested time:
fun seekTo(uiPosMs: Long) {
    controller.seekTo(uiPosMs + eqProc.getChainLatencyUs() / 1000L)
}
```

MP3 frame boundaries (1152 samples each → 26.1 ms at 44.1 kHz) are roughly the seek-precision floor anyway, so 46 ms compensation is within ~2 frames — acceptable.

---

### A5. JTransforms 3.1 (Jan 2018, unmaintained) → alternatives

**Status.** JTransforms latest published is 3.1; repo `wendykierp/JTransforms` shows a `3.2` Maven coord but the project is essentially dormant. License: BSD-2-Clause / MPL 1.1 / LGPL (tri-license — BSD-2 path is clean for a sideloaded app). Pure-JVM, no JNI.

**Candidates:**

| Lib | License | JNI burden | Speedup vs JTransforms (8192 real FFT) | Verdict |
|---|---|---|---|---|
| **PFFFT** (Pommier) / `marton78/pffft` fork | BSD-3 | Yes — small (~3000 LoC C). NDK build for arm64-v8a + armeabi-v7a (+ x86_64 if needed) | **~3–5×** on ARM (NEON). Competitive with FFTW for small/medium sizes. | **PRIMARY recommendation for v0.9.5.** AOSP ships PFFFT (`platform/external/pffft`), so it's battle-tested on Android. Single-precision float — adapt the 4096-tap kernel; no audible quality loss. PFFFT exposes `pffft_zconvolve_accumulate` — the canonical UPC kernel, avoids writing your own packed-complex multiply. |
| **Ne10** (Arm) | Apache 2.0 | Yes — larger (~50k LoC, but FFT-only subset is compact). Arm-only (no x86_64). | **~3–6×** on ARM. Edge over PFFFT at radix-3/5. | Excellent if dropping x86_64 (emulator-only on a sideloaded app — fine). |
| **KissFFT** (mborgerding) | BSD-3 | Yes — tiny (~1000 LoC). No SIMD. | ~1.0–1.5× (JTransforms is decent pure Java). | Not worth the JNI overhead vs PFFFT/Ne10. |
| **Stay on JTransforms 3.1** | BSD-2 | None | baseline | Acceptable for v0.9.0–v0.9.4 if A1 + A2 + off-thread DSP worker are done. Migrate in v0.9.5. |

**For v0.9.0–v0.9.4**: stay on JTransforms. The dominant bottleneck after A1 is not the FFT — it's autobox GC. Defer FFT replacement to v0.9.5.

**Migration to PFFFT checklist:**
- Add an NDK module (`externalNativeBuild { cmake { ... } }`) bundling PFFFT.
- ABIs: `arm64-v8a`, `armeabi-v7a`. Skip `x86_64` unless you need emulator FFT.
- BSD-3 attribution: `app/src/main/assets/licenses/LICENSE-PFFFT.txt` + About screen credit.
- R8 stays OFF (per project notes, v0.3.0 reflection breakage). Native libs unaffected.

---

### A6. Synchronous `synthesizeKernelSync(FLAT)` in `configure()` blocks audio thread

**Mechanism.** Media3 calls `AudioProcessor.configure(AudioFormat)` whenever the input format changes — initial track start AND every time the sink reconfigures after `setMediaItem`/`prepare`. Current impl synchronously synthesizes FLAT (~2–3 ms warm A55, 8–15 ms cold A53). Per `AudioProcessor` Javadoc, `configure` is followed by `flush()` before any new `queueInput`; the spec says explicitly "After calling [configure], it is necessary to flush the processor to apply the new configuration." This work happens synchronously before the first buffer of the new stream → directly contributes to the handoff glitch (§D).

**Symptom signature.** Tiny first-buffer delay on track start; bigger glitch on streaming→downloaded handoff because that path triggers a fresh `configure()`.

**Severity.** LOW alone, HIGH combined with §D.

**Code locus.** `LinearPhaseEq.kt`: `configure(rate, channels)` → `synthesizeKernelSync(EqPresets.FLAT)`.

**Fix.** Precompute flat kernels at companion-object init for 44.1 and 48 kHz. Unknown rates → mark `passthrough=true`, schedule synth on `workerScope`, flip when ready.

```kotlin
// PATCH: LinearPhaseEq.kt
companion object {
    private val FLAT_SPECS: Map<Int, DoubleArray> by lazy {
        mapOf(
            44100 to buildFlatSpec(44100),
            48000 to buildFlatSpec(48000)
        )
    }
    private fun buildFlatSpec(rate: Int): DoubleArray = /* current synthesizeKernelSync body */
}

override fun configure(rate: Int, channels: Int) {
    val cached = FLAT_SPECS[rate]
    if (cached != null) {
        kernelSpectrum = cached
        passthrough = false
    } else {
        kernelSpectrum = null
        passthrough = true
        workerScope.launch {
            val k = buildFlatSpec(rate)
            kernelSpectrum = k
            passthrough = false
        }
    }
    resetState()
}
```

---

### A7. `recordBufferTiming` vs output-frame count divergence

**Audit.** ADPF (`PerformanceHintManager.Session.reportActualWorkDuration(actualDurationNanos)` paired with `updateTargetWorkDuration(targetNanos)`) reports the wall-clock time to produce one buffer of output against an expected per-frame budget. For audio rendering, the meaningful budget is `framesQueuedThisCall × 1e9 / sampleRate / playbackSpeed`. Spec uses `audioNs / playbackSpeed` with `audioNs` derived from input `frameCount`. In linear-phase mode, *output* frame count per `queueInput` call can be 0 (still filling accumulator) or 1024+ (one OLA chunk emitted). Wall-clock budget tracks *input* because that's what the sink hands you per call — sustainable output rate cannot exceed input-frames/rate/speed.

**Conclusion: current `audioNs = frameCount`-based is correct in aggregate.** Edge cases:
- `queueInput` emits 0 output frames: tiny `processingNs` reported against non-trivial `audioNs`. Harmless — hint manager averages over its window.
- `queueInput` emits 2× output (drain): `processingNs` includes 2× FFT but `audioNs` reflects only input consumed. Slight over-report → governor stays warm. Functionally fine.
- Do NOT switch to output-based: under-reports during fill and over-reports during drain in the opposite pattern.

Add a millisecond floor on `audioNs` to avoid divide-by-near-zero in any downstream smoothing:

```kotlin
// PATCH: AudioChainTelemetry.kt or wherever recordBufferTiming lives
// audioNs is intentionally derived from INPUT frameCount, not output,
// because the per-call wall-clock budget = the input chunk's playback duration.
val audioNsClamped = audioNs.coerceAtLeast(1_000_000L)  // 1 ms floor
hintBridge.reportActual((audioNsClamped / playbackSpeed).toLong())
```

---

### A8. Mode-switch reset must flush limiter + oversampler state

**Mechanism.** `setPhaseModeLinear(on)` calls only `linearPhaseEq.reset()`. The look-ahead limiter's monotonic-deque holds samples from the **post-EQ** signal — toggling linear↔min-phase mid-playback leaves stale samples in the deque, briefly producing a wrong windowed-max envelope. Same for the oversampler's polyphase delay lines.

Even more important: **Media3 calls `AudioProcessor.flush()` on seek, on `setMediaItem`, and on format changes.** Your current `flush()` likely does not clear stateful stages. Stale state from the previous track audibly bleeds into the new one — a contributor to the handoff symptoms (§D).

**Symptom signature.** Click or brief level burst when toggling phase mode mid-playback; click immediately after seek or after `setMediaItem` (with or without EQ engaged).

**Severity.** LOW alone, MEDIUM as a contributor to handoff symptoms.

**Code locus.** `EqAudioProcessor.kt`: `setPhaseModeLinear`, `flush`, `reset`.

```kotlin
// PATCH: EqAudioProcessor.kt
private fun chainReset() {
    dcBlocker.reset()
    biquadCascade.resetState()
    linearPhaseEq.reset()        // pending + inputAccum + outputQueue
    oversampler.resetState()     // up- and down-polyphase delay lines
    limiter.resetState()         // monotonic-deque + envelope + look-ahead buffer
}

fun setPhaseModeLinear(on: Boolean) {
    phaseModeLinear = on
    chainReset()
}

override fun flush() {
    super.flush()
    chainReset()
}
```

`flush()` is the critical one for §D: it's called by `DefaultAudioSink` between media items and on seeks.

---

## B. PARTITIONED CONVOLUTION

### B1. Uniform partitioned convolution (UPC) — algorithm

Kernel of length `L_k = 4096` split into `P` partitions of `L = 1024` (P=4). Each partition `K_p[0..L-1]` is zero-padded to `FFT_SIZE_p = 2L = 2048` and stored as real-packed spectrum `K_p^F[0..L]`. Per input block of L samples:

1. Build 2L overlap: `[prev_input_L | current_input_L]`.
2. Single FFT: `X_t^F = FFT_2L(overlap)`.
3. Push `X_t^F` into front of **frequency-domain delay line** (FDL), a circular array of P spectra.
4. Sum: `Y^F[k] = Σ_{p=0..P-1} FDL[p][k] · K_p^F[k]` ∀ bin k.
5. Single IFFT: `y = IFFT(Y^F)`. Output = `y[L..2L-1]` (overlap-save form).
6. Algorithmic latency = `L` samples = ~23 ms at 1024@44.1k.

**Complexity per output block.** 1 forward + 1 inverse FFT at size 2L, plus P complex-multiply-accumulates of (L+1) bins. Per sample: `O(log L + P/2)`. Current monolithic OLA: 1 fwd + 1 inv at FFT_SIZE=8192. UPC at P=4 / 2L=2048 is **~3× cheaper** (FFT cost is `N log N`; the 8192→2048 reduction outweighs the P=4 multiply-add overhead).

References: Frank Wefers, "Partitioned convolution algorithms for real-time auralization" (RWTH Aachen, 2015) — definitive treatment. Gardner (1995) "Efficient convolution without input/output delay." García (2003). The Wefers thesis demonstrates `FFT_SIZE = 2 × block_length` is the optimal default; deviating only pays for very long kernels.

### B2. Non-uniform (NUPC / Gardner)

Small initial partition (e.g. 64) for low latency, doubling tail partitions for efficiency. **Overkill for a podcast app** where 46 ms is fine. Document and skip.

### B3. Kotlin pseudocode for UPC drop-in replacement

```kotlin
// LinearPhaseUPC.kt — replacement for LinearPhaseEq.kt
class LinearPhaseUPC(
    private val sampleRate: Int,
    private val channels: Int,
    private val blockSize: Int = 1024,           // L
    private val kernelLen: Int = 4096            // L_k
) {
    private val fftSize = 2 * blockSize           // 2L = 2048
    private val numPartitions = kernelLen / blockSize   // P = 4
    private val fft = Array(channels) { DoubleFFT_1D(fftSize.toLong()) }

    @Volatile private var kernelPartSpec: Array<DoubleArray> =
        Array(numPartitions) { DoubleArray(fftSize) }

    // Per-channel state
    private val fdl: Array<Array<DoubleArray>> =     // [ch][P][fftSize]
        Array(channels) { Array(numPartitions) { DoubleArray(fftSize) } }
    private val fdlHead = IntArray(channels)
    private val prevInput = Array(channels) { DoubleArray(blockSize) }
    private val workspace = Array(channels) { DoubleArray(fftSize) }
    private val accumSpec = Array(channels) { DoubleArray(fftSize) }

    fun setKernelTaps(taps: DoubleArray) {
        // Worker thread; partition + FFT, then @Volatile publish.
        val newParts = Array(numPartitions) { p ->
            val ws = DoubleArray(fftSize)
            val srcOff = p * blockSize
            val n = min(blockSize, taps.size - srcOff)
            if (n > 0) System.arraycopy(taps, srcOff, ws, 0, n)
            DoubleFFT_1D(fftSize.toLong()).realForward(ws)   // own FFT instance per worker
            ws
        }
        kernelPartSpec = newParts
    }

    fun processBlock(input: Array<DoubleArray>, output: Array<DoubleArray>) {
        val parts = kernelPartSpec
        for (ch in 0 until channels) {
            val ws = workspace[ch]
            // overlap-save 2L input
            System.arraycopy(prevInput[ch], 0, ws, 0, blockSize)
            System.arraycopy(input[ch], 0, ws, blockSize, blockSize)
            System.arraycopy(input[ch], 0, prevInput[ch], 0, blockSize)
            fft[ch].realForward(ws)
            val head = fdlHead[ch]
            System.arraycopy(ws, 0, fdl[ch][head], 0, fftSize)
            val acc = accumSpec[ch]
            java.util.Arrays.fill(acc, 0.0)
            for (p in 0 until numPartitions) {
                val idx = ((head - p) % numPartitions + numPartitions) % numPartitions
                packedComplexMultiplyAccumulate(fdl[ch][idx], parts[p], acc, fftSize)
            }
            fdlHead[ch] = (head + 1) % numPartitions
            fft[ch].realInverse(acc, true)
            System.arraycopy(acc, blockSize, output[ch], 0, blockSize)
        }
    }

    // JTransforms real-FFT packed layout:
    // spec[0]=Re[0], spec[1]=Re[N/2], spec[2k]=Re[k], spec[2k+1]=Im[k] for k=1..N/2-1
    private fun packedComplexMultiplyAccumulate(
        a: DoubleArray, b: DoubleArray, dst: DoubleArray, n: Int
    ) {
        dst[0] += a[0] * b[0]                  // DC
        dst[1] += a[1] * b[1]                  // Nyquist
        val half = n / 2
        var k = 1
        while (k < half) {
            val aRe = a[2 * k]; val aIm = a[2 * k + 1]
            val bRe = b[2 * k]; val bIm = b[2 * k + 1]
            dst[2 * k]     += aRe * bRe - aIm * bIm
            dst[2 * k + 1] += aRe * bIm + aIm * bRe
            k++
        }
    }

    fun reset() {
        for (ch in 0 until channels) {
            for (p in 0 until numPartitions) java.util.Arrays.fill(fdl[ch][p], 0.0)
            java.util.Arrays.fill(prevInput[ch], 0.0)
            fdlHead[ch] = 0
        }
    }
}
```

### B4. Latency tradeoffs

- UPC algorithmic latency = `blockSize` samples (NOT `kernel_len/2` like a symmetric-FIR group delay). For blockSize=1024 @44.1k = **23 ms**. Half the linear-phase latency for free.
- **BUT** linear-phase property comes from kernel symmetry — UPC just convolves with whatever kernel you give it. Keep the symmetric kernel and the *audible* group delay remains 46 ms (kernel center at tap 2047). To realize 23 ms, pair UPC with a **minimum-phase kernel** (§C1). That combination — UPC + min-phase — gives low latency, low CPU, zero pre-ringing.
- Dropping blockSize to 512 (P=8) → ~12 ms latency; 256 (P=16) → ~6 ms. Per-block overhead grows but stays comfortable for a podcast app. Not necessary for v1.0.0/v1.1; overkill for spoken content.

### B5. Memory

For P=4, fftSize=2048, channels=2:
- Kernel partitions: `4 × 2048 × 8 B = 64 KB` (shared).
- Per-channel FDL: `4 × 2048 × 8 B = 64 KB` × 2 = **128 KB**.
- Workspace + accumulator + prev input: negligible.
- Total **~192 KB** vs current ~80 KB. Same order of magnitude. Acceptable.

---

## C. ALTERNATIVES TO LINEAR-PHASE EQ

### C1. Minimum-phase FIR (cepstrum-derived)

**Why.** For speech, transient preservation is irrelevant; symmetric-FIR pre-ringing is strictly bad (the smear before a transient is the famous linear-phase "cough that precedes itself"). Minimum-phase reshapes the same magnitude response with all energy after the transient. Zero pre-ringing. Group delay is non-constant but sub-millisecond at podcast EQ Qs (0.7–1.4) — inaudible.

**Algorithm (real-cepstrum, after Mian & Nainer 1982 / Pei & Lin 2005 / Oppenheim-Schafer §10.5).** Cheaper than complex-cepstrum; avoids phase unwrap.

```
Given target magnitude H_mag[k], k = 0..N/2

1. A[k] = log(max(|H_mag[k]|, eps)) on the symmetric extension
2. c_real[n] = IFFT(A)
3. Fold window:
   w[0]   = 1
   w[N/2] = 1   (N even)
   w[n]   = 2   for n = 1..N/2-1
   w[n]   = 0   for n = N/2+1..N-1
   c_min[n] = c_real[n] * w[n]
4. min_phase_spec = exp(FFT(c_min))   # complex, |·| = H_mag
5. h_min[n] = IFFT(min_phase_spec)    # real, causal, min-phase
6. Truncate to KERNEL_LENGTH (energy front-loaded; truncation error tiny)
7. Optional: Kaiser window (β=4–6) on truncation
```

**Latency.** With KERNEL_LENGTH=4096 inside a UPC block of L=1024, buffering latency stays ~23 ms (one block) but *group delay* becomes ~ms-level at all frequencies. User-visible position drift → ≤23 ms (vs 46 ms currently); pre-ringing artifacts on speech transients → gone.

**Implementation cost.** Two extra FFTs at synth time (worker thread). No audio-thread cost change.

```kotlin
// PATCH: FftEq.kt — minimum-phase kernel synthesis
private fun synthesizeMinPhaseKernel(magResp: DoubleArray, fftSize: Int): DoubleArray {
    require(magResp.size == fftSize / 2 + 1)
    val EPS = 1e-12
    val logSpec = DoubleArray(fftSize)
    logSpec[0] = ln(max(magResp[0], EPS))
    logSpec[1] = ln(max(magResp[fftSize / 2], EPS))
    for (k in 1 until fftSize / 2) {
        logSpec[2 * k]     = ln(max(magResp[k], EPS))
        logSpec[2 * k + 1] = 0.0
    }
    val fft = DoubleFFT_1D(fftSize.toLong())
    fft.realInverse(logSpec, true)         // c_real
    val cMin = DoubleArray(fftSize)
    cMin[0] = logSpec[0]
    cMin[fftSize / 2] = logSpec[fftSize / 2]
    for (n in 1 until fftSize / 2) cMin[n] = 2.0 * logSpec[n]
    fft.realForward(cMin)
    val minSpec = DoubleArray(fftSize)
    minSpec[0] = exp(cMin[0])
    minSpec[1] = exp(cMin[1])
    for (k in 1 until fftSize / 2) {
        val re = cMin[2 * k]; val im = cMin[2 * k + 1]
        val mag = exp(re)
        minSpec[2 * k]     = mag * cos(im)
        minSpec[2 * k + 1] = mag * sin(im)
    }
    fft.realInverse(minSpec, true)
    val out = DoubleArray(KERNEL_LENGTH)
    System.arraycopy(minSpec, 0, out, 0, KERNEL_LENGTH)
    applyKaiserWindow(out, beta = 6.0)
    return out
}
```

### C2. IIR biquad cascade (existing min-phase path)

Already in production. For speech, RBJ peaking biquads at Q ≈ 0.7–1.4 introduce ≤ 2 ms group-delay variation across bands — inaudible. **For v0.9.0–v0.9.3, hide linear-phase temporarily** and ship pure-IIR as the only phase mode, because linear-phase is the proximal cause of three of the four reported audio symptoms. **Linear-phase returns in v0.9.4 rebuilt on UPC + crossfade (item #10) and is joined by a new Min-Phase FIR mode (item #14)** — see §F for the full v1.0.0 four-mode lineup. The audience that notices linear-phase's temporary absence is the same audience that will read the release notes and recognize that the v0.9.4 rebuild is the genuine upgrade.

### C3. Warped-IIR

Bilinear / Laguerre frequency-warping for log-frequency biquads. Better Q accuracy in sub-bass. **Out of scope** for v1.0.0; adds warped-allpass cascade CPU and benefits a use case (precise sub-bass on speech) that doesn't matter here.

### C4. Mixed-phase / hybrid

Linear above some crossover, min-phase below — used in mastering EQs (FabFilter Pro-Q3 "Natural Phase", DMG EQuilibrium). **Promoted to v1.0.0 ship item #17 (Mixed-Phase mode); see §F.**

---

## D. STREAMING → DOWNLOADED HANDOFF FAILURES

### D1. Does `setMediaItem(item, posMs); prepare()` keep the audio sink alive?

**Behavior in Media3 1.4.x.** `setMediaItem` clears the current source; `prepare()` builds a new `MediaPeriod` and reads sample format. If PCM parameters (sample rate, channels, encoding) match the current `AudioTrack`, the sink reuses it. If they differ — even just sample rate — the sink calls `AudioProcessor.configure(newFormat)` on every processor, then `flush()`, then resumes. Javadoc on `androidx.media3.common.audio.AudioProcessor`: "After calling [configure], it is necessary to flush the processor to apply the new configuration."

**Implication.** Same-bytes handoff (the common case for LofiPod — same MP3 stream → same file → same 44.1k stereo PCM): the sink does NOT tear down. But `configure()` is still invoked on each processor with the same params → your `LinearPhaseEq.configure()` synchronously synthesizes the FLAT kernel (~2–15 ms cold), and `flush()` discards in-flight PCM. **Both contribute to the handoff glitch.** Fixes: A6 (precompute FLAT) + A8 (chainReset on flush).

If PCM params actually differ (rare for a same-source handoff), AudioTrack rebuilds. On API 31+ that adds 20–100 ms more. Media3 issue #2229 documents AudioTrack init failure modes with recoverable-error fallback — not your exact case but illustrates sink lifecycle is fragile.

### D2. Race: download-completion collector ↔ audio thread

`observeDownloadCompletion` runs on `PlayerController.scope = Dispatchers.Main.immediate`. `MediaController` methods must be called on the player's application Looper (= Main for a Session-injected controller). If a coroutine resumes on a non-Main dispatcher and then touches `controller`, you'll see `IllegalStateException: Player accessed from wrong thread`. Defensive: `withContext(Dispatchers.Main.immediate) { ... }` immediately before any controller call.

The audio thread (`ExoPlayer:Playback`) is **not** preempted mid-chunk by `setMediaItem` — Main posts a message to the playback Looper, which processes it after finishing the current `queueInput` call. **Not the race.**

The actual race is between:
1. `LofiPodDownloader` atomic rename `.tmp → .final` (IO).
2. `fsync` of file contents and (where supported) parent dir.
3. DB write marking COMPLETED (IO).
4. `byId` Flow emits → collector consumes (Main).
5. Main calls `controller.setMediaItem(fileUri, posMs)`.
6. Playback thread processes; `FileDataSource.open()` reads the file.

If steps are not strictly ordered with fsync barriers, `FileDataSource.open()` may see a not-yet-durable file. See §E4.

### D3. Buffer flush click on handoff

`setMediaItem` → `DefaultAudioSink.flush()` → `AudioTrack.flush()`. Your AudioTrack ring is configured for **1.5–3.0 s** of PCM. HAL behavior on `AudioTrack.flush()` varies: some hard-cut (click only), some play out residual samples (click then silence). ExoPlayer issue #6649 documents the related historic behavior that `setPlaybackParams()` does not flush pending samples (~1 s of old-params audio carries over) — not exactly your case but illustrates partial-flush quirks.

**Defensive handoff sequence:** explicit pause → drain → setMediaItem → prepare → play, with awaits between steps.

```kotlin
// PATCH: PlayerController.kt — replace current handoff body
suspend fun performHandoff(newItem: MediaItem, savedPosMs: Long) {
    if (handoffTriggeredForGuid.contains(currentGuid)) return
    handoffTriggeredForGuid.add(currentGuid)
    withContext(Dispatchers.Main.immediate) {
        playHandoffCue()                                    // existing double-beep
        controller.pause()
        awaitState { !controller.isPlaying }
        controller.setMediaItem(newItem, savedPosMs)
        controller.prepare()
        awaitState { controller.playbackState == Player.STATE_READY }
        controller.play()
    }
}
private suspend fun awaitState(timeoutMs: Long = 2000L, pred: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!pred() && SystemClock.elapsedRealtime() < deadline) delay(20)
}
```

**Strongly preferred for v0.9.0: drop mid-playback handoff entirely.** See D6.

### D4. Codec / container mismatches between streamed and downloaded source

If you download exactly the bytes you stream (`OkHttp` Range-resume on the same URL), encoded bytes are identical → no demuxer timestamp drift.

Risk: CDN returns subtly different bytes (dynamic ad insertion in MP3 prefix, ID3 strip/rewrite, server-side mutation). Then `Mp3Extractor` computes a different first-frame timestamp; `savedPosMs` lands on the wrong audio frame.

Media3 `Mp3Extractor` uses Xing/Info/VBRI/LAME headers for VBR seek tables. Media3 1.9.x release notes include "MP3: Don't stop playback early when a VBRI frame's table of contents doesn't cover all the MP3 data in a file" (#1904, fixed in 1.5.0+) — a direct candidate for an early-cut symptom on downloaded files. Older ExoPlayer fix history includes "Fix broken gapless MP3 playback on Samsung devices" (#8594) and LAME ReplayGain parsing fixes.

Mitigation: on download finalize, xxHash-compare the first 1024 bytes of the local file against the first 1024 bytes seen on the streaming initial-read. Mismatch → abandon the handoff for that episode.

### D5. `handoffTriggeredForGuid` and `completedFile()` race

Audit `completedFile(guid)`:
- If "DB row COMPLETED AND `File(path).exists()`", you have a TOCTOU window between `exists()` and the player's open call (small, but possible if cleanup runs concurrently).
- If DB-only, file may not be durably written.

Make `completedFile(guid)` strict:

```kotlin
fun completedFile(guid: String): File? {
    val row = downloadDao.getByGuid(guid) ?: return null
    if (row.status != COMPLETED) return null
    val f = File(row.path)
    if (!f.exists() || f.length() != row.expectedSize) return null
    return f
}
```

Keep `handoffTriggeredForGuid` as a sticky `Set<String>` for the lifetime of `PlayerController`. Add a `clearHandoffGuard(guid: String)` that's only called on explicit user-initiated re-play.

### D6. Recommended defensive handoff pattern (consolidated)

**For v0.9.0 ship: drop mid-playback handoff.** Switch URI only on next play of the same episode. Single flag in PlayerController:

```kotlin
// PATCH: PlayerController.kt
private val ENABLE_MID_PLAYBACK_HANDOFF = false   // v0.9.0+: defer to next play

fun playEpisode(ep: Episode) {
    val local = completedFile(ep.guid)
    val uri = local?.toUri() ?: ep.streamUrl.toUri()
    controller.setMediaItem(MediaItem.fromUri(uri), ep.lastPosMs)
    controller.prepare(); controller.play()
    if (local == null) downloader.start(ep)
    // NOTE: no observeDownloadCompletion handoff dispatch during active playback.
    // The next time the user plays this episode, completedFile() returns non-null.
}
```

If you must keep mid-playback handoff in a later release (v1.1+), use the explicit pause→setMediaItem→prepare→play sequence from D3.

---

## E. DOWNLOADED-EPISODE-ONLY PLAYBACK ISSUES (BASELINE EQ OFF)

### E1. `file://` vs `http(s)://` behavior

Media3 uses `FileDataSource` for `file://`. Reads are synchronous `RandomAccessFile.read()` on the load thread. Differences vs HTTP:
- No chunked-transfer, no compression, no redirect handling.
- Seeks are instantaneous (no Range RTT).
- `Loader` reads continuously until LoadControl says stop.
- Same `Mp3Extractor` for both, but with `file://` the extractor seeks via direct file offsets using Xing/VBRI/LAME tables instead of HTTP Range.

### E2. **Aggressive `minBufferMs=180_000` / `maxBufferMs=600_000` on local files** — most likely root cause

**Mechanism.** Your `DefaultLoadControl` says "keep at least 180 s and up to 600 s buffered" with `prioritizeTimeOverSizeThresholds=true`. On a local file the `Loader` reads as fast as the disk allows, attempting to fill the buffer. A 256 kbps stereo MP3 is ~2 MB/min compressed; decoded PCM is ~10.6 MB/min at 44.1k 16-bit stereo. **600 s buffered ≈ 106 MB of decoded PCM.** With `prioritizeTimeOverSizeThresholds=true`, the allocator ignores byte-size targets in favor of time threshold.

`DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE` (where `DEFAULT_BUFFER_SEGMENT_SIZE = 64 KB` → 12.8 MB minimum target), and `targetBufferBytes=LENGTH_UNSET` triggers `calculateTargetBufferBytes(renderers, trackSelections)` based on track types. Audio-only default is small — but `prioritizeTimeOverSizeThresholds=true` overrides it. Result: on a local file with fast read throughput, ExoPlayer pulls a huge portion (potentially the whole episode) into `SampleQueue` allocations within seconds of `prepare()`. On a 1–2 GB RAM device this is dangerous.

Symptoms:
- Near-OOM on long episodes; possible `OutOfMemoryError`.
- Janky UI as ART GC ramps.
- Audio underruns when a GC pause stretches the playback thread's `queueInput` deadline.
- Watchdog "no-forward-progress > 6 s" may even trip during a long GC.

ExoPlayer issue #8378 documents pathological LoadControl configurations causing playback stalls. Akamai's tuning writeup recommends min≈max with sane caps. The Media3 source (`DefaultLoadControl.java`) shows `minBufferMs` defaults to 50_000 ms — your 180_000 ms is 3.6× that.

**Fix (highest single-impact item for the "downloaded episodes" symptom):**

```kotlin
// PATCH: ExoPlayer builder — replace current LoadControl
val balancedLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        /* minBufferMs= */ 30_000,
        /* maxBufferMs= */ 60_000,
        /* bufferForPlaybackMs= */ 2_000,
        /* bufferForPlaybackAfterRebufferMs= */ 5_000
    )
    .setPrioritizeTimeOverSizeThresholds(false)
    .setTargetBufferBytes(8 * 1024 * 1024)    // 8 MB ceiling
    .build()
```

Rationale:
- 30/60 s is plenty for podcasts at 64–256 kbps.
- `setPrioritizeTimeOverSizeThresholds(false)` re-engages the byte ceiling — critical for local files.
- 8 MB ≈ 4 minutes at 256 kbps stereo PCM. Comfortable.

If you want different policies for local vs HTTP, you can switch the player's LoadControl by rebuilding the player at media-item change — but a single balanced config is simpler and sufficient.

### E3. FD exhaustion / read-ahead

`FileDataSource` holds one FD per active load. With 2 concurrent downloads + 1 player FD = 3 FDs. Android app rlimit comfortably 1024+. Not a real risk.

Read-ahead pattern of `FileDataSource` is "read until LoadControl says stop" — fixed by E2.

### E4. `.tmp → .final` rename while player holds read handle

You're on `minSdk 28`, so `Files.move(tmp, final, StandardCopyOption.ATOMIC_MOVE)` is available (API 26+). On the same filesystem this is POSIX `rename(2)`, atomic. After the rename, write COMPLETED to the DB; the DB write is the synchronization point.

```kotlin
// PATCH: LofiPodDownloader.kt — finalize sequence
private suspend fun finalize(tmpPath: Path, finalPath: Path, guid: String, expectedSize: Long) {
    withContext(Dispatchers.IO) {
        // 1. fsync file contents
        FileChannel.open(tmpPath, StandardOpenOption.WRITE).use { it.force(true) }
        // 2. Atomic rename
        Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
        // 3. fsync parent dir (where supported) so rename is durable
        try {
            FileChannel.open(finalPath.parent, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) { /* not all FS support dir fsync; tolerate */ }
        // 4. DB write — visibility barrier
        downloadDao.markCompleted(guid, finalPath.toString(), expectedSize)
    }
}
```

If a player already had an open FD on `.tmp` (which won't happen unless you change paths during playback — you don't), POSIX guarantees the FD continues to point to the same inode after rename. The relevant invariant: between observe-COMPLETED → setMediaItem(final), the file at `final` is fully written and durable. The sequence above ensures that.

### E5. OkHttp downloader edge cases

- **Range-resume after kill: tail corruption.** If you append-write to `.tmp` and the app is SIGKILL'd mid-write, bytes buffered inside OkHttp's stream but not yet flushed are lost; on resume you `Range: bytes=length(tmp)-` but the last few KB of `.tmp` may be garbage left from the previous write where the stream was buffered above the OS write boundary. **Fix:** truncate `.tmp` to a 64 KB boundary on resume start (matches your 64 KB read buffer):
  ```kotlin
  val safeLen = tmp.length() - (tmp.length() % 65536)
  RandomAccessFile(tmp, "rw").use { it.setLength(safeLen) }
  ```
  Worst-case loss: 64 KB.

- **Server returns 200 (full body) on a Range request.** Detect via `response.code` and reset target file rather than blindly appending.

- **Content-Length mismatch.** After download, verify `length(.tmp) == ContentLength` (from the final 200/206 response, not any earlier redirect). Mismatch → retry. Many podcast CDNs (PodTrac, Megaphone) inject tracking redirects; only the final response's `Content-Length` is authoritative.

### E6. `Mp3Extractor` ID3/LAME sniffing on podcast MP3s

Known issue surface for podcasts:
- ID3v2 with embedded chapter markers (CHAP/CTOC): parsed and emitted as metadata, shouldn't break playback but may spam logs.
- ID3v2 with embedded artwork (APIC frames, often 1–5 MB): may cause demuxer state confusion if frame length is malformed.
- LAME-VBR `Xing/Info` headers used for seek tables. Older ExoPlayer fixed gapless MP3 on Samsung (#8594), UTF-16 ID3 (#9087), and recently VBRI early-stop (#1904, fixed in 1.5.0+). Media3 1.5.0+ also added MP3 LAME ReplayGain parsing.

**If your downloaded-episode symptom is "stops short before the actual end" or "wrong duration reported"**, the VBRI table-of-contents fix in 1.5.0+ is a direct candidate. **Upgrade Media3 1.4.1 → 1.5.0+** (preferably 1.6.x).

Upgrade caveats:
- `DefaultAudioSink` internals evolved 1.4 → 1.6 (e.g., audio output retry logic improvements in 1.5+). Your `EqRenderersFactory.buildAudioSink` reflection path must be re-vetted.
- Per project notes, R8 is OFF since v0.3.0 due to reflection-into-DefaultAudioSink breakage. Keep R8 OFF for the upgrade.
- The `media3-common-ktx` module (1.5.0+) offers Kotlin suspend extensions for player events — useful for the awaitState helper in D3.
- ABI / AGP / Kotlin compat is fine: Media3 1.5.0–1.6.x supports AGP 8.x and Kotlin 2.0.20.

---

## v0.9.x → v1.0.0 SHIP CHECKLIST (ranked by impact ÷ cost)

**Audiophile Clout** column legend:
- `+++` major credibility gain (pro-tier visible feature)
- `++` notable (visible quality / architectural sophistication)
- `+` mild positive (subtle quality gain)
- `—` neutral (invisible to user)
- `−` mild risk to perceived quality if mishandled
- `−−` notable risk if mishandled (and not mitigated by a paired item)

| Rank | Fix | Section | Effort | Risk | Impact | Audiophile Clout |
|---|---|---|---|---|---|---|
| **1** | Replace `ArrayDeque<Double>` outputQueue with `DoubleRing` (or eliminate queue) | A1 | **S** | Low | Resolves dominant linear-phase artifact | **+** Zero-alloc audio thread = quiet credibility win; mention in Audio Diagnostics + Audiophile Notes |
| **2** | Tune `DefaultLoadControl` to 30/60 s, 8 MB ceiling | E2 | **XS** | Low | Resolves downloaded-episode issues | **—** Invisible unless audiophile is RAM-monitoring |
| **3** | Disable mid-playback streaming→downloaded handoff for v0.9.0+; defer to next play | D6 | **XS** | Low (small UX delta) | Eliminates handoff failure class | **—** if framed as "no mid-stream PCM splice — episode always plays from a complete, validated file." Frame in release notes as a quality choice. |
| **4** | Override `flush()` → `chainReset()` (limiter + oversampler + EQ state) | A8 | **XS** | Low | Eliminates click on seek/setMediaItem | **+** Clean seeks are an audiophile prerequisite |
| **5** | Atomic rename + fsync + DB-write ordering in `LofiPodDownloader.finalize()` | E4, E5 | **S** | Low | Hardens download correctness | **—** Invisible; foundational |
| **6** | Precompute FLAT kernel; remove sync FFT from `configure()` | A6 | **XS** | Low | Eliminates first-buffer stall on format change | **—** Invisible |
| **7** | `getChainLatencyUs()` → `displayPositionMs` (UI subtracts, watchdog does not); seek compensation | A4 | **S** | Low | Fixes progress-bar / seek correctness | **++** Latency-compensated transport = pro-DAW signaling. Surface "Chain latency: 46 ms" line in Audio Diagnostics. |
| **8** | Upgrade Media3 1.4.1 → 1.5.0+ (re-vet reflection; R8 stays OFF) | E6 | **M** | Medium (needs QA) | MP3 VBRI fix + sink retry improvements | **—** Invisible to user; updates a credible dependency |
| **9** | Truncate `.tmp` to 64 KB boundary on resume; verify `Content-Length` post-download | E5 | **S** | Low | Prevents rare downloaded-file corruption | **—** Invisible |
| **10** | Cross-fade between two parallel convolution paths on band changes | A2 | **M** | Medium | Eliminates slider-tick clicks (linear/FIR modes) | **++** Zipper-free EQ is a high-end EQ marker. FabFilter / Pultec / mastering-tier behavior. |
| **11** | **Interim**: hide linear-phase mode in v0.9.0–v0.9.3 UI; ship pure-IIR as only phase mode | C2 | **XS** | Low (paired with #14) | Sidesteps remaining linear-phase risk while rebuild lands | **−−** if shipped that way for v1.0.0. **Fully mitigated by #14**: linear-phase returns in v0.9.4 rebuilt on UPC + crossfade, alongside new Min-Phase FIR. **Net at v1.0.0: ++** (2 modes → 4 modes; one of them is a new genuine min-phase FIR). |
| **12** | Off-thread DSP worker with SPSC ring (parallelize FFT off audio thread) | A3 | **L** | Medium-high | CPU headroom for stretch features and Mixed-Phase mode | **+** Visible in CPU diagnostics; architectural credibility. Name the pattern ("SPSC lock-free ring") in Audiophile Notes. |
| **13** | Implement UPC (uniform partitioned convolution) | B | **L** | Medium | Foundation for #10, #14, and all FIR modes | **+++** Partitioned convolution = serious DSP territory. Name the technique in About / Audiophile Notes; cite Wefers 2015 / Gardner 1995. |
| **14** | **NEW MODE**: Min-Phase FIR via real-cepstrum kernel | C1 | **M** | Low | NEW phase mode #2: surgical precision, no pre-ringing, low latency | **+++** This is THE audiophile addition. Distinct from biquad min-phase (FIR magnitude precision + non-constant but ms-level group delay). Cite Mian & Nainer 1982 / Oppenheim-Schafer §10.5 in Notes. |
| **15** | Migrate JTransforms → PFFFT via JNI | A5 | **L** | Medium | 3–5× FFT speedup; CPU room for Mixed-Phase mode | **+** NEON-optimized FFT under the hood. Name-drop "PFFFT (NEON SIMD)" in Audio Diagnostics chain-spec line. |
| **16** | Clamp `audioNs` floor in ADPF reporting | A7 | **XS** | None | Defensive only | **—** Invisible |
| **17** | **NEW MODE**: Mixed-Phase (linear-phase ≥ 120 Hz, min-phase < 120 Hz, allpass-matched crossover) | §F | **M** | Medium | NEW phase mode #4: mastering-style hybrid | **++** Mastering-EQ flex; niche but recognizable to the audience that cares. Pro-Q3 "Natural Phase" / DMG EQuilibrium territory. |

---

## F. PHASE MODE LINEUP — TARGET FOR v1.0.0

v0.8.0 ships **2 modes**: `minimum` (RBJ biquad) and `linear` (4096-tap symmetric FIR OLA). The fix path opens room for **4 modes** at v1.0.0, distinguished by algorithm, latency, ringing behavior, and best-fit content.

| # | UI label | Algorithm | Latency | Pre-ringing | Best for | Audiophile read |
|---|---|---|---|---|---|---|
| 1 | **Pure IIR** (renamed from "Minimum") | RBJ peaking biquad cascade with crossfade on band changes | ~6 ms | none | Conversational pods, BT headphones, low-power devices, fastest band-slider response | "Transparent IIR, no phase fuss" — the safe default |
| 2 | **Min-Phase FIR** *(NEW v0.9.4)* | Real-cepstrum 4096-tap kernel via UPC | ~23 ms | **none** | Surgical EQ on speech transients; sermon/scripture audio with sibilance | "Pro-mastering territory" — what serious mastering EQs do |
| 3 | **Linear-Phase FIR** *(rebuilt v0.9.4)* | Symmetric 4096-tap kernel via UPC + crossfade on band-change | ~46 ms | yes (symmetric, audible on transients) | Music-heavy podcasts (worship/concert content), mixed feeds | "DAW-grade exact-phase" — the academically pure option |
| 4 | **Mixed-Phase** *(NEW v1.0.0)* | Linear-phase ≥ 120 Hz, min-phase < 120 Hz, allpass-matched crossover | ~46 ms | bass band only | Audiobook + bass-music hybrid, hymn collections w/ organ pedal | "Mastering-style hybrid" — niche flex; FabFilter Pro-Q3 "Natural Phase" territory |

**Diagnostics labeling.** In the Audio Diagnostics screen, display the per-mode chain spec, e.g.:

```
Mode: Min-Phase FIR
  Kernel: 4096 taps, cepstrum-derived
  Convolution: UPC, 4 partitions × 1024 samples
  FFT: PFFFT (arm64 NEON)        [was JTransforms in v0.9.0–v0.9.4]
  Algorithmic latency: 23.2 ms
  Total chain latency: 29.6 ms (incl. oversampler + limiter)
  Pre-ringing: none
```

This kind of expose-the-internals labeling is unusual for podcast apps and is, on its own, a strong signal of seriousness to the audiophile audience.

**Audiophile Notes page (existing route).** Update for v1.0.0 to document the four modes, the UPC architecture, the cepstrum derivation, and the linear-phase crossfade. The page already exists; this is a content edit, not engineering.

---

## v0.9.x → v1.0.0 RELEASE SEQUENCING

Each tag is independently shippable; the user perceives steady improvement; no release leaves the app in a worse state than v0.8.0 on the dimensions the user cares about.

| Tag | Theme | Includes | Phase modes available | Net clout vs v0.8.0 |
|---|---|---|---|---|
| **v0.9.0** | Stability cut | #1, #2, #3, #4, #5, #6, #11 | Pure IIR only (linear hidden) | **−** (linear-phase regression noticed only by users who used it) |
| **v0.9.1** | Hardening | #8, #9, #16 | Pure IIR only | **−** same |
| **v0.9.2** | Latency honesty | #7 | Pure IIR only | **+** Pro-DAW position reporting introduced |
| **v0.9.3** | DSP foundation | #12, #13 (UPC infrastructure lands, not yet user-facing in mode list) | Pure IIR only | **+** Foundation laid; visible in Audio Diagnostics |
| **v0.9.4** | FIR resurrection + new mode | #10 (crossfade), #14 (Min-Phase FIR mode added). Linear-Phase FIR returns, rebuilt on UPC + crossfade. | Pure IIR, **Min-Phase FIR**, Linear-Phase FIR | **+++** 3 modes; one of them is genuinely new and superior for speech |
| **v0.9.5** | Optimization | #15 (PFFFT JNI migration) | same 3 modes; faster | **++++** PFFFT name-drop adds to the technical credibility stack |
| **v1.0.0** | Release | #17 (Mixed-Phase mode); marketing materials; Audiophile Notes content refresh; About-screen tech credits ("PFFFT", "Wefers UPC", "real-cepstrum FIR design") | **Pure IIR, Min-Phase FIR, Linear-Phase FIR, Mixed-Phase** | **+++++** 2 modes → 4 modes; every mode has a defensible reason to exist; chain internals are visible and credibly named |

### Features that need tweaking en route (non-engineering)

- **Audiophile Notes page** — content rewrite for v1.0.0 (the page already exists; just rewrite). Sections to add: the four modes and when to use each; the UPC architecture diagram; the cepstrum-derivation paragraph (cite Mian & Nainer 1982 / Oppenheim-Schafer §10.5); the linear-phase crossfade paragraph (zipper noise context); zero-allocation audio-thread discipline; latency-compensated transport.
- **Audio guide (plain language) page** — light edits to reference the new mode names without engineering jargon: "Pure IIR (fastest)", "Min-Phase FIR (precise, low latency)", "Linear-Phase FIR (academically pure)", "Mixed-Phase (best of both)".
- **Mode chip UI in EqScreen** — expand from 2 chips to 4. Long-press tooltip per chip = the one-line "audiophile read" from §F.
- **Settings → DataStore key** — `phase_mode_linear: Boolean` → `phase_mode: Enum(PURE_IIR, MIN_FIR, LIN_FIR, MIXED)`. Migration path: existing `true` → `LIN_FIR`, existing `false` → `PURE_IIR`. Stage the migration so v0.9.0–v0.9.3 still write the old bool, then a one-shot DataStore migration in v0.9.4 promotes to the enum.
- **Audio Diagnostics screen** — extend the chain-spec line to render the per-mode block shown in §F.
- **Release notes** — v0.9.0 release notes must explicitly state that linear-phase is **temporarily hidden** while it's rebuilt, and that v0.9.4 brings it back alongside a new min-phase FIR mode. This preempts the inevitable "where did linear-phase go" complaint and converts it into an anticipation moment.
- **Share APK QR + GitHub release pipeline** — no engineering changes needed; just make sure the v0.9.x tags are clean and the `latest.json` is correct each step.

---

## CUMULATIVE AUDIOPHILE POSITIONING AT v1.0.0

Net effect of this plan on the app's positioning vs. v0.8.0:

- **2 modes → 4 modes.** More than doubles the phase-mode surface, including a genuine Min-Phase FIR (not just biquad min-phase). This is the headline.
- **Partitioned convolution + real-cepstrum + NEON FFT** become nameable internals. Surface them in Audio Diagnostics and Audiophile Notes — audiophiles read engineering tells, and this is one.
- **Latency-compensated transport.** Progress bar matches audible. Pro-DAW behavior.
- **Zipper-free EQ adjustment.** Crossfade between FIR kernels on band-change is a high-end EQ marker.
- **Zero-allocation audio thread.** Documented in Audiophile Notes. Signals discipline.
- **Open about trade-offs.** Each mode lists its latency and pre-ringing in the UI. Honesty reads as credibility.

**Risk to manage:** shipping v0.9.0–v0.9.3 with linear-phase hidden will draw attention from any user who used it in v0.8.0. **Mitigation:** release notes name the v0.9.4 return + new Min-Phase FIR mode explicitly; in-app banner on the EQ screen during v0.9.0–v0.9.3 ("Linear-Phase returns in v0.9.4, rebuilt on partitioned convolution. New Min-Phase FIR mode arrives the same release."). The audience for whom this matters is small, technically literate, and reads patch notes — the same audience that will reward the v1.0.0 four-mode lineup with the clout you're after.
