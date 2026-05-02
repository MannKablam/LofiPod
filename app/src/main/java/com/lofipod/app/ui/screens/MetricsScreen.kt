@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.Backup
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.EpisodeStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun MetricsScreen(onBack: () -> Unit) {
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
            val all = app.db.episodeStateDao().getAll()
            all.groupBy { it.feedUrl }.map { (feedUrl, episodes) ->
                PodcastMetrics(
                    feedUrl = feedUrl,
                    title = Sources.displayNameOf(feedUrl) ?: feedUrl.shortHost(),
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
                    val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    Backup.export(episodes, notes, checkpoints, pkg.versionName ?: "?")
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Metrics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Open the include-notes choice first; the export
                        // launcher fires from inside the dialog.
                        pendingExportUri = android.net.Uri.EMPTY
                    }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Export backup")
                    }
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                    }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Import backup")
                    }
                }
            )
        }
    ) { padding ->
        when {
            !loaded -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("No listening data yet.") }

            else -> {
                Column(Modifier.padding(padding)) {
                    val totalH = rows.sumOf { it.totalListenedMs } / 1000.0 / 3600.0
                    val totalHearted = rows.sumOf { it.hearted.size }
                    Text(
                        "%.2f h listened across all podcasts • %d hearted".format(totalH, totalHearted),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rows, key = { it.feedUrl }) { row -> PodcastMetricsCard(row) }
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
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date())
                    val suffix = if (exportIncludeNotes) "" else "-no-notes"
                    exportLauncher.launch("lofipod-backup-$stamp$suffix.json")
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
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            val hours = row.totalListenedMs / 1000.0 / 3600.0
            Text(
                "%.2f h listened • %d hearted".format(hours, row.hearted.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.hearted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                row.hearted.forEach { ep ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // One filled heart for tier 1 (Excellent), two for tier 2
                        // (Most-excellent). Cheap visual distinction without
                        // dropping a second icon style on the screen.
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        if (ep.favoriteTier >= 2) {
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                Icons.Filled.Favorite,
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

private data class PodcastMetrics(
    val feedUrl: String,
    val title: String,
    val totalListenedMs: Long,
    val hearted: List<EpisodeStateEntity>
)

private fun String.shortHost(): String = try {
    val u = java.net.URI(this)
    (u.host ?: this).removePrefix("www.").removePrefix("feed.").removePrefix("feeds.")
} catch (_: Exception) {
    this
}
