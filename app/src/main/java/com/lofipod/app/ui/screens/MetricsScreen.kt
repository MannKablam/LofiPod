@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Backup
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.ui.theme.ThemedArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun MetricsScreen(
    onBack: () -> Unit,
    /**
     * Settings' "Export play history" entry lands here with this set:
     * the SAF save-file dialog opens on arrival (notes included — the
     * full-fat default), so the shortcut really is tap -> pick where to
     * save. The Backup section's own Export button remains the path to
     * the include-notes choice.
     */
    autoStartExport: Boolean = false,
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var rows by remember { mutableStateOf<List<PodcastMetrics>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var exportIncludeNotes by remember { mutableStateOf(true) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) {
            // This is a closed-canon app: every channel here is one WE
            // chose, so the metrics rows should know them like the catalog
            // does — artwork, author, how deep into the archive the
            // listening has gone. Identity comes from the feed cache;
            // hydrate first because Metrics is reachable straight from the
            // top-bar menu on a cold start, before the Catalog primed it
            // (kabod packs re-parse from assets via their own loader).
            app.repo.hydrateFromDisk()
            val all = app.db.episodeStateDao().getAll()
            val notesByGuid = app.db.episodeNoteEntryDao().getAll()
                .groupingBy { it.guid }.eachCount()
            all.groupBy { it.feedUrl }.map { (feedUrl, episodes) ->
                val pod = app.repo.cached(feedUrl)
                    ?: app.kabodLoader.takeIf { it.isKabodFeed(feedUrl) }
                        ?.loadIntoCache(feedUrl)
                val artOverride = Sources.PODCAST_FEEDS
                    .firstOrNull { it.feedUrl == feedUrl }?.customArtworkUrl
                PodcastMetrics(
                    feedUrl = feedUrl,
                    title = when {
                        feedUrl == DEVICE_FEED_URL -> "This device"
                        else -> Sources.displayNameOf(feedUrl)
                            ?: pod?.title
                            ?: feedUrl.shortHost()
                    },
                    author = pod?.author,
                    artworkUrl = artOverride ?: pod?.artworkUrl,
                    episodeCount = pod?.episodes?.size ?: 0,
                    playedCount = episodes.count {
                        it.durationMs > 0 && it.positionMs >= it.durationMs - 5_000
                    },
                    noteCount = episodes.sumOf { notesByGuid[it.guid] ?: 0 },
                    lastPlayedMillis = episodes.maxOf { it.lastPlayedMillis },
                    totalListenedMs = episodes.sumOf { it.cumulativeListenMs },
                    hearted = episodes.filter { it.favoriteTier > 0 }
                        .sortedByDescending { it.favoriteTier * 1_000_000_000L + it.lastPlayedMillis }
                )
            }.sortedByDescending { it.totalListenedMs }
        }
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val episodes = app.db.episodeStateDao().getAll()
                    // Honor the user's "include notes" choice — when off we
                    // export an empty notes list, keeping the same backup shape.
                    val notes = if (exportIncludeNotes)
                        app.db.episodeNoteEntryDao().getAll() else emptyList()
                    val checkpoints = app.db.playbackCheckpointDao().getAll()
                    val podcastStates = app.db.podcastStateDao().getAll()
                    val kabodPacks = app.db.kabodPackDao().getAll()
                    val episodeKabod = app.db.episodeKabodDao().getAll()
                    val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    Backup.export(
                        episodes, notes, checkpoints, podcastStates,
                        kabodPacks, episodeKabod, pkg.versionName ?: "?"
                    )
                }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                snackbarHostState.showSnackbar(
                    if (exportIncludeNotes) "Exported ${json.length / 1024} KB"
                    else "Exported ${json.length / 1024} KB (notes omitted)"
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    // The Settings shortcut's whole point is zero extra taps: fire the
    // save-file dialog the moment the screen lands. One-shot per entry —
    // recompositions must not re-open a dialog the user dismissed.
    LaunchedEffect(Unit) {
        if (autoStartExport) {
            exportLauncher.launch(defaultBackupFilename(exportIncludeNotes))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Metrics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                // Backup import/export used to be two bare top-bar icons —
                // prime position for something done a few times a year, and
                // nothing about a download glyph next to "Metrics" said
                // "backup". They now live in a labeled Backup section at the
                // bottom of the list (and in the empty state, where import
                // is the only way a fresh install gets its history back).
            )
        }
    ) { padding ->
        when {
            !loaded -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No listening data yet.")
                Spacer(Modifier.height(16.dp))
                // A fresh install's only road back to its history is the
                // import button — it can't live solely under rows that
                // don't exist yet.
                BackupSection(
                    onExport = { pendingExportUri = android.net.Uri.EMPTY },
                    onImport = {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                )
            }

            else -> {
                Column(Modifier.padding(padding)) {
                    val totalH = rows.sumOf { it.totalListenedMs } / 1000.0 / 3600.0
                    val totalHearted = rows.sumOf { it.hearted.size }
                    val totalNotes = rows.sumOf { it.noteCount }
                    Text(
                        "%.2f h listened across all podcasts • %d hearted • %d notes"
                            .format(totalH, totalHearted, totalNotes),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rows, key = { it.feedUrl }) { row -> PodcastMetricsCard(row) }
                        item(key = "backup://footer") {
                            BackupSection(
                                onExport = { pendingExportUri = android.net.Uri.EMPTY },
                                onImport = {
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingExportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingExportUri = null },
            title = { Text("Export backup") },
            text = {
                Column {
                    Text(
                        "Choose what to include. Episode states, ratings, and " +
                            "playback positions are always exported.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = exportIncludeNotes,
                            onCheckedChange = { exportIncludeNotes = it }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (exportIncludeNotes) "Include notes"
                            else "Omit notes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingExportUri = null
                    exportLauncher.launch(defaultBackupFilename(exportIncludeNotes))
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExportUri = null }) { Text("Cancel") }
            }
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "Episodes and notes in the backup will be merged in. Anything with a matching ID is overwritten by the backup's version."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toImport = uri
                    pendingImportUri = null
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) {
                                ctx.contentResolver.openInputStream(toImport)?.use {
                                    it.bufferedReader().readText()
                                } ?: error("Couldn't read file")
                            }
                            val result = withContext(Dispatchers.IO) {
                                Backup.importInto(text, app.db)
                            }
                            reload()
                            snackbarHostState.showSnackbar(
                                "Restored ${result.episodeCount} episodes, ${result.noteCount} notes, ${result.checkpointCount} checkpoints"
                            )
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Import failed: ${e.message}")
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PodcastMetricsCard(row: PodcastMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Channel identity header — the same face the catalog shows.
            // These are hand-picked canon feeds, not strangers; the row
            // should look like the podcast, not like a URL's hostname.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedArtwork(artworkUrl = row.artworkUrl, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    row.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(
                            author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val hours = row.totalListenedMs / 1000.0 / 3600.0
            Text(
                buildString {
                    append("%.2f h listened".format(hours))
                    // "N of M played" reads archive depth against the
                    // feed's real size when the cache knows it; a bare
                    // played count is the fallback for feeds that
                    // haven't hydrated (or the device pseudo-feed).
                    if (row.episodeCount > 0) {
                        append("  •  ${row.playedCount} of ${row.episodeCount} played")
                    } else if (row.playedCount > 0) {
                        append("  •  ${row.playedCount} played")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val detailLine = buildString {
                if (row.hearted.isNotEmpty()) append("${row.hearted.size} hearted")
                if (row.noteCount > 0) {
                    if (isNotEmpty()) append("  •  ")
                    append("${row.noteCount} note${if (row.noteCount == 1) "" else "s"}")
                }
                if (row.lastPlayedMillis > 0) {
                    if (isNotEmpty()) append("  •  ")
                    append(
                        "last " + SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                            .format(Date(row.lastPlayedMillis))
                    )
                }
            }
            if (detailLine.isNotEmpty()) {
                Text(
                    detailLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (row.hearted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                row.hearted.forEach { ep ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // One filled heart for tier 1 (Excellent), two for tier 2
                        // (Most-excellent). Cheap visual distinction without
                        // dropping a second icon style on the screen.
                        Icon(
                            painterResource(R.drawable.favorite_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        if (ep.favoriteTier >= 2) {
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                painterResource(R.drawable.favorite_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            ep.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Backup import/export as a labeled section — lives at the bottom of the
 * metrics list (and alone in the empty state). Export = save this
 * install's history to a JSON file; import = merge one back in.
 */
@Composable
private fun BackupSection(onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Everything above — positions, hearts, archives, notes — as a " +
                "JSON file. Import merges a backup back in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(
                    painterResource(R.drawable.file_download_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Export")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(
                    painterResource(R.drawable.file_upload_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Import")
            }
        }
    }
}

private data class PodcastMetrics(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val artworkUrl: String?,
    /** Feed's full episode count from the cache; 0 when unhydrated. */
    val episodeCount: Int,
    val playedCount: Int,
    val noteCount: Int,
    val lastPlayedMillis: Long,
    val totalListenedMs: Long,
    val hearted: List<EpisodeStateEntity>
)

/** Suggested SAF filename: UTC date stamp, "-no-notes" marker when the
 *  notes switch is off so the file says what it holds. */
private fun defaultBackupFilename(includeNotes: Boolean): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    val suffix = if (includeNotes) "" else "-no-notes"
    return "lofipod-backup-$stamp$suffix.json"
}

private fun String.shortHost(): String = try {
    val u = java.net.URI(this)
    (u.host ?: this).removePrefix("www.").removePrefix("feed.").removePrefix("feeds.")
} catch (_: Exception) {
    this
}
