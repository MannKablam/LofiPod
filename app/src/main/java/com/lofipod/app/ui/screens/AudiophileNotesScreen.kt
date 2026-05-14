@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Static reference page describing LofiPod's audio chain in enough detail that
 * a curious listener can verify what's actually running. Sister screen to
 * [AudioDiagnosticsScreen] — diagnostics shows the LIVE state of the chain;
 * this page documents the DESIGN. Both are reachable from Settings.
 *
 * Content is hand-written (not auto-generated from code) so the wording can
 * stay accessible without exposing implementation noise. Specs cited here
 * match the file headers in `EqAudioProcessor.kt`, `Biquad.kt`, `DcBlocker.kt`,
 * `Limiter.kt`, `Oversampler.kt`, `Dither.kt` — when those change, update
 * this screen too.
 *
 * SelectionContainer wraps the body so the spec values are copy-paste-able
 * (useful for forum posts, bug reports, "is your app really doing this?"
 * questions).
 */
@Composable
fun AudiophileNotesScreen(
    onBack: () -> Unit,
    onOpenLofiNotes: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes for audiophiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CrossLink(label = "Non-audiophile Lofi notes", onClick = onOpenLofiNotes)

            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    Section("Overview", OVERVIEW)
                    SectionDivider()
                    Section("Signal chain", CHAIN)
                    SectionDivider()
                    Section("Float64 / Float32 boundaries", FLOAT64)
                    SectionDivider()
                    Section("DC blocker (optional)", DC_BLOCKER)
                    SectionDivider()
                    Section("Parametric EQ", EQ)
                    SectionDivider()
                    Section("Phase modes (four-way lineup)", PHASE_MODES)
                    SectionDivider()
                    Section("UPC: uniform partitioned convolution", UPC)
                    SectionDivider()
                    Section("Min-phase kernel via real cepstrum", CEPSTRUM)
                    SectionDivider()
                    Section("Cross-fade on band changes", CROSSFADE)
                    SectionDivider()
                    Section("Master gain", GAIN)
                    SectionDivider()
                    Section("2x polyphase oversampling", OVERSAMPLING)
                    SectionDivider()
                    Section("Look-ahead limiter", LIMITER)
                    SectionDivider()
                    Section("TPDF dither + truncation", DITHER)
                    SectionDivider()
                    Section("Latency", LATENCY)
                    SectionDivider()
                    Section("Latency-compensated transport", LATENCY_TRANSPORT)
                    SectionDivider()
                    Section("Zero-allocation audio-thread discipline", ZERO_ALLOC)
                    SectionDivider()
                    Section("CPU footprint", CPU)
                    SectionDivider()
                    Section("What this chain does NOT do", NOT_DOES)
                    SectionDivider()
                    Section("Licenses + attribution", LICENSES)
                    SectionDivider()
                    Section("Verifying the chain is live", VERIFY)
                }
            }

            Spacer(Modifier.height(16.dp))
            CrossLink(label = "Non-audiophile Lofi notes", onClick = onOpenLofiNotes)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Right-justified text-link row used at top + bottom of the page. Mirrors
 *  the convention used in [NonAudiophileLofiNotesScreen] so the cross-link
 *  reads the same way in both directions. */
@Composable
private fun CrossLink(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) { Text(label) }
    }
}

/** Subtle visual divider between top-level sections. Quieter than a heading
 *  change alone — gives the eye a place to land between dense technical
 *  paragraphs. */
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

@Composable
private fun Section(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    // Reflow handles two issues with the """ raw-string consts:
    //   1. Leading + trailing newlines from the """ delimiters that would
    //      otherwise render as blank lines.
    //   2. Hard-wrapped lines that the source has at fixed column widths —
    //      Compose's Text honors those line breaks, so the result reads
    //      "split to next line in arbitrary places" on actual phone widths.
    // [reflowProse] joins prose-paragraph lines with spaces (letting Compose
    // wrap them naturally to the device width) while preserving indented
    // diagram blocks (the chain ASCII art, latency math, etc.) intact.
    Text(reflowProse(body), style = MaterialTheme.typography.bodySmall)
}

/**
 * Convert the """-style raw-string constants into prose with natural
 * device-width wrapping. Paragraph boundaries (double newlines) survive;
 * lines within a paragraph are joined by a single space — UNLESS any line
 * in that paragraph starts with two or more spaces, which we treat as a
 * code/diagram block whose alignment must be preserved (the signal-chain
 * ASCII, the latency math, etc.).
 */
private fun reflowProse(s: String): String {
    val trimmed = s.trim()
    return trimmed.split("\n\n").joinToString("\n\n") { paragraph ->
        val lines = paragraph.lines()
        val isDiagram = lines.any { it.startsWith("  ") }
        if (isDiagram) paragraph
        else lines.joinToString(" ") { it.trim() }
    }
}

private const val OVERVIEW = """
LofiPod runs a real-time audiophile DSP chain on every track. The chain is
the same for podcasts and bundled content. It is built from scratch in this
codebase — no third-party DSP libraries are pulled in for the audio path.

The intent: every change to the signal is principled, justifiable, and
verifiable. No tanh "warmth," no perceptual shortcuts, no opaque "audio
enhancement." If you can hear a difference between LofiPod and a bit-perfect
player, this page documents exactly what's responsible.
"""

private const val CHAIN = """
PCM int16 in
  -> Float64 conversion       (zero-error int -> double)
  -> [DC blocker]             (toggleable, off by default)
  -> EQ stage (one of):
       Pure IIR     - 10-band biquad cascade w/ crossfade on band change
       Min FIR      - UPC convolution, real-cepstrum min-phase kernel
       Linear FIR   - UPC convolution, symmetric linear-phase kernel
       Mixed        - hybrid: min-phase < 120 Hz, linear-phase > 120 Hz
  -> master gain              (Float64)
  -> 2x polyphase upsample    (anti-imaging FIR)
  -> look-ahead limiter       (~5 ms LA, linked-stereo, soft knee, runs at 2x)
  -> 2x polyphase downsample  (anti-aliasing FIR)
  -> [TPDF dither]            (only when limiter actually attenuated)
  -> int16 truncation
PCM int16 out

The EQ stage swaps based on the user's Phase mode selection; the rest of
the chain (DC blocker, gain, oversampler, limiter, dither) is identical
across modes. The four FIR-based modes share a single UPC convolution
engine — only the kernel synthesis differs.
"""

private const val FLOAT64 = """
Most of the chain runs in IEEE-754 Float64 (double precision, ~16 decimal
digits). Float32 has only ~7 decimal digits and is numerically marginal for
biquad EQ at the low end (31 / 62 Hz) because the cookbook coefficient
formulas multiply quantities that cancel to ~1e-7 of full magnitude. Float64
makes that precision concern vanish for the biquad cascade, kernel
synthesis, gain stage, oversampler, limiter, and dither.

ARM scalar-FPU cost is identical for double vs float arithmetic, so this
is pure precision win at zero CPU cost.

Float32 boundary at the FIR convolution. The FIR EQ modes (Min FIR /
Linear FIR / Mixed) route the audio block through a PFFFT-backed UPC
convolver internally. PFFFT is single-precision (Float32) — that's where
its NEON SIMD speedup comes from. The conversion happens at the
UpcConvolver boundary: input block converts Float64 -> Float32 before the
convolution, output converts back Float32 -> Float64 before re-entering
the gain / oversampler / limiter stages.

This is acceptable because the input itself is 16-bit PCM (16 bits of
resolution); Float32 has 24 bits of mantissa = 8 bits of headroom above
the input's actual precision. The kernel SYNTHESIS still happens in
Float64 (on the worker thread, where the cepstrum log/exp recipe benefits
from the extra precision) and converts to Float32 only at the
UpcConvolver.setKernelTaps call.

Biquad and DC-blocker state are clamped to zero when they fall below
1e-15 (well below 16-bit quantization noise) to avoid the denormal
slow-path on ARM cores that don't have flush-to-zero set.
"""

private const val DC_BLOCKER = """
A single-pole high-pass at ~5 Hz. Removes any DC (0 Hz) energy that the
source carries before it hits the EQ + gain stages, where DC would silently
steal headroom from the limiter.

Off by default because well-mastered podcasts have no DC and the filter is a
tiny bit of CPU + a marginal phase shift below 20 Hz for them. Worth turning
on for low-bitrate MP3 sermons or other badly-encoded sources where you
notice headroom problems on quiet passages.
"""

private const val EQ = """
Ten parametric peaking biquads per channel at ISO third-octave centers:
31 Hz, 62 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, 16 kHz.
Each band has its own gain (-12..+12 dB) and Q (default 1.41 = 2/3-octave
bandwidth). Coefficients are computed per RBJ's audio EQ cookbook (peaking
EQ form) in Float64 end-to-end.

Direct-Form II Transposed topology. Per-channel state, per-band
coefficients (coefficients are shared across channels for a given band;
state is not).

The biquad cascade is what runs in Pure IIR mode. The three FIR modes
(Min FIR / Linear FIR / Mixed) SAMPLE the same biquad cascade's magnitude
response to derive their FIR kernels — so all four modes produce the
same target magnitude curve. Only the phase response (and the convolution
mechanism) differs.
"""

private const val PHASE_MODES = """
The EQ stage can run in one of four modes, selectable from the Audio
screen. All four produce the same MAGNITUDE response (whatever your band
sliders dial in); they differ in PHASE response — how the EQ delays
different frequencies relative to each other.

1. Pure IIR (default). Ten-band biquad cascade direct on the Float64
audio stream. ~6.4 ms total chain latency, lowest CPU, transparent for
almost all listeners. Like every analog EQ ever made and most digital
ones, it introduces frequency-dependent group delay — different
frequencies are delayed by different amounts. The ear can't resolve
sub-millisecond group delay differences at typical EQ Qs, so for podcast
content this is inaudible. Fastest slider response of the four modes.

2. Min-Phase FIR. The biquad cascade's MAGNITUDE response is sampled
at 8192 frequency points; the phase is derived via a real-cepstrum recipe
(see "Min-phase kernel via real cepstrum" below) to produce a 4096-tap
CAUSAL impulse response — all the kernel's energy is front-loaded.
Convolved against the audio via UPC. Sub-millisecond group delay across
the spectrum, NO pre-ringing. ~29 ms total chain latency (UPC block
buffering + ~6 ms post-EQ chain). Best mode for transient-heavy speech
with sibilance — surgical magnitude precision without the linear-phase
"cough that precedes itself" smearing.

3. Linear-Phase FIR. The same magnitude response is realized as a
4096-tap SYMMETRIC impulse response (zero-phase synthesis: IFFT a
zero-phase spectrum, circular shift, truncate, Kaiser-window). Every
frequency is delayed by EXACTLY the same amount (group delay = kernel
center = ~46 ms). The original signal's transient waveform shape is
preserved verbatim — useful for audiophile-grade A/B testing where you
want to verify a recording's transient response without ANY EQ smearing
in time alignment. Tradeoff: ~70 ms total chain latency, plus audible
pre-ringing on sharp transients (a brief "tinkle" before each transient
strike that's the famous linear-phase pre-echo).

4. Mixed-Phase. Hybrid: min-phase synthesis below a ~120 Hz crossover,
linear-phase synthesis above. Complementary cosine-ramp masks (80 -> 180
Hz transition band) split the target magnitude; the two halves are
synthesized separately and the time-domain impulse responses are
time-aligned (min-phase shifted forward by KERNEL_LENGTH / 2 to match
linear-phase's group delay) and summed. Result: a single hybrid 4096-tap
kernel that delivers no-pre-ringing bass and transient-exact
mids/highs. ~70 ms total chain latency (same as Linear-Phase — the
linear contribution dominates the group delay). Mastering-EQ flex,
recognizable to listeners familiar with FabFilter Pro-Q3 "Natural Phase"
or DMG EQuilibrium.

When to use each:
  - Pure IIR for conversational pods or BT headphones where slider feel
    and latency matter; lowest power draw.
  - Min-Phase FIR for sermons / lecture audio where sibilance + transient
    sharpness are most audible. No pre-ringing means the kernel can't
    smear consonants in time.
  - Linear-Phase FIR for material where exact transient time-alignment
    matters. Pre-ringing is the cost of academic purity.
  - Mixed-Phase when you want bass without the pre-echo + mids/highs
    that are transient-exact. Niche but distinctive.

The kernel synthesis runs on a worker coroutine on band changes; the
audio thread sees a single atomic reference swap when a new kernel is
ready, so slider drags don't stall the audio path. A 4-block (~93 ms)
linear crossfade between the prior and new kernels makes the slider
drag itself audibly seamless — see "Cross-fade on band changes" below.
"""

private const val UPC = """
The three FIR modes (Min FIR / Linear FIR / Mixed) share a single
Uniform Partitioned Convolution (UPC) engine. UPC is the standard
real-time convolution algorithm for kernels of medium length and is
substantially cheaper per output sample than naive overlap-add.

Architecture. The 4096-tap kernel is split into P=4 partitions of
L=1024 samples each. Each partition is FFT'd to a packed-complex
spectrum and stored. Per input block of L samples:

  1. The convolver builds a 2L=2048 input window
     [previous_block | current_block].
  2. Single forward FFT transforms it to PFFFT's internal-layout
     spectrum.
  3. The new spectrum gets pushed onto a frequency-domain delay line
     (FDL) of length P.
  4. The FDL is multiply-accumulated against the kernel partition
     spectra: Y = sum over p in 0..P-1 of FDL[head - p] * K_partition[p].
     PFFFT's pffft_zconvolve_accumulate runs this as a single
     NEON-SIMD inner loop.
  5. Single inverse FFT brings the accumulated spectrum back to time
     domain.
  6. Emit the second half (overlap-save form).

Why this is cheaper. Complexity per output sample is O(log L + P/2) =
O(log 1024 + 2) vs. O(log 8192) for a monolithic single-FFT OLA at the
same kernel length. The smaller per-block FFTs run substantially faster,
and the inner loop (multiply-accumulate of P partitions) is what NEON
SIMD eats for breakfast.

Clean kernel switching. The FDL stores INPUT history, not
kernel-convolved output history. Atomically swapping the kernel
partition spectra (which is what setKernelTaps does on band changes) is
a clean linear-time-invariant switch — no stale OLA tail mixing the new
kernel's output with the old kernel's convolution residue. Combined with
the v0.9.8 4-block linear crossfade, slider drags produce zero
audible zipper noise.

Reference: Frank Wefers, "Partitioned convolution algorithms for
real-time auralization" (RWTH Aachen, 2015). FFT_SIZE = 2 * block_length
is Wefers's optimal default; deviating only pays for very long kernels.
"""

private const val CEPSTRUM = """
Min-Phase FIR (and the low-band of Mixed-Phase) uses a real-cepstrum
recipe to derive a causal, energy-front-loaded impulse response from
the target magnitude response. The Mian & Nainer 1982 / Oppenheim-Schafer
section 10.5 formulation. Cheaper than the complex-cepstrum recipe
(no phase unwrap needed).

Steps (worker thread, Float64):

  1. Sample target magnitude at FFT_SIZE/2 + 1 bins: M[k] = |H_target(k)|
  2. Real cepstrum c_r = IFFT(ln M)
  3. Fold: c_min[n] = c_r[n] * w[n] where w = (1, 2, 2, ..., 2, 1, 0, ..., 0)
     for n = 0..N-1. This zeros the negative-time half of the cepstrum,
     which is what makes the result min-phase.
  4. min_log_spec = FFT(c_min) — complex log-spectrum of the min-phase IR
  5. min_spec[k] = exp(min_log_spec[k]) — exponentiate bin-by-bin
  6. h_min = IFFT(min_spec) — causal min-phase impulse response
  7. Truncate to KERNEL_LENGTH (energy is front-loaded; truncation tail
     is negligible). Apply Kaiser window (beta = 6) on the truncation
     boundary for cleanup.

The resulting kernel realizes |H_target| exactly (modulo truncation +
windowing) with all energy concentrated near tap 0. Group delay collapses
from the linear-phase ~46 ms down to sub-ms — small enough that the ear
doesn't perceive any frequency-dependent delay smearing.

Cost: two extra FFTs at synth time (worker thread). No audio-thread cost
change vs. linear-phase synthesis.
"""

private const val CROSSFADE = """
Both the biquad chain and the FIR convolver run a band-change crossfade
to suppress zipper noise on slider drags.

Pure IIR. On any band change, the new biquad coefficients are installed
into a parallel filter chain that runs alongside the previous chain for
2048 samples (~46 ms at 44.1 kHz). The output is mixed via an equal-power
half-cosine crossfade.

Why two filters in parallel rather than interpolating coefficients:
biquad poles can briefly leave the unit circle during interpolation,
which is momentary instability = audible pop. Running two stable filters
in parallel and crossfading their outputs is bulletproof. CPU cost is
roughly 2x the EQ stage during the 46 ms fade window only; zero impact
the rest of the time.

FIR modes. The UPC convolver retains the prior kernel partition spectra
when a new kernel arrives; for the next 4 blocks (~93 ms at 1024 samples
at 44.1 kHz) it runs the same FDL against BOTH kernels, IFFTs both
outputs, and mixes them via a linear alpha ramp going from 0 to 1
across the fade window. After 4 blocks the prior kernel reference
drops and the dual-path machinery goes dormant again.

Since the FDL stores INPUT (not output) history, sharing it across the
two kernels is mathematically clean: both convolutions see the same
input. Only the multiply-accumulate against each kernel's partition
spectra differs.

Rapid drag handling: each kernel publish captures whatever is currently
live (possibly itself a mid-fade new kernel) as the new "prev" and
restarts the alpha ramp from 0. The user always hears a smooth ramp
between the most-recent-live state and the next, regardless of drag
speed.

CPU cost during the FIR fade window: one extra IFFT and one extra set
of P MACs per channel per block. With PFFFT's NEON SIMD that's well
within budget at 2x playback even on mid-range ARM. After the fade
window the cost is identical to steady-state UPC.
"""

private const val GAIN = """
Linear (10^(dB/20)) scalar applied to every sample after the EQ. Range
0..+12 dB at the UI level. The limiter downstream catches any peaks the
gain pushes above -1 dBFS, so cranking the slider stays clean instead of
clipping.
"""

private const val OVERSAMPLING = """
The limiter is wrapped in a 2x oversampling envelope. The biquad EQ is
linear and doesn't need oversampling — it can only attenuate or boost
existing frequencies, not create new ones. The look-ahead limiter, however,
is a time-varying gain element. Multiplying a signal by a rapidly-changing
gain envelope spreads the spectrum (it's a convolution in the frequency
domain). Components that would have been silently bandlimited away can fold
back into the audible range as aliasing.

Running the limiter at 2x sample rate pushes alias products above the
original Nyquist where the downsample filter removes them.

FIR design: 128 taps, Kaiser window with beta = 9, low-pass at the original
Nyquist. ~90 dB stopband attenuation; transition band roughly 20-24 kHz at
44.1 kHz input. Below 20 kHz the response is flat to ~0.001 dB ripple,
keeping the roll-off entirely outside the audible band even for golden-ear
listeners. Linear phase (FIR is symmetric).

Both up and down stages are polyphase (taps split into even/odd phases for
the upsampler; single FIR convolution at 2x rate sampled at 1x for the
downsampler).
"""

private const val LIMITER = """
Look-ahead brick-wall limiter on the chain output, operating at 2x rate
inside the oversampling envelope.

  - Look-ahead window: ~5 ms (220 samples at 44.1k input rate)
  - Threshold: -1 dBFS (~0.891 linear), leaving 1 dB of truncation headroom
  - Soft knee: 3 dB wide, quadratic in dB domain (peaks below -2.5 dBFS get
    no reduction; peaks above +0.5 dBFS are hard-limited; in between get
    progressively reduced for an inaudible compression onset)
  - Envelope follower: 1 ms attack, 50 ms release, one-pole
  - Linked stereo: gain reduction = max(|L|, |R|) across the LA window,
    applied identically to both channels. Per-channel GR would shift the
    stereo image (a transient on one side only would briefly push that
    channel "back" in the field). Linked GR preserves spatial stability.

Sliding-window peak detection uses a monotonic deque (head-to-tail
non-increasing), so peak retrieval is O(1) amortized rather than scanning
the full LA window every frame. Bit-exact same windowed max as a brute-force
scan; this is purely a CPU optimization that buys headroom for thermal /
power-saver throttling.
"""

private const val DITHER = """
Triangular-PDF (TPDF) dither at the int16 truncation stage, peak +/-1 LSB.
Sum of two uniform RPDF samples — variance is signal-INDEPENDENT, which
means no modulation noise (the noise floor doesn't "breathe" with the
signal).

Gated on limiter activity: dither only fires when the limiter actually
attenuated the buffer. When the chain is in passthrough or the limiter is
idle, the chain output is bit-identical to its input (or as close as biquad
+ FIR numerical error allows), and adding dither would be a noise-floor
regression vs. that bit-exact path.

Standard mastering practice for 16-bit deliveries; LofiPod uses it because
the chain output IS a 16-bit delivery to Android's audio sink.
"""

private const val LATENCY = """
Total chain latency varies by phase mode at 44.1 kHz input:

  Common to all modes (post-EQ chain):
    - Look-ahead limiter:  ~5.0 ms  (5 ms LA at the 2x rate -> 5 ms at 1x)
    - Oversampler FIR:     ~1.4 ms  (64 samples group delay, up + down at 1x)
    - Subtotal:            ~6.4 ms

  EQ-stage contribution (per mode):
    Pure IIR mode:          0 ms     (biquad is min-phase, sub-ms group delay)
    Min-Phase FIR:         ~23 ms    (UPC block-buffering delay + ms-scale
                                       group delay from the cepstrum kernel)
    Linear-Phase FIR:      ~70 ms    (UPC block + symmetric kernel center
                                       at KERNEL_LENGTH / 2)
    Mixed-Phase:           ~70 ms    (same as Linear; the linear-phase
                                       contribution dominates the group delay)

  Total chain latency:
    Pure IIR:              ~6.4 ms
    Min-Phase FIR:         ~29 ms
    Linear-Phase FIR:      ~70 ms
    Mixed-Phase:           ~70 ms

Even the longest mode (~70 ms) is far below the 100 ms threshold of
conversational lip-sync, so podcast playback is unaffected. Dropped
frames at flush / end-of-stream are handled by zero-padding the chain
so the last few ms of every track actually reach your ears (not silently
eaten by the LA buffer or the UPC accumulator).

Skip-silence runs at a separate stage of the audio sink and adds its own
small latency only when active.
"""

private const val LATENCY_TRANSPORT = """
Pro-DAW behavior: the position reported to the UI (the scrubber, time
readout, note-creation position) subtracts the chain's algorithmic
latency from the AudioTrack's raw playback position. So the scrubber
matches what you are AUDIBLY hearing right now, not what the audio sink
last wrote.

When you SEEK (drag the scrubber, tap a saved note position), the seek
target is adjusted by the same offset in the opposite direction — so a
tap on "1:00" lands AUDIBLE 1:00, not raw-frame 1:00 (which would be
audibly ~70 ms before 1:00 in Linear or Mixed modes).

The stall watchdog and the database persistence path intentionally read
raw position directly. Forward-progress detection is about raw frames
advancing through the audio sink; save/restore is about resuming on the
same raw frame on next play. The latency compensation is a UI-layer
concern only.

The +/-15s seek-back and +/-30s seek-forward transport buttons use
Media3's built-in seek-by-increment, which operates on raw position at
both endpoints. Both endpoints shift by the same constant offset, so the
relative seek is exact without explicit compensation.

This is the kind of behavior pro-DAW transports do reflexively and most
podcast apps don't.
"""

private const val ZERO_ALLOC = """
The audio thread is held to zero heap allocations during steady-state
playback. Every scratch buffer the chain needs is pre-allocated at
configure time and reused across every queueInput call:

  - Per-channel input/output frame arrays (1x rate, 2x rate)
  - Biquad state (z1, z2 per band per channel)
  - DC-blocker state (x_prev, y_prev per channel)
  - Oversampler delay lines (up + down stages, per channel)
  - Limiter LA delay line + monotonic-deque peak window
  - FirEq input accumulators + block scratch (Float64 in, Float32 out)
  - UPC frequency-domain delay line + workspace + accumulators (Float32)
  - Output ring buffer (primitive DoubleArray-backed, no autobox)

Audio-thread allocation is dangerous: ART's concurrent copying GC is
fast, but any allocation slow path (TLAB refill), GC barrier hit, or
background concurrent-mark phase can stretch a queueInput call past its
~23 ms deadline. The AudioTrack buffer absorbs occasional spikes; what
the v0.9.0 audit caught was an autoboxing pattern (ArrayDeque<Double>)
that was generating ~88,000 transient Double allocations per second on
the audio thread, sustained ~1.4 MB/s of heap garbage, correlated with
click/pop artifacts on long sessions. Replacing it with a primitive
DoubleArray-backed ring buffer (DoubleRing) closed that hole.

Worker-thread kernel synthesis allocates (~128 KB transient per band
change), but that runs on Dispatchers.Default — off the audio path
entirely. The new kernel reaches the audio thread via a single @Volatile
reference swap, atomic and allocation-free at the audio-thread side.
"""

private const val CPU = """
Roughly a few percent of one core for stereo at 44.1 kHz on any phone
made in the last 5 years.

In Pure IIR mode the dominant costs are the oversampler FIR (128 taps
x 2 channels x 2 stages per input frame, run at the 1x rate) and the
10-band biquad cascade. The limiter's monotonic-deque peak detector is
O(1) amortized; envelope smoothing and gain application are trivial.

In FIR modes the dominant cost shifts to the UPC convolution. PFFFT
(NEON-SIMD on ARM, single-precision) runs each 2048-point forward +
inverse FFT in ~100-400 microseconds on mid-range ARM cores —
substantially faster than the equivalent pure-JVM DoubleFFT_1D path
that was here pre-v0.9.6 (~400-1800 microseconds). The multiply-
accumulate inner loop uses PFFFT's pffft_zconvolve_accumulate, which is
SIMD-vectorized on top.

The Audio diagnostics screen surfaces a live load factor (= DSP
wallclock / audio duration per buffer). Values near 0% mean abundant
headroom; values near 100% mean the audio thread is saturated and
underruns are imminent. On modern hardware the chain holds well under
10% even at 2x playback under thermal throttling — the PFFFT NEON
speedup gives substantial headroom even in the FIR modes.

ADPF (Android Dynamic Performance Framework, API 31+) integration: the
chain reports per-buffer processing time + audio deadline to the OS
scheduler via PerformanceHintManager.Session. This lets the governor
pick a CPU frequency that meets the audio deadline rather than guessing
based on whatever heuristic it uses for general workloads. The targetNs
passed to the hint manager is scaled by playback speed: at 2x, the
deadline is (audio_duration / 2) of wall-clock, and we tell ADPF
exactly that. Without this scaling at 2x the OS picks a frequency for
a deadline twice as generous as reality, producing the
screen-off / pocket / thermal throttle chop reports from v0.6.x.
"""

private const val NOT_DOES = """
For transparency, things this chain explicitly does NOT do:

  - No psychoacoustic "enhancement" (no exciter, no harmonic generator, no
    bass synthesis)
  - No multiband compression / dynamic EQ
  - No "wide stereo" / Haas-style channel-shuffle widening
  - No reverb, no spatializer, no virtualizer
  - No upsampling beyond what the limiter needs internally — the chain
    output is the input sample rate, bit-perfect when no DSP fired

If you turn the Audio enhancement master off, every stage above is
bypassed and Android receives the bit-identical 16-bit input from the
decoder.
"""

private const val LICENSES = """
The audio chain is mostly original code, written for LofiPod and
licensed under the project's terms. Two third-party FFT libraries are
linked into the audio path:

  - PFFFT (BSD-3-Clause, by Julien Pommier, with FFTPACK pieces from
    NCAR/UCAR). NEON-SIMD single-precision FFT. Used on the audio
    thread for the UPC convolution's forward/inverse FFTs and for
    the multiply-accumulate inner loop (pffft_zconvolve_accumulate).
    Full license text bundled at assets/licenses/LICENSE-PFFFT.txt.

  - JTransforms (BSD-2-Clause / MPL 1.1 / LGPL tri-license, used
    here under BSD-2-Clause, by Piotr Wendykier). Pure-JVM
    double-precision FFT. Used on the kernel-synthesis worker thread
    (off the audio path) for the magnitude / cepstrum / kernel-impulse
    derivations. Full license text bundled at
    assets/licenses/LICENSE-JTRANSFORMS.txt.

No GPL code is linked into the chain.

Algorithmic credits (math, not code):

  - Biquad coefficient formulas: Robert Bristow-Johnson, "Audio EQ
    Cookbook" (public). RBJ's notes are widely-cited reference
    material; the formulas themselves are unpatentable mathematics.
  - TPDF dither: Lipshitz, Wannamaker, Vanderkooy 1992, "Quantization
    and Dither: A Theoretical Survey" (public).
  - Polyphase upsample/downsample structure: standard DSP textbook
    (Vaidyanathan, "Multirate Systems and Filter Banks").
  - Kaiser window: Kaiser & Schafer 1980.
  - Uniform Partitioned Convolution: Frank Wefers, "Partitioned
    convolution algorithms for real-time auralization"
    (RWTH Aachen, 2015); Gardner 1995, "Efficient convolution
    without input/output delay."
  - Real-cepstrum min-phase kernel derivation: Mian & Nainer 1982 /
    Oppenheim-Schafer, "Discrete-Time Signal Processing", section 10.5.
"""

private const val VERIFY = """
Two ways to confirm the chain is doing what this page describes.

By ear — the Audio screen has a "Hold to A/B" button under the global
toggles. Press and hold to instantly bypass the entire DSP chain (your
settings stay intact); release to bring the chain back. Use this on
material with strong low end, or material you've EQ'd, to hear what the
chain is actually contributing. Settings are never written by this
button — it's purely momentary.

By telemetry — the Audio diagnostics screen (Settings -> Audio
diagnostics) shows live chain state in real time:

  - Chain spec: input format, FIR length, LA window, threshold, total
    latency. One-shot per audio configure; verifies the chain is wired
    the way this page claims.
  - Live: input peak (post-EQ), output peak (post-chain), limiter gain
    reduction, plus state flags (passthrough / xfade / dither active /
    DC blocker on / disabled).
  - Performance: per-buffer DSP wallclock, average + p95 + max processing
    time, and load factor (= DSP time / audio duration). Use this to
    confirm the audio thread isn't saturated under your specific
    conditions (pocket, screen-off, thermal-throttled).
  - Counters: cumulative configures / flushes / cross-fades / drains /
    passthrough-vs-DSP buffer split / total frames processed.
  - Recent events: rolling log of the last ~50 chain transitions
    (configures, flushes, cross-fades, passthrough enter/exit, EOS
    drains, A/B-bypass press/release).
  - Copy-to-clipboard button dumps everything as plain text for sharing.

If anything on this page disagrees with what the diagnostics screen
shows, the diagnostics screen is the authoritative source — that's what
the audio thread is actually doing right now.
"""
