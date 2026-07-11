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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.audio.AudioChainTelemetry
import com.lofipod.app.audio.EqPresets
import com.lofipod.app.diagnostics.AppDiagnostics
import com.lofipod.app.diagnostics.StartupTimings
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
    val timing = remember(tick) { AudioChainTelemetry.computeBufferTimingStats() }
    val events = remember(tick) { AudioChainTelemetry.snapshotEvents() }
    val startupPhases = remember(tick) { StartupTimings.snapshot() }
    // Stall-watchdog firings: filtered AppDiagnostics entries. Surfaced
    // here (not just buried in Settings → App diagnostics → Other) so the
    // user can see at a glance whether the renderer is repeatedly
    // recovering from underruns at high playback speeds.
    val appEvents by AppDiagnostics.entries.collectAsState()
    val stallEvents = remember(appEvents) {
        appEvents.filter {
            it.category == AppDiagnostics.Category.OTHER && it.identifier == "renderer_stall"
        }
    }
    // Playback breadcrumbs (track changes, download handoffs in either
    // direction, auto-download deferrals, wake-lock oscillation, feed-
    // aware back-nav). Newest first.
    val playbackEvents = remember(appEvents) {
        appEvents.filter { it.category == AppDiagnostics.Category.PLAYBACK }
    }
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
                    SectionLabel("Performance")
                    Text(formatPerformance(snap, timing), style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Scheduler")
                    Text(formatScheduler(snap), style = MaterialTheme.typography.bodySmall)

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

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Stall watchdog (newest first)")
                    if (stallEvents.isEmpty()) {
                        Text(
                            "  (no renderer stalls recorded — chain is keeping up)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(formatStallEvents(stallEvents), style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Playback events (newest first)")
                    if (playbackEvents.isEmpty()) {
                        Text(
                            "  (no playback events yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(formatPlaybackEvents(playbackEvents), style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Startup")
                    if (startupPhases.isEmpty()) {
                        Text(
                            "  (no phases recorded yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(formatStartup(startupPhases), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(onClick = {
                    val text = buildClipboardDump(
                        snap = snap,
                        timing = timing,
                        bands = bandsLabel,
                        gainDb = gainDb,
                        audioEnhancement = audioEnhancement,
                        skipSilenceLevel = skipSilenceLevel,
                        playerLine = formatPlayerLine(playerState),
                        errorVerbose = errorVerbose,
                        events = events,
                        stallEvents = stallEvents,
                        playbackEvents = playbackEvents,
                        startupPhases = startupPhases,
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
                // skip_silence=off, dc_blocker=off, tone filters off. Same
                // recovery path the inline Settings panel offered before
                // this screen existed.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        settings.setAudioEnhancementEnabled(true)
                        settings.setGainDb(0f)
                        settings.setEqBandsCsv(EqPresets.FLAT.joinToString(",") { it.gainDb.toString() })
                        settings.setSkipSilenceLevel(0)
                        settings.setPauseSkipSensitivity(
                            com.lofipod.app.audio.PauseTapProcessor.DEFAULT_SENSITIVITY
                        )
                        settings.setDcBlockerEnabled(false)
                        settings.setToneLowCutHz(0f)
                        settings.setToneHighCutHz(0f)
                        settings.setToneTiltDb(0f)
                        settings.setToneLowCutSteep(false)
                        settings.setVoiceDeEsserLevel(0)
                        settings.setVoiceWarmthLevel(0)
                        settings.setVoiceLevelerLevel(0)
                        settings.setVoiceAirLevel(0)
                    }
                    eq.setBands(EqPresets.FLAT)
                    eq.setGainDb(0f)
                    eq.setDcBlockerEnabled(false)
                    eq.setLowCutHz(0f)
                    eq.setHighCutHz(0f)
                    eq.setTiltDb(0f)
                    eq.setLowCutSteep(false)
                    eq.setDeEsserLevel(0)
                    eq.setWarmthLevel(0)
                    eq.setLevelerLevel(0)
                    eq.setAirLevel(0)
                    PlaybackService.sharedSkipSilence.setLevel(0)
                    PlaybackService.sharedPauseTap.setSensitivity(
                        com.lofipod.app.audio.PauseTapProcessor.DEFAULT_SENSITIVITY
                    )
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
                    if (expanded) painterResource(R.drawable.expand_less_24) else painterResource(R.drawable.expand_more_24),
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

    Performance — wallclock measurements around the DSP path's queueInput. Read these to see if the audio thread is keeping up with real time, especially in pocket / screen-off / thermal-throttled conditions.
      last_proc       processing time of the most recent buffer.
      avg_proc / p95  rolling stats over the last ~64 buffers.
      max_proc        peak observed since last counters reset.
      load_factor     processing / audio-time, as a percentage. 5% means the chain finished in 5% of the audio's duration (95% headroom). Values approaching 100% mean the audio thread is saturated and underruns are imminent.

    Scheduler — OS-level facts about the thread running the DSP chain.
      audio_thread     Linux TID + Java thread name of the audio sink thread. Captured on the first buffer; bumps if the sink is rebuilt mid-session.
      thread_priority  Process.getThreadPriority for that TID. Should be -16 (AUDIO) or -19 (URGENT_AUDIO). 0 (DEFAULT) means the chain is competing with normal app threads for CPU and underruns are expected on any contention — that's a bug.
      demotions        How many times the priority has been observed >= 0 (i.e. demoted out of audio priority) since process start. Each transition logs a 'priority_demoted' (and matching 'priority_recovered') event in Recent events with a timestamp — so if this number is non-zero, scroll down to the event log to see when each demotion happened.
      perf_hint        State of the Android 12+ PerformanceHintManager wiring. When "active", the OS scheduler keeps the CPU at the right frequency for the audio chain's measured workload — fewer downclock-then-spinup events and fewer max-load spikes during sustained playback. "unsupported" on API <31 or OEMs without the service.

    EQ / Player / Last error — same data the inline panel in Settings shows, kept here so a screenshot of this screen captures the full picture.

    Recent events — circular log of the last ~50 chain transitions. Read newest first; "ago" is wall-clock time since the event.
      configure       audio format set up.
      flush           processor flushed (seek or similar).
      xfade           band cross-fade started.
      passthrough     "enter" / "exit" — chain switched between fast and DSP path.
      eos_drain       end-of-stream drain ran (with frame count).
      dc_blocker      DC blocker toggled.
      format_change   sample rate or channel count changed mid-session.

    Stall watchdog — three independent arms (A/B/C) detect renderer underruns:
      no_progress_Ns           Arm A: PLAYING but currentPosition hasn't advanced for N seconds.
      buffering_Ns             Arm B: chronic STATE_BUFFERING with playWhenReady=true for N seconds.
      sticky_Npct_over_Ms      Arm C: over the last M seconds, actual position advance was N% of what speed-adjusted wall-clock would predict. Survives BUFFERING<->READY oscillation that resets arms A and B.

    Playback events — back-end-readable breadcrumbs separate from the audio chain log. Read newest first.
      track_change             Episode swap. Includes from-guid, to-guid, autoplay flag, target feed URL.
      handoff_forward          Streaming -> downloaded file swap fired (download completed mid-playback).
      handoff_reverse          Downloaded file -> streaming swap fired (download removed mid-playback).
      auto_download_deferred   (v0.10.12 only — removed in v0.10.14) Auto-download skipped inline because screen-off + autoplay.
      auto_download_delayed_fire  v0.10.14+ replacement: autoplay-induced auto-download started after the configured settle delay (15s default).
      auto_download_delayed_skip  Scheduled delayed-fire ran but the download was already in flight / completed / manually started in the interim — no-op.
      wake_lock_oscillation    Wake-lock acquired N times in 30s — isPlaying flip-flopping, corroborates arm C.
      back_nav_feed_aware      Back-from-Player routed to episodes/{currentFeed}.
      back_nav_fallback        Back-from-Player feedUrl unknown — used plain smartBack.
      autoplay_confirm_remote  Remote-controller (BT, vehicle, notification) play/pause intercepted as autoplay confirm. v0.10.13+: our own process's pauses no longer trigger this.
      controller_release_failed  Media3's MediaController.release threw on activity destroy (usually IllegalArgumentException "Service not registered" — framework race, swallowed since we're tearing down anyway).
      audio_underrun_window    AudioTrack underran N times in 30s — worst gap M ms vs buffer K ms. Kernel-layer signal: the audio HAL ran dry, not just BUFFERING<->READY oscillation. If this fires repeatedly the DSP chain isn't keeping up.
      audio_sink_error         ExoPlayer's DefaultAudioSink threw — Media3 chain-level failure.
      audio_codec_error        MediaCodec (decoder) threw — codec-level failure. If accompanied by mp3-decoder events look at Fix #4 (software decoder preference).
      audio_decoder_init       Which decoder Media3 picked for the current track. v0.10.13+ prefers non-c2.android.* MP3 decoders.

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
    val deEsserGainLin: Double,
    val levelerGainLin: Double,
    val configures: Int,
    val flushes: Int,
    val crossFades: Int,
    val bandChanges: Int,
    val drains: Int,
    val passthroughBuffers: Long,
    val dspBuffers: Long,
    val framesProcessed: Long,
    val lastBufferProcessingNs: Long,
    val lastBufferAudioNs: Long,
    val maxBufferProcessingNs: Long,
    val audioThreadId: Int,
    val audioThreadName: String,
    val audioThreadPriority: Int,
    val perfHintApiSupported: Boolean,
    val perfHintSessionActive: Boolean,
    val perfHintTargetNs: Long,
    val perfHintLastError: String?,
    val priorityDemotions: Int,
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
                deEsserGainLin = deEsserGainLin,
                levelerGainLin = levelerGainLin,
                configures = configureCount(),
                flushes = flushCount(),
                crossFades = crossFadeCount(),
                bandChanges = bandChangeCount(),
                drains = drainCount(),
                passthroughBuffers = passthroughBufferCount(),
                dspBuffers = dspBufferCount(),
                framesProcessed = framesProcessedCount(),
                lastBufferProcessingNs = lastBufferProcessingNs,
                lastBufferAudioNs = lastBufferAudioNs,
                maxBufferProcessingNs = maxBufferProcessingNs,
                audioThreadId = audioThreadId,
                audioThreadName = audioThreadName,
                audioThreadPriority = audioThreadPriority,
                perfHintApiSupported = perfHintApiSupported,
                perfHintSessionActive = perfHintSessionActive,
                perfHintTargetNs = perfHintTargetNs,
                perfHintLastError = perfHintLastError,
                priorityDemotions = priorityDemotionCount(),
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
    val phaseMode = com.lofipod.app.player.PlaybackService.sharedEq.currentPhaseMode()
    val firUs = com.lofipod.app.player.PlaybackService.sharedEq.getChainLatencyUs()
    return buildString {
        append("  input            = ${s.sampleRate} Hz / ${s.channelCount} ch / ${s.encoding}\n")
        append("  phase_mode       = ${phaseModeLabel(phaseMode)}\n")
        append(formatPhaseModeBlock(phaseMode))
        append("  fir_taps         = ${s.firTaps} per stage (up + down)\n")
        append("  la_window_2x     = ${s.lookAheadSamples2x} samples (~${"%.2f".format(laMs)} ms)\n")
        append("  threshold        = ${"%.1f".format(s.thresholdDbfs)} dBFS\n")
        append("  chain_latency    = ~${"%.2f".format(firUs / 1000.0)} ms total (live, mode-aware)\n")
        append("  postEQ_latency   = ${s.totalLatencyFrames1x} frames @1x (~${"%.2f".format(s.totalLatencyMs)} ms)\n")
        append("  dc_blocker       = ${if (s.dcBlockerEnabled) "on" else "off"}\n")
        val eq = com.lofipod.app.player.PlaybackService.sharedEq
        append(
            "  voice_suite      = deesser L${eq.currentDeEsserLevel()}, " +
                "warmth L${eq.currentWarmthLevel()}, " +
                "leveler L${eq.currentLevelerLevel()}, " +
                "air L${eq.currentAirLevel()}\n"
        )
        append(
            "  low_cut_slope    = ${if (eq.currentLowCutSteep()) "24" else "12"} dB/oct\n"
        )
        append("  master_enabled   = ${s.enabled}")
    }
}

private fun phaseModeLabel(m: com.lofipod.app.audio.PhaseMode): String = when (m) {
    com.lofipod.app.audio.PhaseMode.PURE_IIR -> "Pure IIR (10-band biquad cascade)"
    com.lofipod.app.audio.PhaseMode.MIN_FIR -> "Min-Phase FIR (UPC + real-cepstrum kernel)"
    com.lofipod.app.audio.PhaseMode.LINEAR_FIR -> "Linear-Phase FIR (UPC + symmetric kernel)"
    com.lofipod.app.audio.PhaseMode.MIXED -> "Mixed-Phase (min < 120 Hz, linear > 120 Hz, UPC)"
}

/** Per-mode chain spec block. Pure IIR is brief (the rest of the chain
 *  spec already covers its main details); the three FIR modes get the
 *  full UPC + kernel-synth detail line so users can see what's running. */
private fun formatPhaseModeBlock(m: com.lofipod.app.audio.PhaseMode): String =
    when (m) {
        com.lofipod.app.audio.PhaseMode.PURE_IIR -> ""  // nothing extra
        com.lofipod.app.audio.PhaseMode.MIN_FIR -> buildString {
            append("  kernel           = 4096 taps, real-cepstrum-derived (causal, min-phase)\n")
            append("  convolution      = UPC, 4 partitions x 1024 samples, FFT_SIZE=2048\n")
            append("  fft_lib          = PFFFT (arm64 NEON SIMD, single precision)\n")
            append("  pre_ringing      = none\n")
        }
        com.lofipod.app.audio.PhaseMode.LINEAR_FIR -> buildString {
            append("  kernel           = 4096 taps, symmetric (zero-phase, Kaiser-windowed)\n")
            append("  convolution      = UPC, 4 partitions x 1024 samples, FFT_SIZE=2048\n")
            append("  fft_lib          = PFFFT (arm64 NEON SIMD, single precision)\n")
            append("  pre_ringing      = yes (symmetric, ~46 ms group delay)\n")
        }
        com.lofipod.app.audio.PhaseMode.MIXED -> buildString {
            append("  kernel           = 4096 taps, hybrid (min-phase low + linear high)\n")
            append("  crossover        = 80-180 Hz cosine ramp (complementary, sum-to-1)\n")
            append("  convolution      = UPC, 4 partitions x 1024 samples, FFT_SIZE=2048\n")
            append("  fft_lib          = PFFFT (arm64 NEON SIMD, single precision)\n")
            append("  pre_ringing      = bass band only\n")
        }
    }

private fun formatLive(s: TelemetrySnapshot): String = buildString {
    append("  in_peak          = ${linearToDb(s.inputPeak)}\n")
    append("  out_peak         = ${linearToDb(s.outputPeak)}\n")
    append("  limiter_GR       = ${"%5.2f".format(s.reductionDb)} dB\n")
    // Voice suite: de-esser high-band GR + leveler ride gain, in dB.
    // Both read 0.00 while their stage is off (gain mirror rests at 1.0).
    append("  deesser_GR       = ${"%5.2f".format(20.0 * log10(s.deEsserGainLin.coerceAtLeast(1e-6)))} dB\n")
    append("  leveler_gain     = ${"%+5.2f".format(20.0 * log10(s.levelerGainLin.coerceAtLeast(1e-6)))} dB\n")
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

/**
 * Format the Scheduler section: audio thread identity + priority + the
 * Performance Hint API status. Surfaces two distinct things the user (or
 * a bug-report reader) needs to triage stutters:
 *   1. Is the DSP actually running at THREAD_PRIORITY_AUDIO (= -16)? If
 *      not, it's competing with normal app threads and underruns are
 *      expected on any CPU contention.
 *   2. Did we successfully wire up PerformanceHintManager? On a supported
 *      device with an active session, the OS scheduler keeps the CPU at
 *      the right frequency for the workload — visible as fewer max-load
 *      spikes after sustained playback.
 */
private fun formatScheduler(s: TelemetrySnapshot): String {
    if (s.audioThreadId <= 0) {
        return "  (no audio buffers processed yet — thread info captured on first buffer)"
    }
    val priorityLabel = when (s.audioThreadPriority) {
        Int.MIN_VALUE -> "?"
        // Process.THREAD_PRIORITY_* constants; the audio sink's BG callback
        // thread should land on AUDIO (-16) or URGENT_AUDIO (-19).
        -19 -> "${s.audioThreadPriority} (URGENT_AUDIO — best)"
        -16 -> "${s.audioThreadPriority} (AUDIO — expected)"
        in -15..-1 -> "${s.audioThreadPriority} (above normal)"
        0 -> "${s.audioThreadPriority} (DEFAULT — WRONG, thread should be AUDIO)"
        in 1..19 -> "${s.audioThreadPriority} (BELOW normal — WRONG)"
        else -> s.audioThreadPriority.toString()
    }
    val hintLine = if (!s.perfHintApiSupported) {
        "unsupported (Android 12+ required — running on older / OEM without service)"
    } else if (!s.perfHintSessionActive) {
        val err = s.perfHintLastError
        if (err != null) "supported but no active session ($err)"
        else "supported, no session yet (will create on first buffer)"
    } else {
        val targetMs = s.perfHintTargetNs / 1_000_000.0
        "active, target = ${"%.2f".format(targetMs)} ms / buffer"
    }
    return buildString {
        append("  audio_thread     = tid=${s.audioThreadId} name='${s.audioThreadName}'\n")
        append("  thread_priority  = $priorityLabel\n")
        append("  demotions        = ${s.priorityDemotions}")
        if (s.priorityDemotions > 0) {
            append("  (see 'priority_demoted' in Recent events for timestamps)")
        }
        append("\n  perf_hint        = $hintLine")
        s.perfHintLastError?.let { append("\n  perf_hint_error  = $it") }
    }
}

private fun formatPerformance(
    s: TelemetrySnapshot,
    timing: AudioChainTelemetry.BufferTimingStats,
): String {
    if (timing.sampleCount == 0 || s.lastBufferAudioNs == 0L) {
        return "  (no DSP buffers processed yet)"
    }
    fun nsToMs(ns: Long): String = "%.2f ms".format(ns / 1_000_000.0)
    fun pct(d: Double): String = "%.1f%%".format(d * 100.0)
    val lastLoad = if (s.lastBufferAudioNs > 0) {
        s.lastBufferProcessingNs.toDouble() / s.lastBufferAudioNs
    } else 0.0
    return buildString {
        append("  last_proc       = ${nsToMs(s.lastBufferProcessingNs)} for ${nsToMs(s.lastBufferAudioNs)} of audio\n")
        append("  avg_proc        = ${nsToMs(timing.avgProcessingNs)}\n")
        append("  p95_proc        = ${nsToMs(timing.p95ProcessingNs)}\n")
        append("  max_proc        = ${nsToMs(s.maxBufferProcessingNs)}  (since last reset)\n")
        append("  last_load       = ${pct(lastLoad)}  (processing / audio-time)\n")
        append("  avg_load        = ${pct(timing.avgLoadFactor)}\n")
        append("  max_load        = ${pct(timing.maxLoadFactor)}\n")
        append("  samples         = ${timing.sampleCount} buffers in window")
    }
}

/**
 * Format the startup-phase list as a small fixed-width table. Each row
 * shows the phase name padded to a fixed width, followed by its duration
 * in ms. The list is already sorted by start time so the order shows the
 * sequence of operations during boot. A summary row at the bottom shows
 * the total wallclock since [StartupTimings.processStartNs].
 */
private fun formatStartup(phases: List<StartupTimings.Phase>): String {
    val nameWidth = phases.maxOf { it.name.length }.coerceAtLeast(20)
    val totalMs = (System.nanoTime() - StartupTimings.processStartNs) / 1_000_000.0
    return buildString {
        for (p in phases) {
            append("  ")
            append(p.name.padEnd(nameWidth))
            append("  ")
            append("%6.1f ms".format(p.durationMs))
            append("  @")
            val offsetMs = (p.startNs - StartupTimings.processStartNs) / 1_000_000.0
            append("%6.0f".format(offsetMs))
            append(" ms")
            append('\n')
        }
        append("  ")
        append("(elapsed since process start)".padEnd(nameWidth))
        append("  ")
        append("%6.1f ms".format(totalMs))
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

/**
 * Same shape as [formatEvents] but for [AppDiagnostics.Entry] rows — used
 * to render the stall-watchdog firings. Detail is the explanation already
 * built by the watchdog (position + speed at recovery time).
 */
private fun formatStallEvents(events: List<AppDiagnostics.Entry>): String {
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
        "  $padded ago  ${e.detail}"
    }
}

/**
 * Same shape as [formatStallEvents]/[formatEvents], but renders the
 * PLAYBACK breadcrumb events (track changes, handoffs, oscillation,
 * deferred downloads, feed-aware back-nav). [identifier] is included
 * so a back-end reader can grep for specific event kinds.
 */
private fun formatPlaybackEvents(events: List<AppDiagnostics.Entry>): String {
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
        "  $padded ago  ${e.identifier}: ${e.detail}"
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
    timing: AudioChainTelemetry.BufferTimingStats,
    bands: String,
    gainDb: Float,
    audioEnhancement: Boolean,
    skipSilenceLevel: Int,
    playerLine: String,
    errorVerbose: String?,
    events: List<AudioChainTelemetry.Event>,
    stallEvents: List<AppDiagnostics.Entry>,
    playbackEvents: List<AppDiagnostics.Entry>,
    startupPhases: List<StartupTimings.Phase>,
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
    appendLine("[Performance]")
    appendLine(formatPerformance(snap, timing))
    appendLine()
    appendLine("[Scheduler]")
    appendLine(formatScheduler(snap))
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
    appendLine()
    appendLine("[Stall watchdog]")
    if (stallEvents.isEmpty()) appendLine("  (no stalls)")
    else appendLine(formatStallEvents(stallEvents))
    appendLine()
    appendLine("[Playback events]")
    if (playbackEvents.isEmpty()) appendLine("  (no playback events)")
    else appendLine(formatPlaybackEvents(playbackEvents))
    appendLine()
    appendLine("[Startup]")
    if (startupPhases.isEmpty()) appendLine("  (no phases recorded)")
    else appendLine(formatStartup(startupPhases))
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LofiPod audio diagnostics", text))
    Toast.makeText(ctx, "Diagnostics copied", Toast.LENGTH_SHORT).show()
}
