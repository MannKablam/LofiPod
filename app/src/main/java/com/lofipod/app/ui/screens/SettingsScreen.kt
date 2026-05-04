@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings
import com.lofipod.app.ui.theme.specFor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }

    val theme by settings.theme.collectAsState(initial = LofiTheme.LOWLIGHT)
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)
    val autoPlayNextInFeed by settings.autoPlayNextInFeed.collectAsState(initial = true)
    val showPlayedInList by settings.showPlayedInList.collectAsState(initial = true)
    val autoArchiveDays by settings.autoArchiveDays.collectAsState(initial = 3)
    val textScale by settings.textScale.collectAsState(initial = 1.0f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                checked = showPlayedInList,
                title = "Show played episodes",
                subtitle = "Already-finished episodes stay visible (dimmed and " +
                    "struck through) instead of disappearing from the per-podcast list.",
                onCheckedChange = { v -> scope.launch { settings.setShowPlayedInList(v) } }
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
            SectionHeader("Fonts")
            Text(
                "Press Start 2P by Cody \"CodeMan38\" Boisclair, used under the SIL Open Font License 1.1. Full license text bundled at assets/PressStart2P-OFL.txt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                Icons.Filled.Check,
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
