@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.AudioChainTelemetry
import com.lofipod.app.data.Settings
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max

/**
 * In-Player diagnostics tab. Enabled via Settings → Audio diagnostics →
 * "Show Diagnostics tab on Player". When the user is actively listening and
 * something sounds wrong, this is the fastest path to live readouts.
 *
 * Content (top-down):
 *  - **Watchpoints**: glanceable green/yellow/red chips for each subsystem
 *    (audio thread priority, PerfHint, wake lock, load factor, recent
 *    stalls, player error). One chip = one mode of failure.
 *  - **Actions**: full-screen toggle, save-snapshot-as-note,
 *    copy-snapshot-to-clipboard.
 *  - **Live readout**: compact text dump of the values that matter most
 *    while live (phase mode, speed, thread, PerfHint, wake lock, load
 *    factor, last error). Selectable so a user can copy any single value.
 *  - **Load-factor sparkline**: rolling 30-sample bar of avgLoadFactor so
 *    a trend toward 1.0 is visible at a glance rather than requiring the
 *    user to compute the trend in their head from a single point reading.
 *  - **Recent events**: newest-first slice of the breadcrumb log.
 *
 * Auto-snapshot: when a high-signal event fires
 * ([priority_demoted], [renderer_stall], or a player error), the tab pins
 * a snapshot-note to the currently-playing episode at the current playback
 * position — so forensic evidence is captured the moment a bug happens,
 * not whenever the user remembers to come look. Rate-limited to one
 * auto-snapshot per minute total so a sustained demotion doesn't spam
 * Notes with dozens of duplicates.
 *
 * Full-screen mode: a separate sibling composable [FullScreenDiagnostics]
 * renders the same content sans the player chrome above the tab strip.
 * MainActivity reads the full-screen flag (lifted into a CompositionLocal
 * — see [LocalDiagnosticsFullScreen]) to keep the mini-player visible
 * at the bottom during full-screen mode for transport.
 */
@Composable
fun PlayerDiagnosticsTab(
    episodeGuid: String?,
    controller: PlayerController,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val playerState by controller.state.collectAsState()
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    // 500 ms poll matches AudioDiagnosticsScreen. Audio thread writes
    // @Volatile fields on every frame; UI samples periodically so we don't
    // recompose 44k times a second.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }

    val snap = remember(tick) { LiveDiagnosticSnap.capture() }
    val events = remember(tick) { AudioChainTelemetry.snapshotEvents() }

    // Rolling sparkline buffer for avgLoadFactor. ~60 samples = 30s at the
    // 500 ms tick rate. Capped to a fixed size; oldest drops off the front.
    // Kept as a remember-state list so recomposition picks up the new
    // sample each tick without mutating any global.
    val loadHistory = remember { mutableStateOf<List<Double>>(emptyList()) }
    LaunchedEffect(tick) {
        val bts = AudioChainTelemetry.computeBufferTimingStats()
        if (bts.sampleCount > 0) {
            val updated = (loadHistory.value + bts.avgLoadFactor).takeLast(SPARKLINE_CAPACITY)
            loadHistory.value = updated
        }
    }

    // Auto-snapshot bookkeeping. Track the timestamp of the most recent
    // event we've inspected — robust to the underlying 50-slot ring's
    // wrap-around evictions in a way a size-based count is NOT (when the
    // ring is at capacity, every new event keeps size constant at 50 and
    // a size-diff check fires zero times even as new events stream in).
    //
    // The -1L sentinel "no baseline yet" gates the first observation: we
    // SET the baseline from the most-recent event but don't fire an
    // auto-snapshot, so opening the tab in the middle of a sustained
    // demotion doesn't snapshot the historical event the moment the user
    // arrives.
    var lastSeenEventTsMs by remember { mutableStateOf(-1L) }
    var lastAutoSnapshotAtMs by remember { mutableStateOf(0L) }
    var autoSnapshotMessage by remember { mutableStateOf<String?>(null) }
    val newestEventTs = events.firstOrNull()?.timestampMs
    LaunchedEffect(newestEventTs, episodeGuid) {
        if (episodeGuid == null) return@LaunchedEffect
        val newest = events.firstOrNull() ?: return@LaunchedEffect
        if (lastSeenEventTsMs < 0L) {
            lastSeenEventTsMs = newest.timestampMs
            return@LaunchedEffect
        }
        if (newest.timestampMs <= lastSeenEventTsMs) return@LaunchedEffect

        // takeWhile against monotonic timestamps yields the strictly-new
        // events in newest-first order. Note: timestamps are millisecond-
        // granular so two events captured in the same ms by the audio
        // thread would tie; the `<=` skip on equal timestamps means we'd
        // miss the second of a tied pair. Acceptable: events at this
        // granularity are themselves the SAME human-visible moment and
        // one snapshot covers both.
        val newSlice = events.takeWhile { it.timestampMs > lastSeenEventTsMs }
        lastSeenEventTsMs = newest.timestampMs
        val triggering = newSlice.firstOrNull { e ->
            e.kind in AUTO_SNAPSHOT_TRIGGER_KINDS
        } ?: return@LaunchedEffect

        val now = System.currentTimeMillis()
        if (now - lastAutoSnapshotAtMs < AUTO_SNAPSHOT_MIN_INTERVAL_MS) return@LaunchedEffect
        lastAutoSnapshotAtMs = now

        val text = buildSnapshotText(
            reason = "auto: ${triggering.kind} — ${triggering.detail.ifEmpty { "(no detail)" }}",
            playerState = controller.state.value,
            errorVerbose = controller.lastErrorDetails,
            snap = snap,
            events = events,
            loadHistory = loadHistory.value,
        )
        val posMs = controller.currentPositionMs()
        withContext(Dispatchers.IO) {
            app.db.episodeNoteEntryDao().upsert(
                EpisodeNoteEntryEntity(
                    guid = episodeGuid,
                    createdAt = now,
                    playbackPosMs = posMs,
                    text = text,
                )
            )
        }
        autoSnapshotMessage = "Auto-snapshot saved (${triggering.kind})"
    }
    // Clear the toast banner after a moment so it doesn't pin forever.
    LaunchedEffect(autoSnapshotMessage) {
        if (autoSnapshotMessage != null) {
            delay(3_500)
            autoSnapshotMessage = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        WatchpointsRow(playerState = playerState, snap = snap, events = events)

        Spacer(Modifier.height(10.dp))

        ActionsRow(
            isFullScreen = isFullScreen,
            onToggleFullScreen = onToggleFullScreen,
            onCopyToClipboard = {
                val text = buildSnapshotText(
                    reason = "manual",
                    playerState = controller.state.value,
                    errorVerbose = controller.lastErrorDetails,
                    snap = snap,
                    events = events,
                    loadHistory = loadHistory.value,
                )
                copyToClipboard(ctx, text)
            },
            saveAsNoteEnabled = episodeGuid != null,
            onSaveAsNote = saveAsNote@{
                val guid = episodeGuid ?: return@saveAsNote
                val text = buildSnapshotText(
                    reason = "manual",
                    playerState = controller.state.value,
                    errorVerbose = controller.lastErrorDetails,
                    snap = snap,
                    events = events,
                    loadHistory = loadHistory.value,
                )
                val posMs = controller.currentPositionMs()
                val nowMs = System.currentTimeMillis()
                // Fire-and-forget into the activity scope via the
                // controller's same approach: use a coroutine on the
                // app's main scope so the DB write doesn't block the UI.
                // Show a Toast for immediate feedback since the tab
                // doesn't host a SnackbarHost (PlayerScreen owns one but
                // we'd need to plumb the host down here — Toast is the
                // pragmatic short path).
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.episodeNoteEntryDao().upsert(
                            EpisodeNoteEntryEntity(
                                guid = guid,
                                createdAt = nowMs,
                                playbackPosMs = posMs,
                                text = text,
                            )
                        )
                    }
                    Toast.makeText(ctx, "Diagnostic snapshot saved to notes", Toast.LENGTH_SHORT).show()
                }
            },
        )

        autoSnapshotMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionLabel("Live")
        SelectionContainer {
            Text(
                formatLiveBlock(snap, playerState, controller.lastErrorDetails),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionLabel("Load factor (last ${SPARKLINE_CAPACITY / 2}s, ~500 ms each)")
        Spacer(Modifier.height(4.dp))
        LoadFactorSparkline(history = loadHistory.value)

        Spacer(Modifier.height(12.dp))

        SectionLabel("Recent events")
        if (events.isEmpty()) {
            Text(
                "(no events yet)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Cap at 12 in the player-tab context so the strip stays
            // readable without scrolling forever. Full event log is one
            // tap away on the Settings → Audio diagnostics screen.
            SelectionContainer {
                Text(
                    formatEventsCompact(events.take(12)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// Full-screen mode is in-band: the same [PlayerDiagnosticsTab] renders
// while [isFullScreen] is true. The surrounding PlayerScreen hides the
// player chrome above the tab strip (artwork / transport / scrubber)
// when its tabsFullscreen flag is on, and MainActivity keeps the
// mini-player anchored to the bottom so transport stays reachable.
// One composable, two layouts — no separate full-screen surface.

// ---------------------------------------------------------------------------
// Watchpoints
// ---------------------------------------------------------------------------

private enum class WatchpointStatus { OK, WARN, BAD, NEUTRAL }

private data class Watchpoint(
    val label: String,
    val status: WatchpointStatus,
    val tooltip: String,
)

@Composable
private fun WatchpointsRow(
    playerState: com.lofipod.app.player.PlayerState,
    snap: LiveDiagnosticSnap,
    events: List<AudioChainTelemetry.Event>,
) {
    val nowMs = System.currentTimeMillis()
    val recentStallSeconds = 60L
    val recentStallCount = events.count { e ->
        nowMs - e.timestampMs < recentStallSeconds * 1000 &&
            (e.kind == "renderer_stall" || e.kind == "no_progress" || e.kind.startsWith("buffering_"))
    }
    val watchpoints = buildList {
        add(audioThreadWatchpoint(snap))
        add(perfHintWatchpoint(snap))
        add(wakeLockWatchpoint(playerState, snap))
        add(loadFactorWatchpoint(snap))
        add(stallWatchpoint(recentStallCount))
        add(playerStateWatchpoint(playerState))
    }
    Column {
        SectionLabel("Watchpoints")
        Spacer(Modifier.height(6.dp))
        // Two columns so the chips stay readable on narrow devices. Order
        // is severity-first within each column would be nice but a stable
        // categorical order (thread, perf hint, wake, load, stall, player)
        // matches how a user reads them top-to-bottom for triage.
        FlowOrColumn(watchpoints)
    }
}

@Composable
private fun FlowOrColumn(items: List<Watchpoint>) {
    // Simple two-per-row layout. Doesn't use FlowRow because each chip's
    // content width varies and we want predictable wrapping.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { wp ->
                    WatchpointChip(wp, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WatchpointChip(wp: Watchpoint, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (wp.status) {
        WatchpointStatus.OK -> cs.primaryContainer to cs.onPrimaryContainer
        WatchpointStatus.WARN -> cs.tertiaryContainer to cs.onTertiaryContainer
        WatchpointStatus.BAD -> cs.errorContainer to cs.onErrorContainer
        WatchpointStatus.NEUTRAL -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                wp.label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
            )
            Text(
                wp.tooltip,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
            )
        }
    }
}

private fun audioThreadWatchpoint(snap: LiveDiagnosticSnap): Watchpoint {
    val pri = snap.audioThreadPriority
    val demotions = snap.priorityDemotions
    return when {
        pri == Int.MIN_VALUE -> Watchpoint(
            "Thread: (unknown)", WatchpointStatus.NEUTRAL,
            "No buffer processed yet. Start playback to populate.",
        )
        pri < 0 && demotions == 0 -> Watchpoint(
            "Thread: pri $pri", WatchpointStatus.OK,
            "Audio thread above normal priority. No demotions seen.",
        )
        pri < 0 && demotions > 0 -> Watchpoint(
            "Thread: pri $pri ($demotions demotions)", WatchpointStatus.WARN,
            "Currently OK but has been demoted $demotions time(s) this session.",
        )
        else -> Watchpoint(
            "Thread: pri $pri", WatchpointStatus.BAD,
            "Audio thread at normal or below — underruns likely. v0.7.5 will attempt re-elevation.",
        )
    }
}

private fun perfHintWatchpoint(snap: LiveDiagnosticSnap): Watchpoint {
    return when {
        !snap.perfHintApiSupported -> Watchpoint(
            "PerfHint: n/a", WatchpointStatus.NEUTRAL,
            "Android 12+ feature. Device runs without OS scheduling hints.",
        )
        snap.perfHintSessionActive -> Watchpoint(
            "PerfHint: active", WatchpointStatus.OK,
            "Target ${snap.perfHintTargetNs / 1000} µs · speed-scaled.",
        )
        else -> Watchpoint(
            "PerfHint: idle", WatchpointStatus.WARN,
            "API supported but no session yet. Will arm on first buffer.",
        )
    }
}

private fun wakeLockWatchpoint(
    playerState: com.lofipod.app.player.PlayerState,
    snap: LiveDiagnosticSnap,
): Watchpoint {
    return when {
        !playerState.isPlaying && !snap.wakeLockHeld -> Watchpoint(
            "Wake lock: idle", WatchpointStatus.NEUTRAL,
            "Released when paused. Will acquire on next play.",
        )
        playerState.isPlaying && snap.wakeLockHeld -> Watchpoint(
            "Wake lock: held", WatchpointStatus.OK,
            "Kernel asked to stay clocked during playback.",
        )
        playerState.isPlaying && !snap.wakeLockHeld -> Watchpoint(
            "Wake lock: missing", WatchpointStatus.BAD,
            "Playing but no wake lock. CPU may downclock screen-off.",
        )
        else -> Watchpoint(
            "Wake lock: lingering", WatchpointStatus.WARN,
            "Held while paused — should release in onIsPlayingChanged.",
        )
    }
}

private fun loadFactorWatchpoint(snap: LiveDiagnosticSnap): Watchpoint {
    val avg = snap.avgLoadFactor
    val max = snap.maxLoadFactor
    return when {
        snap.bufferSamples == 0 -> Watchpoint(
            "Load: (no data)", WatchpointStatus.NEUTRAL,
            "No buffers timed yet.",
        )
        max > 1.0 -> Watchpoint(
            "Load: max ${"%.2f".format(max)}", WatchpointStatus.BAD,
            "At least one buffer missed its deadline. Underruns likely.",
        )
        avg > 0.8 -> Watchpoint(
            "Load: avg ${"%.2f".format(avg)}", WatchpointStatus.WARN,
            "Trending close to deadline. Try lower speed or Minimum phase.",
        )
        else -> Watchpoint(
            "Load: avg ${"%.2f".format(avg)}", WatchpointStatus.OK,
            "DSP comfortably within budget.",
        )
    }
}

private fun stallWatchpoint(recentStalls: Int): Watchpoint {
    return when {
        recentStalls == 0 -> Watchpoint(
            "Stalls (60s): 0", WatchpointStatus.OK,
            "No renderer stalls in the last minute.",
        )
        recentStalls < 3 -> Watchpoint(
            "Stalls (60s): $recentStalls", WatchpointStatus.WARN,
            "Watchdog has had to force-flush. Recurring = real issue.",
        )
        else -> Watchpoint(
            "Stalls (60s): $recentStalls", WatchpointStatus.BAD,
            "Multiple stalls. Something upstream is stuck.",
        )
    }
}

private fun playerStateWatchpoint(playerState: com.lofipod.app.player.PlayerState): Watchpoint {
    return when {
        playerState.errorMessage != null -> Watchpoint(
            "Player: ERROR", WatchpointStatus.BAD,
            "Last error: ${playerState.errorMessage}",
        )
        playerState.isBuffering -> Watchpoint(
            "Player: BUFFERING", WatchpointStatus.WARN,
            "Waiting on source or sink to fill enough buffer to resume.",
        )
        playerState.isPlaying -> Watchpoint(
            "Player: PLAYING", WatchpointStatus.OK,
            "Audio flowing.",
        )
        playerState.isReady -> Watchpoint(
            "Player: READY", WatchpointStatus.NEUTRAL,
            "Ready but paused.",
        )
        else -> Watchpoint(
            "Player: IDLE", WatchpointStatus.NEUTRAL,
            "Nothing loaded.",
        )
    }
}

// ---------------------------------------------------------------------------
// Actions row
// ---------------------------------------------------------------------------

@Composable
private fun ActionsRow(
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    onCopyToClipboard: () -> Unit,
    saveAsNoteEnabled: Boolean,
    onSaveAsNote: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Snapshot → note. Disabled when no episode is loaded (the note
        // needs an episode guid). Comes first because it's the most
        // information-preserving action — copies live state + recent
        // events into a single timestamped note pinned to this episode
        // at this playback position. Later browsable from the regular
        // Notes screen + searchable + included in backups.
        AssistChip(
            onClick = onSaveAsNote,
            enabled = saveAsNoteEnabled,
            label = { Text("Save as note") },
            leadingIcon = {
                Icon(
                    Icons.Filled.NoteAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        AssistChip(
            onClick = onCopyToClipboard,
            label = { Text("Copy") },
            leadingIcon = {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(Modifier.weight(1f))
        AssistChip(
            onClick = onToggleFullScreen,
            label = { Text(if (isFullScreen) "Exit full-screen" else "Full-screen") },
            leadingIcon = {
                Icon(
                    if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Sparkline
// ---------------------------------------------------------------------------

@Composable
private fun LoadFactorSparkline(history: List<Double>) {
    val cs = MaterialTheme.colorScheme
    val okColor = cs.primary
    val warnColor = cs.tertiary
    val badColor = cs.error
    val axisColor = cs.outline.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (history.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            // Y maps load 0..1.2 → bottom..top so values just past the
            // deadline still show on the chart.
            val maxY = 1.2
            fun yFor(v: Double) =
                (h - (v / maxY * h).toFloat()).coerceIn(0f, h)
            // Horizontal axis at load = 1.0 (the deadline).
            val deadlineY = yFor(1.0)
            drawLine(
                color = axisColor,
                start = Offset(0f, deadlineY),
                end = Offset(w, deadlineY),
                strokeWidth = 1f,
            )
            // Bar width slot per sample, biased to last slot at the right.
            val slotW = w / SPARKLINE_CAPACITY
            history.forEachIndexed { i, v ->
                val slot = SPARKLINE_CAPACITY - history.size + i
                val x = slot * slotW + slotW * 0.15f
                val bw = slotW * 0.7f
                val barTop = yFor(v.coerceAtLeast(0.0))
                val color = when {
                    v > 1.0 -> badColor
                    v > 0.8 -> warnColor
                    else -> okColor
                }
                drawRect(
                    color = color,
                    topLeft = Offset(x, barTop),
                    size = androidx.compose.ui.geometry.Size(bw, (h - barTop).coerceAtLeast(1f)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Snapshot building + helpers
// ---------------------------------------------------------------------------

/**
 * Capture-at-an-instant view of the live audio chain state. Single immutable
 * snapshot so all downstream rendering sees a consistent view.
 */
private data class LiveDiagnosticSnap(
    val audioThreadId: Int,
    val audioThreadName: String,
    val audioThreadPriority: Int,
    val priorityDemotions: Int,
    val perfHintApiSupported: Boolean,
    val perfHintSessionActive: Boolean,
    val perfHintTargetNs: Long,
    val playbackSpeed: Float,
    val wakeLockHeld: Boolean,
    val avgLoadFactor: Double,
    val maxLoadFactor: Double,
    val avgProcessingNs: Long,
    val maxProcessingNs: Long,
    val bufferSamples: Int,
    val passthrough: Boolean,
    val phaseMode: com.lofipod.app.audio.PhaseMode,
    val masterEnabled: Boolean,
) {
    companion object {
        fun capture(): LiveDiagnosticSnap {
            val bts = AudioChainTelemetry.computeBufferTimingStats()
            return LiveDiagnosticSnap(
                audioThreadId = AudioChainTelemetry.audioThreadId,
                audioThreadName = AudioChainTelemetry.audioThreadName,
                audioThreadPriority = AudioChainTelemetry.audioThreadPriority,
                priorityDemotions = AudioChainTelemetry.priorityDemotionCount(),
                perfHintApiSupported = AudioChainTelemetry.perfHintApiSupported,
                perfHintSessionActive = AudioChainTelemetry.perfHintSessionActive,
                perfHintTargetNs = AudioChainTelemetry.perfHintTargetNs,
                playbackSpeed = AudioChainTelemetry.playbackSpeed,
                wakeLockHeld = PlaybackService.wakeLockHeld,
                avgLoadFactor = bts.avgLoadFactor,
                maxLoadFactor = bts.maxLoadFactor,
                avgProcessingNs = bts.avgProcessingNs,
                maxProcessingNs = bts.maxProcessingNs,
                bufferSamples = bts.sampleCount,
                passthrough = AudioChainTelemetry.passthrough,
                // v0.9.3: PhaseMode enum replaces the Boolean. Pure IIR /
                // Min-Phase FIR / Linear-Phase FIR.
                phaseMode = PlaybackService.sharedEq.currentPhaseMode(),
                masterEnabled = AudioChainTelemetry.enabled,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun formatLiveBlock(
    snap: LiveDiagnosticSnap,
    playerState: com.lofipod.app.player.PlayerState,
    errorVerbose: String?,
): String = buildString {
    append("  audio_enhancement = ${snap.masterEnabled}\n")
    append("  phase_mode        = ${when (snap.phaseMode) {
        com.lofipod.app.audio.PhaseMode.PURE_IIR -> "Pure IIR (biquad cascade)"
        com.lofipod.app.audio.PhaseMode.MIN_FIR -> "Min-Phase FIR (UPC + cepstrum kernel)"
        com.lofipod.app.audio.PhaseMode.LINEAR_FIR -> "Linear-Phase FIR (UPC + symmetric kernel)"
        com.lofipod.app.audio.PhaseMode.MIXED -> "Mixed-Phase (min-phase < 120 Hz, linear-phase > 120 Hz, UPC)"
    }}\n")
    append("  speed             = ${"%.2fx".format(snap.playbackSpeed)}\n")
    append("  passthrough       = ${snap.passthrough}\n")
    append("  audio_thread      = tid=${snap.audioThreadId} name=${snap.audioThreadName} priority=${snap.audioThreadPriority}\n")
    append("  demotions         = ${snap.priorityDemotions}\n")
    append("  perf_hint         = supported=${snap.perfHintApiSupported} active=${snap.perfHintSessionActive} targetUs=${snap.perfHintTargetNs / 1000}\n")
    append("  wake_lock         = ${if (snap.wakeLockHeld) "held" else "released"}\n")
    append("  load_factor       = avg=${"%.2f".format(snap.avgLoadFactor)} max=${"%.2f".format(snap.maxLoadFactor)} samples=${snap.bufferSamples}\n")
    append("  player            = ${describePlayerState(playerState)}\n")
    append("  last_error        = ${errorVerbose ?: "(none)"}")
}

private fun describePlayerState(p: com.lofipod.app.player.PlayerState): String = when {
    p.errorMessage != null -> "ERROR — ${p.errorMessage}"
    p.isBuffering -> "BUFFERING"
    p.isPlaying -> "PLAYING"
    p.isReady -> "READY (paused)"
    else -> "IDLE"
}

private fun formatEventsCompact(events: List<AudioChainTelemetry.Event>): String {
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

private fun buildSnapshotText(
    reason: String,
    playerState: com.lofipod.app.player.PlayerState,
    errorVerbose: String?,
    snap: LiveDiagnosticSnap,
    events: List<AudioChainTelemetry.Event>,
    loadHistory: List<Double>,
): String = buildString {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    appendLine("[Diagnostic snapshot] ${sdf.format(java.util.Date())} ($reason)")
    appendLine("=".repeat(56))
    appendLine()
    appendLine("[Live]")
    appendLine(formatLiveBlock(snap, playerState, errorVerbose))
    appendLine()
    appendLine("[Load history (oldest first)]")
    if (loadHistory.isEmpty()) appendLine("  (no samples)")
    else appendLine(
        "  " + loadHistory.joinToString(" ") { "%.2f".format(it) }
    )
    appendLine()
    appendLine("[Recent events (newest first)]")
    if (events.isEmpty()) appendLine("  (no events)")
    else appendLine(formatEventsCompact(events.take(25)))
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LofiPod diagnostic snapshot", text))
    Toast.makeText(ctx, "Diagnostic snapshot copied", Toast.LENGTH_SHORT).show()
}

private const val SPARKLINE_CAPACITY = 60
private const val AUTO_SNAPSHOT_MIN_INTERVAL_MS = 60_000L
private val AUTO_SNAPSHOT_TRIGGER_KINDS = setOf(
    "priority_demoted",
    "renderer_stall",
    "no_progress",
)
