@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.PlaybackCheckpointEntity
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Global playback history. Shows all checkpoints (capped at 200, newest first), with
 * podcast + episode title context so the user can tell what each row corresponds to.
 * Tap a row to jump to that position.
 */
@Composable
fun HistoryScreen(
    controller: PlayerController,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) {
            val checkpoints = app.db.playbackCheckpointDao().getAll()
            val guids = checkpoints.map { it.guid }.distinct()
            val states = app.db.episodeStateDao().getByGuids(guids).associateBy { it.guid }
            checkpoints.map { cp ->
                val state = states[cp.guid]
                val feedUrl = state?.feedUrl
                // Try the canon's hardcoded displayName first, then fall back to
                // the parsed Podcast.title from the in-memory feed cache. Most
                // entries in Sources.PODCASTS have displayName = null and rely
                // on the parsed title — without this fallback every history row
                // showed up as "(unknown podcast)".
                val podcastTitle = feedUrl?.let { url ->
                    Sources.displayNameOf(url) ?: app.repo.cached(url)?.title
                } ?: "(unknown podcast)"
                HistoryRow(
                    checkpoint = cp,
                    podcastTitle = podcastTitle,
                    episodeTitle = state?.title ?: "(unknown episode)"
                )
            }
        }
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback history") },
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
        when {
            !loaded -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No history yet. Checkpoints accumulate as you jump between notes and switch episodes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.checkpoint.id }) { row ->
                        HistoryCard(
                            row = row,
                            onJump = {
                                controller.jumpToCheckpoint(row.checkpoint)
                                onBack()
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.db.playbackCheckpointDao().delete(row.checkpoint.id)
                                    }
                                    reload()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    row: HistoryRow,
    onJump: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJump),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                row.podcastTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                row.episodeTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        citationOf(row.checkpoint.recordedAt, row.checkpoint.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        reasonLabel(row.checkpoint.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onJump, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "Jump to this position",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete checkpoint",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun reasonLabel(reason: String): String = when (reason) {
    "jump_from" -> "Before a note jump"
    "session_end" -> "End of a listening session"
    "promoted_to_most_excellent" -> "Promoted to most-excellent"
    else -> reason
}

private data class HistoryRow(
    val checkpoint: PlaybackCheckpointEntity,
    val podcastTitle: String,
    val episodeTitle: String
)
