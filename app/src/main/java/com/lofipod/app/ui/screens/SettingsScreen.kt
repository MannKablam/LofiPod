@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController
import com.lofipod.app.share.QrCode
import com.lofipod.app.ui.theme.specFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public BMC link — same account as the Kontx app's support button. */
private const val BUY_ME_COFFEE_URL = "https://buymeacoffee.com/dev.laroix"

@Composable
fun SettingsScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenAudioDiagnostics: () -> Unit = {},
    onOpenAudiophileNotes: () -> Unit = {},
    onOpenLofiNotes: () -> Unit = {},
    onOpenAppDiagnostics: () -> Unit = {},
    onOpenTextSettings: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    /** Jumps to Metrics with the export save-file dialog already open —
     *  the one-tap "get my play history out" path. */
    onExportBackup: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }

    val theme by settings.theme.collectAsState(initial = LofiTheme.LOWLIGHT)
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)
    val autoPlayNextInFeed by settings.autoPlayNextInFeed.collectAsState(initial = true)
    val autoplayConfirmEnabled by settings.autoplayConfirmEnabled.collectAsState(initial = true)
    val smartResume by settings.smartResumeEnabled.collectAsState(initial = true)
    val autoArchiveDays by settings.autoArchiveDays.collectAsState(initial = 3)
    val textScale by settings.textScale.collectAsState(initial = 1.0f)
    val scrubberHeatmap by settings.scrubberHeatmapEnabled.collectAsState(initial = true)
    val scrubberBreakMarks by settings.scrubberBreakMarksEnabled.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // Updates first — most-frequent action when the user opens
            // Settings is "is there a new build?" Putting it at the top
            // means no scrolling past Theme / Playback / etc. to reach it.
            SectionHeader("Updates")
            UpdatesRow()

            Spacer(Modifier.height(20.dp))
            SectionHeader("Theme")
            Spacer(Modifier.height(4.dp))
            LofiTheme.values().forEach { t ->
                ThemeRow(
                    theme = t,
                    selected = (t == theme),
                    onSelect = { scope.launch { settings.setTheme(t) } }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Playback")
            SwitchRow(
                checked = autoPlayNextInFeed,
                title = "Auto-play next in feed",
                subtitle = "When the queue is empty, advance to the next published " +
                    "episode of the same podcast at the end of one.",
                onCheckedChange = { v -> scope.launch { settings.setAutoPlayNextInFeed(v) } }
            )
            SwitchRow(
                checked = autoplayConfirmEnabled,
                title = "Confirm autoplay continues",
                subtitle = "Beep at 1, 2, and 3 minutes into each auto-played episode " +
                    "and pause at 3:10 unless you tap the play button (or a Bluetooth " +
                    "play/pause press) to confirm. Stops indefinite background playback.",
                onCheckedChange = { v -> scope.launch { settings.setAutoplayConfirmEnabled(v) } }
            )
            // "Show played episodes" moved to the Episodes screen's list
            // menu (v0.11, per user direction): it only affects browsing
            // that list, so its switch lives where it acts. The DataStore
            // setting itself is unchanged.
            SwitchRow(
                checked = scrubberHeatmap,
                title = "Replay heatmap on the playback bar",
                subtitle = "Tint re-listened stretches cold-to-hot. Display only — " +
                    "listening history keeps recording while hidden.",
                onCheckedChange = { v -> scope.launch { settings.setScrubberHeatmapEnabled(v) } }
            )
            SwitchRow(
                checked = scrubberBreakMarks,
                title = "Break marks on the playback bar",
                subtitle = "Cut-style ticks at the biggest pauses — the spots the " +
                    "previous-break button jumps to. The button keeps working " +
                    "while they're hidden.",
                onCheckedChange = { v -> scope.launch { settings.setScrubberBreakMarksEnabled(v) } }
            )
            SwitchRow(
                checked = smartResume,
                title = "Smart resume",
                subtitle = "After a pause, playback steps back a little before " +
                    "continuing — nothing for quick pauses, a few seconds after " +
                    "minutes away, up to 45 seconds after a day — so the thread " +
                    "of the message is re-established without manual rewinding.",
                onCheckedChange = { v -> scope.launch { settings.setSmartResumeEnabled(v) } }
            )

            Spacer(Modifier.height(8.dp))
            AutoArchiveRow(
                value = autoArchiveDays,
                onChange = { v -> scope.launch { settings.setAutoArchiveDays(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Notes")
            SwitchRow(
                checked = pauseOnNote,
                title = "Pause playback while writing a note",
                subtitle = "Audio resumes once the note is saved or cancelled.",
                onCheckedChange = { v -> scope.launch { settings.setPauseOnNote(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Display")
            TextScaleRow(
                value = textScale,
                onChange = { v -> scope.launch { settings.setTextScale(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Audio")
            Text(
                "Playback speed and EQ live in the EQ screen (top-bar overflow menu).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("System")
            BatteryOptimizationRow()

            Spacer(Modifier.height(20.dp))
            SectionHeader("Data")
            AutoBackupRow()
            Spacer(Modifier.height(8.dp))
            ClearHistoryRow(
                onConfirm = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.db.playbackCheckpointDao().clear()
                        }
                    }
                }
            )

            // Share sits just above About — out of the daily-use settings
            // flow but still anchored to a section header, not orphaned at
            // the page foot. The user scrolls to it deliberately when
            // they want to hand the app to someone.
            Spacer(Modifier.height(20.dp))
            SectionHeader("Share")
            ShareApkRow()

            Spacer(Modifier.height(20.dp))
            SectionHeader("Audio diagnostics")
            // Inline mini-summary kept as a teaser; the full view (live
            // meters, counters, event log, copy-to-clipboard) lives on its
            // own screen so it can use the full vertical budget. Tap "Open"
            // to navigate.
            AudioDiagnosticsRow(controller)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenAudioDiagnostics) {
                Text("Open full audio diagnostics")
            }
            // In-Player diagnostics tab. When on, PlayerScreen's bottom tab
            // strip grows a 4th "Diagnostics" tab next to Notes / Details /
            // Transcript. Useful when something sounds wrong while you're
            // actively listening — fastest path from "wait, that's not
            // right" to live readouts is one tap on the tab strip, no nav
            // hop required. Includes its own full-screen mode that keeps
            // the mini-player visible at the bottom for transport while
            // you read.
            DiagnosticsTabToggleRow()
            // Manual flush-valve button in Player. Off by default; on for
            // users who want a one-tap audio reset (clears the AudioTrack
            // buffer and re-seeks). Useful for diagnosing audio-chain state
            // issues or when something sounds contaminated and you want a
            // clean restart without disrupting position much (~50 ms rewind).
            FlushButtonToggleRow()
            // App-wide bug telemetry — feed failures, download failures,
            // scripture-tag skips, etc. Distinct from Audio diagnostics
            // (chain-specific). Lands here so a user can capture concrete
            // data when something breaks instead of "it didn't work."
            TextButton(onClick = onOpenAppDiagnostics) {
                Text("App diagnostics (bugs)")
            }

            // Notes about audio — both pages exposed equally so a reader
            // who self-identifies as a non-audiophile can find the
            // plain-language guide without having to tap an
            // audiophile-flavored entry first. Both pages cross-link to
            // each other; this section is the canonical entry to either.
            Spacer(Modifier.height(20.dp))
            SectionHeader("Notes about audio")
            Text(
                "Two takes on the same audio chain. Pick the one that " +
                    "matches how you want to think about it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenLofiNotes) {
                Text("Audio guide (plain language)")
            }
            TextButton(onClick = onOpenAudiophileNotes) {
                Text("Notes for audiophiles")
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Text")
            Text(
                "Pick a body font (system or bundled Garamond) and dial in the " +
                    "size of the notes text + the note editor pop-up. Live preview " +
                    "shows the result before you commit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenTextSettings) {
                Text("Text & font")
            }

            Spacer(Modifier.height(20.dp))
            // Buy-Me-a-Coffee support button — BMC's own presentation is
            // the yellow mark on a black plate, and that's what this is:
            // a filled black chip in every theme (deliberately theme-
            // independent, like the gold kabod card), yellow border for
            // an edge on the near-black Lowlight surface. The earlier
            // outlined ghost version read as just another settings
            // control instead of "the support button".
            SectionHeader("Support the developer")
            Text(
                "LofiPod is free and ad-free. A small tip keeps new versions coming.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(BUY_ME_COFFEE_URL)
                    )
                    ctx.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color(0xFFFFDD57)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color(0xFFFFDD57).copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "To keep updates flowing: ☕ Buy me a coffee",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("About")
            Text(
                "LofiPod — a personal-canon podcast app. Backups + restore live " +
                    "in Metrics. Theme, queue auto-play, archive, and EQ-per-episode " +
                    "preferences persist across reinstalls only when the new build " +
                    "is signed with the same key as the previous one (see BUILD_LOG).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Shortcut into the Metrics screen's export with the save-file
            // dialog already open — Settings is where people look for
            // "get my data out", so the entry lives here too even though
            // the machinery (and import) stays on Metrics.
            TextButton(onClick = onExportBackup) {
                Text("Export play history (backup)")
            }
            TextButton(onClick = onOpenSources) {
                Text("View sources document (read-only)")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Open-source audio libraries: " +
                    "PFFFT (FFT + UPC inner loop, BSD-3-Clause, Julien Pommier / NCAR) — " +
                    "JTransforms (kernel-synth FFT, BSD-2-Clause, Piotr Wendykier). " +
                    "Icons: Material Symbols (Apache-2.0, Google). " +
                    "Full license texts ship inside the APK under " +
                    "assets/licenses/.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Two-line settings row with a leading [Switch]. Used for every boolean
 *  toggle in this screen so they line up visually. */
@Composable
private fun SwitchRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Selectable, copy-friendly readout of the audio chain state + last player
 * error. Surfaced because the v0.4.7 audio-enhancement passthrough fix was
 * impossible to diagnose without seeing the full ExoPlayer error code +
 * cause; this panel makes that reachable from the device without adb. Also
 * shows the live processor state (enabled, gain, all 10 band gains,
 * skip-silence level) so a user can sanity-check that what the EQ screen
 * shows matches what the running [PlaybackService.sharedEq] actually has.
 */
/**
 * "Show Diagnostics tab on Player" row. A simple [SwitchRow] would work, but
 * doing it inline lets the subtitle copy explain the in-player full-screen
 * mode + mini-player anchor — a behavior subtle enough to warrant a couple of
 * extra words rather than burying it in About / Help.
 */
@Composable
private fun DiagnosticsTabToggleRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()
    val enabled by settings.showDiagnosticsTabInPlayer.collectAsState(initial = false)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = { v -> scope.launch { settings.setShowDiagnosticsTabInPlayer(v) } },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Show Diagnostics tab on Player",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Adds a 4th tab next to Notes / Details / Transcript with live audio-chain health, watchpoints, and a one-tap snapshot-to-note. Includes a full-screen mode that keeps the mini-player visible for transport.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Toggle for the manual flush-valve icon shown in the player. Lives next
 * to DiagnosticsTabToggleRow because both are "Player UI affordance" flags
 * with similar plumbing.
 */
@Composable
private fun FlushButtonToggleRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()
    val enabled by settings.showFlushButtonInPlayer.collectAsState(initial = false)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = { v -> scope.launch { settings.setShowFlushButtonInPlayer(v) } },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Show manual flush button on Player",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Adds a flush-valve icon to the right of the speed chip. Tapping it forces an AudioTrack flush + brief re-seek (~50 ms), clearing any pre-flush PCM in the buffer. Useful when something sounds off and a clean restart is the fastest fix.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioDiagnosticsRow(controller: PlayerController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()
    val playerState by controller.state.collectAsState()
    val audioEnhancement by settings.audioEnhancementEnabled.collectAsState(initial = true)
    val skipSilenceLevel by settings.skipSilenceLevel.collectAsState(initial = 0)

    // Live read of the shared processor state. Recomposes whenever
    // playerState changes (which is on every player event), so band/gain
    // edits made on EqScreen show up here when the user switches back to
    // Settings — without us needing a separate flow on the processor.
    val eq = PlaybackService.sharedEq
    val gainDb = remember(playerState) { eq.currentGainDb() }
    val bands = remember(playerState) { eq.currentBands() }
    val bandsLabel = remember(bands) {
        bands.joinToString(" ") { "%+.0f".format(it.gainDb) }
    }
    val errorVerbose = controller.lastErrorDetails

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "EQ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  audio_enhancement = $audioEnhancement\n" +
                        "  master_gain_db   = ${"%+.1f".format(gainDb)}\n" +
                        "  bands_db         = $bandsLabel\n" +
                        "  skip_silence_lvl = $skipSilenceLevel",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Player",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last error",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  ${errorVerbose ?: "(none since last successful play)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorVerbose != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            // Defaults: audio_enhancement=on, master_gain=0, bands=FLAT,
            // skip_silence=off. Lets the user recover from a wedged EQ
            // configuration without hunting through the EQ screen.
            scope.launch {
                withContext(Dispatchers.IO) {
                    settings.setAudioEnhancementEnabled(true)
                    settings.setGainDb(0f)
                    settings.setEqBandsCsv(
                        com.lofipod.app.audio.EqPresets.FLAT
                            .joinToString(",") { it.gainDb.toString() }
                    )
                    settings.setSkipSilenceLevel(0)
                    settings.setPauseSkipSensitivity(
                        com.lofipod.app.audio.PauseTapProcessor.DEFAULT_SENSITIVITY
                    )
                    // v0.11: cover the tone filters, DC blocker and voice
                    // suite too — this reset previously lagged the fuller
                    // one on the Audio-diagnostics screen, so "reset to
                    // defaults" here left tone cuts and the DC blocker
                    // silently engaged.
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
                // Push the new values into the live processors so the
                // change takes effect without restarting the service.
                eq.setBands(com.lofipod.app.audio.EqPresets.FLAT)
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

/**
 * Updates section. Wires:
 *   - auto-check toggle (drives the daily 23:59 UpdateWorker)
 *   - "Check for updates" on-demand button
 *   - last-checked timestamp
 *   - "Update available" chip + Install button when a download is staged
 *
 * Install path: tapping "Install" hands the staged APK to the system
 * package installer. If "Install unknown apps" hasn't been granted to
 * LofiPod, the button instead routes the user to that system Settings page;
 * after they grant it, the next tap launches the installer dialog.
 */
@Composable
private fun UpdatesRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as com.lofipod.app.LofiPodApp
    val settings = remember { com.lofipod.app.data.Settings(app) }
    val checker = remember { com.lofipod.app.update.UpdateChecker(app) }
    val scope = rememberCoroutineScope()

    val autoCheck by settings.updateAutoCheckEnabled.collectAsState(initial = true)
    val lastChecked by settings.updateLastCheckedAt.collectAsState(initial = 0L)
    val availableCode by settings.updateAvailableVersionCode.collectAsState(initial = 0)
    val availableName by settings.updateAvailableVersionName.collectAsState(initial = null)

    var checking by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var stagedApk by remember { mutableStateOf<java.io.File?>(null) }

    // Currently-installed package info. versionCode drives the
    // "is the available release newer?" comparison; versionName is the
    // human-readable label shown in the UI ("Installed: v0.3.4").
    val installedPkg = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }.getOrNull()
    }
    val installedCode = remember(installedPkg) {
        if (installedPkg == null) 0
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            installedPkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") installedPkg.versionCode
    }
    val installedName = remember(installedPkg) {
        installedPkg?.versionName ?: "?"
    }

    // Whether the previously-staged APK is still on disk and still useful
    // (its versionCode > installedCode). On first composition we re-derive
    // this from cache + Settings so the "Install" button reappears after a
    // process restart without re-checking.
    LaunchedEffect(availableCode) {
        if (availableCode > installedCode) {
            val cached = java.io.File(ctx.cacheDir, "updates/lofipod-$availableCode.apk")
            stagedApk = cached.takeIf { it.exists() && it.length() > 0 }
        } else {
            stagedApk = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Auto-check toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoCheck,
                onCheckedChange = { v ->
                    scope.launch {
                        settings.setUpdateAutoCheckEnabled(v)
                        com.lofipod.app.update.UpdateWorker.schedule(app, v)
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Auto-check nightly", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Polls GitHub releases at 23:59 local time. A notification appears when a new build is ready to install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // On-demand check + status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Installed: v$installedName",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    when {
                        lastChecked == 0L -> "Never checked."
                        else -> "Last checked: ${formatBackupTime(lastChecked)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                statusLine?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    statusLine = "Checking…"
                    scope.launch {
                        when (val r = checker.checkAndDownload()) {
                            is com.lofipod.app.update.UpdateChecker.Result.UpToDate -> {
                                statusLine = "Up to date (v$installedName)."
                            }
                            is com.lofipod.app.update.UpdateChecker.Result.Updated -> {
                                stagedApk = r.apkFile
                                statusLine = "Update ready: ${r.versionName}."
                            }
                            is com.lofipod.app.update.UpdateChecker.Result.Failed -> {
                                statusLine = "Check failed: ${r.message}"
                            }
                        }
                        checking = false
                    }
                }
            ) { Text(if (checking) "Checking…" else "Check now") }
        }

        // Available-update chip + Install button
        val staged = stagedApk
        if (staged != null && availableCode > installedCode) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Update available: ${availableName ?: "build $availableCode"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Tap Install to apply. The system will show its standard install dialog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = {
                    if (checker.canRequestInstall()) {
                        checker.launchInstaller(staged)
                    } else {
                        // Permission missing — kick the user into Settings.
                        // Once they grant it, returning here and tapping
                        // Install again succeeds without further hops.
                        checker.openInstallUnknownAppsSettings()
                        statusLine = "Grant \"Install unknown apps\" to LofiPod, then tap Install again."
                    }
                }) { Text("Install") }
            }
        }
    }
}

/**
 * Share row. Renders a QR code that points at the stable
 * `releases/latest/download/lofipod.apk` redirect — same URL the in-app
 * updater uses, so a friend's phone scanning it kicks off a direct download
 * of the most recent signed APK without needing to know about tags or the
 * Releases page.
 *
 * Two complementary share affordances:
 *  - **Visible QR** for in-person sharing (point camera at user's screen).
 *  - **Share link button** that fires `Intent.ACTION_SEND` for remote
 *    sharing (text, Slack, email, etc.). The URL is also displayed as
 *    plain selectable text below the QR so it can be copied manually.
 *
 * The QR is generated client-side via ZXing core — no network, no WebView.
 * Generation happens once per composition (the URL is stable) and the
 * resulting [ImageBitmap] is cached in `remember`.
 */
@Composable
private fun ShareApkRow() {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val sizeDp = 220.dp
    val sizePx = with(density) { sizeDp.roundToPx() }

    // Same URL the in-app UpdateChecker hits. GitHub serves a permanent
    // redirect from /releases/latest/download/<asset> to the actual asset
    // URL of whatever is currently flagged as the "latest" release — so
    // this same QR works across every future v0.x.x cut, no regeneration
    // needed.
    val apkUrl = "https://github.com/MannKablam/LofiPod/releases/latest/download/lofipod.apk"

    // Cache the generated bitmap so it's not recomputed on every recompose.
    // 220.dp at xxxhdpi is ~880px — generation takes a few ms but pinning
    // it to a remember keeps scrolling smooth.
    val qrBitmap: ImageBitmap = remember(apkUrl, sizePx) {
        QrCode.generate(apkUrl, sizePx)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Share with someone in person — they scan the code, their phone " +
                "downloads the latest signed APK directly. They'll need to " +
                "grant \"Install unknown apps\" to whichever browser handles " +
                "the download (one-time per device).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // White plate behind the QR — black-on-white is what every camera
        // scanner expects, regardless of the active theme. Surrounding
        // padding gives the QR a visual frame.
        Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 2.dp,
        ) {
            Image(
                bitmap = qrBitmap,
                contentDescription = "QR code for the latest LofiPod APK",
                contentScale = ContentScale.Fit,
                // Keep the QR's hard pixel edges crisp — anti-aliased scaling
                // softens the modules and degrades scan reliability.
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .padding(12.dp)
                    .size(sizeDp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // URL as selectable plain text — gives a copy-paste fallback in
        // case the QR can't be scanned (poor lighting, scratched lens, etc).
        SelectionContainer {
            Text(
                apkUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            modifier = Modifier.align(Alignment.End),
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "LofiPod APK")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Install LofiPod (latest signed APK):\n$apkUrl"
                    )
                }
                ctx.startActivity(Intent.createChooser(send, "Share LofiPod"))
            }
        ) {
            Icon(painterResource(R.drawable.share_24), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share link")
        }
    }
}

/**
 * Battery-optimization request row. Reads `PowerManager.isIgnoringBatteryOptimizations`
 * for the live status and offers a button that fires the system prompt to
 * mark LofiPod as "Unrestricted."
 *
 * Why this exists: even with a `mediaPlayback` foreground service, Doze + App
 * Standby Buckets can throttle background CPU scheduling, network sockets,
 * and timer fidelity in ways that surface as playback jitter — particularly
 * during long sessions, with the screen off, or at >1x speed where each
 * scheduling hiccup eats more wall-clock budget. Granting unrestricted
 * status removes that throttling. Vendor skins (Xiaomi, OnePlus, Samsung's
 * "deep sleep" lists, etc.) ship even more aggressive defaults than stock
 * AOSP, so this can be a real fix for users on those devices.
 *
 * Status auto-refreshes when the user returns from the system Settings page
 * via the [rememberLauncherForActivityResult] callback — no lifecycle
 * observer needed, and matches the existing pattern used by
 * [AutoBackupRow]'s folder picker.
 */
@Composable
private fun BatteryOptimizationRow() {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(android.os.PowerManager::class.java) }

    // Recompose-tracked tick so the launcher callback can force a re-read
    // of isIgnoringBatteryOptimizations after the user returns from the
    // system prompt. The PowerManager API has no broadcast for this.
    var refreshTick by remember { mutableStateOf(0) }
    val isUnrestricted = remember(refreshTick) {
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User returned from either the request prompt or the per-app
        // battery details page. Bump the tick so we re-read the live state.
        refreshTick++
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Battery optimization", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Android can throttle background CPU and wake-up scheduling to " +
                "save battery. That throttling can cause occasional playback " +
                "stutters or hangs, especially during long sessions with the " +
                "screen off or at >1x speed. Marking LofiPod as " +
                "\"Unrestricted\" lets the audio thread run smoothly. " +
                "Vendor skins (Xiaomi, OnePlus, Samsung) are stricter than " +
                "stock Android — recommended on those devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isUnrestricted) "Status: unrestricted"
                else "Status: optimized (may throttle background playback)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUnrestricted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isUnrestricted) {
                // Already unrestricted — give the user a way back into the
                // per-app system page in case they want to revert or verify.
                TextButton(onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    runCatching { launcher.launch(intent) }
                }) { Text("Open settings") }
            } else {
                FilledTonalButton(onClick = {
                    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS pops the
                    // system "Allow [app] to use battery without restrictions?"
                    // dialog. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    // declared in the manifest, otherwise SecurityException.
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    runCatching { launcher.launch(intent) }
                }) { Text("Allow unrestricted") }
            }
        }
    }
}

/**
 * Auto-backup setup row. Picks a SAF tree (folder) and a periodic interval;
 * the BackupWorker writes a single overwriting file inside that tree on
 * schedule. The "Back up now" button enqueues an immediate one-shot run via
 * the same worker class for parity. Status line shows the last successful
 * backup so the user can sanity-check that scheduled runs are happening.
 */
@Composable
private fun AutoBackupRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { com.lofipod.app.data.Settings(app) }
    val scope = rememberCoroutineScope()
    val treeUri by settings.backupTreeUri.collectAsState(initial = null)
    val intervalHours by settings.backupIntervalHours.collectAsState(initial = 0)
    val lastSuccess by settings.backupLastSuccessAt.collectAsState(initial = 0L)

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist read+write so the worker can write later. Without this the
        // permission is only valid for the duration of this Activity.
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        scope.launch { settings.setBackupTreeUri(uri.toString()) }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Auto-backup", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Writes a single overwriting JSON file (\"lofipod-backup-latest.json\") to the folder you pick. Each run replaces the previous file. Use the manual Export in Metrics for dated copies.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Folder row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Folder",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    treeUri?.let { shortTreeLabel(it) } ?: "(none picked)",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
            TextButton(onClick = { pickFolder.launch(null) }) {
                Text(if (treeUri == null) "Pick" else "Change")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Interval picker — chips
        Text(
            "Interval",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        val intervals = listOf(0 to "Off", 6 to "6 hr", 12 to "12 hr", 24 to "Daily", 168 to "Weekly")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intervals.forEach { (h, label) ->
                FilterChip(
                    selected = intervalHours == h,
                    onClick = {
                        scope.launch {
                            settings.setBackupIntervalHours(h)
                            com.lofipod.app.data.BackupWorker.schedule(app, h)
                        }
                    },
                    label = { Text(label) },
                    enabled = treeUri != null || h == 0
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lastSuccess > 0) "Last backup: ${formatBackupTime(lastSuccess)}"
                else "No backup written yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    scope.launch {
                        // Manual one-off run via the same worker class — kept
                        // separate from the periodic schedule so triggering
                        // "Back up now" doesn't reset the periodic clock.
                        val req = androidx.work.OneTimeWorkRequestBuilder<
                            com.lofipod.app.data.BackupWorker>().build()
                        androidx.work.WorkManager.getInstance(app)
                            .enqueueUniqueWork(
                                "lofipod-auto-backup-now",
                                androidx.work.ExistingWorkPolicy.REPLACE,
                                req
                            )
                    }
                },
                enabled = treeUri != null
            ) { Text("Back up now") }
        }
    }
}

/** "tree:/path/...:Music" → "Music" — short tail label for the folder UI. */
private fun shortTreeLabel(uriString: String): String {
    return try {
        val u = android.net.Uri.parse(uriString)
        val last = u.lastPathSegment ?: return uriString
        last.substringAfterLast('/').substringAfterLast(':').ifBlank { last }
    } catch (_: Exception) {
        uriString
    }
}

private fun formatBackupTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

/**
 * "Clear playback history" row with confirm dialog. Shows the live count so
 * the user knows what they're about to erase. Wipes only the checkpoints
 * (jump-from / session-end / promotion records); per-episode position and
 * favorite tier are preserved.
 */
@Composable
private fun ClearHistoryRow(onConfirm: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    var count by remember { mutableStateOf<Int?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.db.playbackCheckpointDao().count()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Clear playback history", style = MaterialTheme.typography.bodyLarge)
            Text(
                count?.let {
                    "$it checkpoint${if (it == 1) "" else "s"} stored. Position + favorites are preserved."
                } ?: "Position + favorites are preserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = { dialogOpen = true },
            enabled = (count ?: 0) > 0
        ) { Text("Clear") }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Clear playback history?") },
            text = {
                Text(
                    "Erases all ${count ?: 0} checkpoints — jump-from records, session-end snapshots, and most-excellent promotions. Your saved positions and favorites stay intact."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm()
                    dialogOpen = false
                    scope.launch { refresh() }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Auto-archive horizon picker. Discrete chips for the common windows
 * (off / 1 / 3 / 7 / 30 days). Subtitle explains exactly what gets swept —
 * the sweep targets *finished* episodes only, in-progress ones never
 * disappear regardless of this setting.
 */
@Composable
private fun AutoArchiveRow(value: Int, onChange: (Int) -> Unit) {
    val choices = listOf(0, 1, 3, 7, 30)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Auto-archive played episodes after", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Finished episodes (>95% played) move to the archive after the chosen window. In-progress episodes never auto-archive. \"Off\" disables the sweep entirely.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { days ->
                FilterChip(
                    selected = value == days,
                    onClick = { onChange(days) },
                    label = {
                        Text(
                            when (days) {
                                0 -> "Off"
                                1 -> "1 day"
                                else -> "$days days"
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * Text-scale slider. Local preview while dragging — only commits to settings
 * on release so the whole-app fontScale doesn't thrash on every drag tick.
 * The sample line under the slider renders at the previewed scale so the
 * user sees the effect of the slider directly under their thumb instead of
 * having to look around the screen for what changed.
 *
 * Range matches Settings.textScale (0.85 .. 1.4).
 */
@Composable
private fun TextScaleRow(value: Float, onChange: (Float) -> Unit) {
    // Local drag state — initialized from the persisted value and re-synced
    // any time it changes externally (e.g. backup restore, second device).
    var preview by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Text size: ${"%.0f".format(preview * 100)}%",
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Used everywhere except the playback artwork. Bumping it up makes " +
                "longer reading sessions easier on the eyes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Slider(
            value = preview,
            onValueChange = { preview = it },
            onValueChangeFinished = { onChange(preview) },
            valueRange = 0.85f..1.4f,
            steps = 10  // 11 stops between 0.85 and 1.4 (~5% increments)
        )
        Spacer(Modifier.height(4.dp))
        // Live preview line. fontSize derives from bodyLarge (16sp default)
        // scaled by the previewed multiplier so the user can see the effect
        // of the slider directly while dragging, without committing to the
        // whole-app rescale.
        val baseSp = 16f
        Text(
            "The quick brown fox jumps over the lazy dog.",
            style = MaterialTheme.typography.bodyLarge,
            fontSize = (baseSp * preview).sp
        )
    }
}

/**
 * Theme picker row: 4-stripe palette swatch (background, surface, primary,
 * secondary) + name + tagline + check mark when active. Renders the swatches
 * from each theme's own [specFor] so the row previews the actual look without
 * having to switch into it.
 */
@Composable
private fun ThemeRow(
    theme: LofiTheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val spec = specFor(theme)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PaletteSwatch(
            colors = listOf(
                spec.colors.background,
                spec.colors.surface,
                spec.colors.primary,
                spec.colors.secondary
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                theme.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.check_24),
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PaletteSwatch(colors: List<Color>) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
    ) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 28.dp)
                    .background(c)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
