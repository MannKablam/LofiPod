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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
                            Icons.Filled.ArrowBack,
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
                    Section("Float64 throughout", FLOAT64)
                    SectionDivider()
                    Section("DC blocker (optional)", DC_BLOCKER)
                    SectionDivider()
                    Section("Parametric EQ", EQ)
                    SectionDivider()
                    Section("Phase modes (Minimum / Linear)", PHASE_MODES)
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
  -> per-channel biquad EQ    (Float64; cross-fade on band change)
  -> master gain              (Float64)
  -> 2x polyphase upsample    (anti-imaging FIR)
  -> look-ahead limiter       (~5 ms LA, linked-stereo, soft knee, runs at 2x)
  -> 2x polyphase downsample  (anti-aliasing FIR)
  -> [TPDF dither]            (only when limiter actually attenuated)
  -> int16 truncation
PCM int16 out
"""

private const val FLOAT64 = """
Every stage between input conversion and final truncation runs in IEEE-754
Float64 (double precision, ~16 decimal digits). Float32 has only ~7 decimal
digits and is numerically marginal for biquad EQ at the low end (31 / 62 Hz)
because the cookbook coefficient formulas multiply quantities that cancel to
~1e-7 of full magnitude. Float64 makes the precision concern vanish.

ARM scalar-FPU cost is identical for double vs float arithmetic, so this is
pure precision win at zero CPU cost.

Biquad and DC-blocker state are clamped to zero when they fall below 1e-15
(well below 16-bit quantization noise) to avoid the denormal slow-path on
ARM cores that don't have flush-to-zero set.
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
Six parametric peaking biquads per channel: 31 Hz, 62 Hz, 125 Hz, 500 Hz,
2 kHz, 8 kHz. Each band has its own gain (-12..+12 dB) and Q. Coefficients
are computed per RBJ's audio EQ cookbook (peaking EQ form) in Float64
end-to-end.

Direct-Form II Transposed topology. Per-channel state, per-band coefficients
(coefficients are shared across channels for a given band; state is not).
"""

private const val PHASE_MODES = """
The EQ stage can run in either of two modes, selectable from the Audio
screen.

Minimum-phase (default). The biquad cascade described above: ~6.4 ms total
chain latency, low CPU, transparent for almost all listeners. Like every
analog EQ ever made and most digital ones, it introduces frequency-dependent
group delay — different frequencies are delayed by different amounts. The
ear can't resolve sub-millisecond group delay differences, so for normal
music or speech this is inaudible.

Linear-phase. The biquad cascade's MAGNITUDE response is sampled at 8192
frequency points; the phase is set to zero; an inverse FFT produces a
4096-tap symmetric FIR kernel which is then Kaiser-windowed (beta = 6) to
suppress the residual sinc ripple from rectangular truncation, and convolved
against the audio stream via overlap-add. Every frequency is delayed by
EXACTLY the same amount (group delay = (kernel length - 1) / 2 = ~46 ms).
The original signal's transient waveform shape is preserved verbatim —
useful for audiophile-grade A/B testing where you want to verify a
recording's transient response without the EQ smearing it.

Tradeoff. Linear phase costs ~52 ms total chain latency (vs. ~6.4 ms for
minimum phase) and ~3-5x more CPU. For podcast playback both numbers are
fine on modern hardware; the latency is far below conversational thresholds
and the CPU is still well under one core.

The kernel synthesis runs on a worker coroutine on band changes; the audio
thread sees a single atomic reference swap when a new kernel is ready, so
slider drags don't stall the audio path.
"""

private const val CROSSFADE = """
On any band change (slider drag, preset switch, per-episode override
toggle), the new coefficients are installed into a parallel filter chain
that runs alongside the previous chain for 2048 samples (~46 ms at 44.1 kHz).
The output is mixed via an equal-power half-cosine crossfade.

Why two filters in parallel rather than interpolating coefficients: biquad
poles can briefly leave the unit circle during interpolation, which is
momentary instability = audible pop. Running two stable filters in parallel
and crossfading their outputs is bulletproof. CPU cost is roughly 2x the
EQ stage during the 46 ms fade window only; zero impact the rest of the
time.
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
Total chain latency at 44.1 kHz input:

  - Look-ahead limiter:  ~5.0 ms (5 ms LA at the 2x rate -> 5 ms at 1x)
  - Oversampler FIR:     ~1.4 ms (64 samples group delay across up + down at 1x)
  - ----------------------------
  - Total:               ~6.4 ms

Inaudible at any podcast playback context. Dropped frames at flush /
end-of-stream are handled by zero-padding the chain so the last 5 ms of
every track actually reaches your ears (not silently eaten by the LA
buffer).

Skip-silence runs at a separate stage of the audio sink and adds its own
small latency only when active.
"""

private const val CPU = """
Roughly a few percent of one core for stereo at 44.1 kHz on any phone made
in the last 5 years. The dominant costs are the FIR (128 taps x 2 channels
x 2 stages per input frame, run at the 1x rate) and the biquad cascade
(6 bands x 2 channels). The limiter's monotonic-deque peak detector is
O(1) amortized; envelope smoothing and gain application are trivial.

The Audio diagnostics screen surfaces a live load factor (= DSP wallclock /
audio duration per buffer). Values near 0% mean abundant headroom; values
near 100% mean the audio thread is saturated and underruns are imminent.
On modern hardware the chain holds well under 10% even under thermal
throttling.
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
The audio chain is original code, written for LofiPod and licensed under
the project's terms. No third-party audio code is pulled in for the chain.

Algorithmic credits (math, not code):

  - Biquad coefficient formulas: Robert Bristow-Johnson, "Audio EQ Cookbook"
    (public). RBJ's notes are widely-cited reference material; the formulas
    themselves are unpatentable mathematics.
  - TPDF dither: Lipshitz, Wannamaker, Vanderkooy 1992, "Quantization and
    Dither: A Theoretical Survey" (public).
  - Polyphase upsample/downsample structure: standard DSP textbook
    (Vaidyanathan, "Multirate Systems and Filter Banks").
  - Kaiser window: Kaiser & Schafer 1980.

The linear-phase EQ option uses JTransforms (BSD-2-Clause, pure JVM) for
its FFT primitive. No GPL code is linked into the audio chain.
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
