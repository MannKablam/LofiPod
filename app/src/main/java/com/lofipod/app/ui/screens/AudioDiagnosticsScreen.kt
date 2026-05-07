@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.AudioChainTelemetry
import com.lofipod.app.audio.EqPresets
import com.lofipod.app.data.Settings
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max

/**
 * Full-screen diagnostics for the EQ audio chain. Surfaces:
 *
 *   - **Chain spec.** Input format, oversampler FIR length, limiter LA window,
 *     threshold, total chain latency. Read once on configure; verifies the
 *     chain is wired the way the code claims it is.
 *   - **Live readouts.** Decayed peak meters at chain input (post-EQ/gain,
 *     pre-upsample) and chain output (post-downsample), limiter gain
 *     reduction in dB, and current state flags (passthrough / fading /
 *     dither active / DC blocker on). Refreshed every ~250 ms via a Compose
 *     timer; polling is cheap because the audio thread updates @Volatile
 *     fields without locking.
 *   - **Counters.** Cumulative since process start: configures, flushes,
 *     cross-fades, band changes, drains, passthrough vs DSP buffer hits,
 *     total frames processed. Lets you spot pathological churn (e.g. cross-
 *     fades firing every buffer = upstream bug).
 *   - **Player state + last error.** Same source as the inline panel that
 *     was previously embedded in Settings — kept here because diagnosing
 *     audio issues usually needs both chain state and player state.
 *   - **Recent events.** Last ~50 chain events with timestamps: configure /
 *     flush / cross-fade / passthrough toggle / EOS drain. Acts like a
 *     breadcrumb log for "what happened just before things sounded weird?"
 *   - **Actions.** Copy whole readout to clipboard (paste into a bug
 *     report), reset counters/events, reset audio to defaults.
 */
@Composable
fun AudioDiagnosticsScreen(
    controller: PlayerController,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    // 250 ms poll. The audio thread updates @Volatile fields on every frame;
    // we sample them periodically so the UI doesn't recompose 44k times a
    // second. 250 ms is fast enough for meter responsiveness, slow enough
    // that the rest of the screen doesn't churn.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }
    val playerState by controller.state.collectAsState()
    val audioEnhancement by settings.audioEnhancementEnabled.collectAsState(initial = true)
    val skipSilenceLevel by settings.skipSilenceLevel.collectAsState(initial = 0)

    // Snapshot the volatile fields once per tick so the rendering below sees
    // a consistent set of values within a single recomposition.
    val snap = remember(tick) { TelemetrySnapshot.capture() }
    val events = remember(tick) { AudioChainTelemetry.snapshotEvents() }
    val eq = PlaybackService.sharedEq
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val gainDb = remember(playerState, tick) { eq.currentGainDb() }
    val bands = remember(playerState, tick) { eq.currentBands() }
    val bandsLabel = remember(bands) {
        bands.joinToString(" ") { "%+.0f".format(it.gainDb) }
    }
    val errorVerbose = controller.lastErrorDetails

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio diagnostics") },
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
            HelpCard(
                expanded = helpExpanded,
                onToggle = { helpExpanded = !helpExpanded }
            )
            Spacer(Modifier.height(12.dp))

            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("Chain spec")
                    Text(formatChainSpec(snap), style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Live")
                    Text(formatLive(snap), style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Counters")
                    Text(formatCounters(snap), style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("EQ")
                    Text(
                        "  audio_enhancement = $audioEnhancement\n" +
                            "  master_gain_db   = ${"%+.1f".format(gainDb)}\n" +
                            "  bands_db         = $bandsLabel\n" +
                            "  skip_silence_lvl = $skipSilenceLevel",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Player")
                    val playState = when {
                        playerState.errorMessage != null -> "ERROR"
                        playerState.isBuffering -> "BUFFERING"
                        playerState.isPlaying -> "PLAYING"
                        playerState.isReady -> "READY (paused)"
                        else -> "IDLE"
                    }
                    Text(
                        "  state    = $playState\n" +
                            "  episode  = ${playerState.currentTitle ?: "(none)"}\n" +
                            "  guid     = ${playerState.currentEpisodeGuid ?: "(none)"}\n" +
                            "  speed    = ${"%.2fx".format(playerState.speed)}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Last error")
                    Text(
                        "  ${errorVerbose ?: "(none since last successful play)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorVerbose != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Recent events (newest first)")
                    if (events.isEmpty()) {
                        Text(
                            "  (no events yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(formatEvents(events), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(onClick = {
                    val text = buildClipboardDump(
                        snap = snap,
                        bands = bandsLabel,
                        gainDb = gainDb,
                        audioEnhancement = audioEnhancement,
                        skipSilenceLevel = skipSilenceLevel,
                        playerLine = formatPlayerLine(playerState),
                        errorVerbose = errorVerbose,
                        events = events,
                    )
                    copyToClipboard(ctx, text)
                }) { Text("Copy to clipboard") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { AudioChainTelemetry.resetCountersAndEvents() }) {
                    Text("Reset counters")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                // Defaults: audio_enhancement=on, master_gain=0, bands=FLAT,
                // skip_silence=off, dc_blocker=off. Same recovery path the
                // inline Settings panel offered before this screen existed.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        settings.setAudioEnhancementEnabled(true)
                        settings.setGainDb(0f)
                        settings.setEqBandsCsv(EqPresets.FLAT.joinToString(",") { it.gainDb.toString() })
                        settings.setSkipSilenceLevel(0)
                        settings.setDcBlockerEnabled(false)
                    }
                    eq.setBands(EqPresets.FLAT)
                    eq.setGainDb(0f)
                    eq.setDcBlockerEnabled(false)
                    PlaybackService.sharedSkipSilence.setLevel(0)
                    playerState.currentEpisodeGuid?.let {
                        controller.applyEqOverrideFor(it)
                    }
                }
            }) { Text("Reset audio to defaults") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * Collapsible help card that explains every metric on the diagnostics screen.
 * Lives at the top of the scroll. Closed by default — opens on first tap to
 * a one-line definition per field. Using a card + expand toggle instead of
 * per-row tooltips because the rest of the screen is wrapped in
 * [SelectionContainer]; long-press tooltips would fight the text-selection
 * gesture.
 */
@Composable
private fun HelpCard(expanded: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "What do these mean?",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(HELP_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Single string with one-line definitions for every field on the screen,
 * grouped by section. Plain text so it's selectable + copyable. Kept as a
 * file-level const so the wording can be updated without recompiling the
 * composable layout.
 */
private val HELP_TEXT: String = """
    Chain spec — one-shot snapshot taken when the audio sink is configured.
      input          source format (sample rate / channels / encoding) feeding the chain.
      fir_taps       length of the polyphase FIR used by the 2x oversampler. Longer = sharper transition, more CPU.
      la_window_2x   look-ahead buffer length the limiter holds before output emerges. Tradeoff: longer LA = more pre-warning of peaks, more total chain latency.
      threshold      brick-wall threshold the limiter aims to keep peaks under (in dBFS).
      total_latency  sum of FIR group delay + limiter LA, expressed in 1x-rate frames and ms.
      dc_blocker     pre-EQ ~5 Hz HPF that removes DC offset from broken sources. Off by default.
      master_enabled overall audio-enhancement switch. When false, the chain is full passthrough.

    Live — sampled every 250 ms while the screen is open.
      in_peak     decayed peak meter post-EQ + master gain, pre-upsample. What the limiter actually sees.
      out_peak    decayed peak meter at the chain output, post-downsample. With the limiter behaving, this should not exceed the threshold.
      limiter_GR  current gain reduction in dB. 0 = limiter doing nothing; negative = actively attenuating peaks.
      flags       compact state badges:
                    passthrough = chain bypassed (FLAT EQ + 0 dB gain + DC blocker off)
                    xfade       = EQ band cross-fade window in flight (~46 ms after a band change)
                    dither      = TPDF dither active (only when limiter > 0 dB GR)
                    dcblock     = DC blocker engaged
                    DISABLED    = master switch is off

    Counters — cumulative since process start (or last "Reset counters" tap).
      configures       audio sink configurations the processor has seen. Bumps on each new track if format differs.
      flushes          flushes (typically seeks). Each one zeroes the limiter + oversampler state.
      cross_fades      band-change cross-fades initiated. High frequency = upstream is firing setBands too often.
      band_changes     setBands calls (broader: includes calls that did not actually start a fade).
      eos_drains       end-of-stream drain runs.
      passthrough_bufs vs dsp_bufs: how many input buffers went through the fast passthrough vs the full DSP path.
      frames_processed total 1x-rate frames that have moved through queueInput.

    EQ / Player / Last error — same data the inline panel in Settings shows, kept here so a screenshot of this screen captures the full picture.

    Recent events — circular log of the last ~50 chain transitions. Read newest first; "ago" is wall-clock time since the event.
      configure       audio format set up.
      flush           processor flushed (seek or similar).
      xfade           band cross-fade started.
      passthrough     "enter" / "exit" — chain switched between fast and DSP path.
      eos_drain       end-of-stream drain ran (with frame count).
      dc_blocker      DC blocker toggled.
      format_change   sample rate or channel count changed mid-session.

    Actions:
      Copy to clipboard       dumps every section above as plain text.
      Reset counters          zeroes the counters + clears the event log. Useful before reproducing an issue.
      Reset audio to defaults FLAT EQ, 0 dB gain, skip-silence off, DC blocker off, audio enhancement on.
""".trimIndent()

/**
 * Sampled values from [AudioChainTelemetry]. Captured at one moment so the
 * downstream formatters see a consistent snapshot — without this, separate
 * field reads could disagree across a recomposition (audio thread updates
 * mid-render).
 */
private data class TelemetrySnapshot(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: String,
    val dcBlockerEnabled: Boolean,
    val firTaps: Int,
    val lookAheadSamples2x: Int,
    val thresholdDbfs: Double,
    val totalLatencyFrames1x: Int,
    val totalLatencyMs: Double,
    val inputPeak: Double,
    val outputPeak: Double,
    val reductionDb: Double,
    val fading: Boolean,
    val passthrough: Boolean,
    val ditherActive: Boolean,
    val enabled: Boolean,
    val configures: Int,
    val flushes: Int,
    val crossFades: Int,
    val bandChanges: Int,
    val drains: Int,
    val passthroughBuffers: Long,
    val dspBuffers: Long,
    val framesProcessed: Long,
) {
    companion object {
        fun capture(): TelemetrySnapshot = with(AudioChainTelemetry) {
            TelemetrySnapshot(
                sampleRate = sampleRate,
                channelCount = channelCount,
                encoding = encoding,
                dcBlockerEnabled = dcBlockerEnabled,
                firTaps = firTaps,
                lookAheadSamples2x = lookAheadSamples2x,
                thresholdDbfs = thresholdDbfs,
                totalLatencyFrames1x = totalLatencyFrames1x,
                totalLatencyMs = totalLatencyMs(),
                inputPeak = inputPeak,
                outputPeak = outputPeak,
                reductionDb = reductionDb,
                fading = fading,
                passthrough = passthrough,
                ditherActive = ditherActive,
                enabled = enabled,
                configures = configureCount(),
                flushes = flushCount(),
                crossFades = crossFadeCount(),
                bandChanges = bandChangeCount(),
                drains = drainCount(),
                passthroughBuffers = passthroughBufferCount(),
                dspBuffers = dspBufferCount(),
                framesProcessed = framesProcessedCount(),
            )
        }
    }
}

private fun linearToDb(v: Double): String =
    if (v < 1e-6) "  -inf dBFS"
    else "%+5.1f dBFS".format(20.0 * log10(v))

private fun formatChainSpec(s: TelemetrySnapshot): String {
    if (s.sampleRate == 0) return "  (chain not yet configured)"
    val laMs = s.lookAheadSamples2x.toDouble() / max(1, 2 * s.sampleRate) * 1000.0
    return buildString {
        append("  input            = ${s.sampleRate} Hz / ${s.channelCount} ch / ${s.encoding}\n")
        append("  fir_taps         = ${s.firTaps} per stage (up + down)\n")
        append("  la_window_2x     = ${s.lookAheadSamples2x} samples (~${"%.2f".format(laMs)} ms)\n")
        append("  threshold        = ${"%.1f".format(s.thresholdDbfs)} dBFS\n")
        append("  total_latency    = ${s.totalLatencyFrames1x} frames @1x (~${"%.2f".format(s.totalLatencyMs)} ms)\n")
        append("  dc_blocker       = ${if (s.dcBlockerEnabled) "on" else "off"}\n")
        append("  master_enabled   = ${s.enabled}")
    }
}

private fun formatLive(s: TelemetrySnapshot): String = buildString {
    append("  in_peak          = ${linearToDb(s.inputPeak)}\n")
    append("  out_peak         = ${linearToDb(s.outputPeak)}\n")
    append("  limiter_GR       = ${"%5.2f".format(s.reductionDb)} dB\n")
    append("  flags            = ")
    val flags = buildList {
        if (s.passthrough) add("passthrough")
        if (s.fading) add("xfade")
        if (s.ditherActive) add("dither")
        if (s.dcBlockerEnabled) add("dcblock")
        if (!s.enabled) add("DISABLED")
    }
    append(if (flags.isEmpty()) "(none — full DSP, transparent)" else flags.joinToString(", "))
}

private fun formatCounters(s: TelemetrySnapshot): String {
    val totalBuffers = s.passthroughBuffers + s.dspBuffers
    val passPct = if (totalBuffers > 0) {
        "%.1f%%".format(100.0 * s.passthroughBuffers / totalBuffers)
    } else "n/a"
    return buildString {
        append("  configures       = ${s.configures}\n")
        append("  flushes          = ${s.flushes}\n")
        append("  cross_fades      = ${s.crossFades}\n")
        append("  band_changes     = ${s.bandChanges}\n")
        append("  eos_drains       = ${s.drains}\n")
        append("  passthrough_bufs = ${s.passthroughBuffers} ($passPct)\n")
        append("  dsp_bufs         = ${s.dspBuffers}\n")
        append("  frames_processed = ${s.framesProcessed}")
    }
}

private fun formatEvents(events: List<AudioChainTelemetry.Event>): String {
    val now = System.currentTimeMillis()
    return events.joinToString("\n") { e ->
        val agoSec = (now - e.timestampMs) / 1000.0
        val ago = when {
            agoSec < 1.0 -> "<1s"
            agoSec < 60.0 -> "${"%.0f".format(agoSec)}s"
            agoSec < 3600.0 -> "${"%.0f".format(agoSec / 60.0)}m"
            else -> "${"%.0f".format(agoSec / 3600.0)}h"
        }
        val padded = ago.padStart(5)
        if (e.detail.isEmpty()) "  $padded ago  ${e.kind}"
        else "  $padded ago  ${e.kind}: ${e.detail}"
    }
}

private fun formatPlayerLine(p: com.lofipod.app.player.PlayerState): String {
    val playState = when {
        p.errorMessage != null -> "ERROR"
        p.isBuffering -> "BUFFERING"
        p.isPlaying -> "PLAYING"
        p.isReady -> "READY (paused)"
        else -> "IDLE"
    }
    return "$playState — ${p.currentTitle ?: "(none)"} — speed ${"%.2fx".format(p.speed)}"
}

/** Compact one-call clipboard dump. Plain text; sections separated by blank
 *  lines so paste targets (issue trackers, chat) render readably. */
private fun buildClipboardDump(
    snap: TelemetrySnapshot,
    bands: String,
    gainDb: Float,
    audioEnhancement: Boolean,
    skipSilenceLevel: Int,
    playerLine: String,
    errorVerbose: String?,
    events: List<AudioChainTelemetry.Event>,
): String = buildString {
    appendLine("LofiPod audio diagnostics")
    appendLine("=========================")
    appendLine()
    appendLine("[Chain spec]")
    appendLine(formatChainSpec(snap))
    appendLine()
    appendLine("[Live]")
    appendLine(formatLive(snap))
    appendLine()
    appendLine("[Counters]")
    appendLine(formatCounters(snap))
    appendLine()
    appendLine("[EQ]")
    appendLine("  audio_enhancement = $audioEnhancement")
    appendLine("  master_gain_db    = ${"%+.1f".format(gainDb)}")
    appendLine("  bands_db          = $bands")
    appendLine("  skip_silence_lvl  = $skipSilenceLevel")
    appendLine()
    appendLine("[Player]")
    appendLine("  $playerLine")
    appendLine()
    appendLine("[Last error]")
    appendLine("  ${errorVerbose ?: "(none)"}")
    appendLine()
    appendLine("[Recent events]")
    if (events.isEmpty()) appendLine("  (no events)")
    else appendLine(formatEvents(events))
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LofiPod audio diagnostics", text))
    Toast.makeText(ctx, "Diagnostics copied", Toast.LENGTH_SHORT).show()
}
