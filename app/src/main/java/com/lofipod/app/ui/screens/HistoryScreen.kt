@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.PlaybackCheckpointEntity
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Global playback history. Shows all checkpoints (capped at 200, newest first), with
 * podcast + episode title context so the user can tell what each row corresponds to.
 * Tap a row to jump to that position. Filter chips at top scope to a single reason
 * type; rows are grouped under day headers (Today / Yesterday / "Mon, Apr 28").
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
    var activeFilter by remember { mutableStateOf<String?>(null) }

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

    val visibleRows = remember(rows, activeFilter) {
        if (activeFilter == null) rows
        else rows.filter { it.checkpoint.reason == activeFilter }
    }
    // Group adjacent rows by day. Day buckets are computed in the device's
    // local timezone so the user's perception of "today" matches the bucket.
    val grouped = remember(visibleRows) { groupByDay(visibleRows) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback history") },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Filter chip row — present even when the underlying list is
            // empty, so the user can see at-a-glance what reason categories
            // exist without having to wait for data to arrive.
            FilterChipRow(
                rows = rows,
                active = activeFilter,
                onPick = { picked -> activeFilter = picked }
            )
            when {
                !loaded -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                rows.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No history yet. Checkpoints accumulate as you jump between notes, switch episodes, or promote an episode to most-excellent.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                visibleRows.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No checkpoints in this category.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        grouped.forEach { (label, dayRows) ->
                            item(key = "header-$label") {
                                DayHeader(label, count = dayRows.size)
                            }
                            items(dayRows, key = { it.checkpoint.id }) { row ->
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
    }
}

/**
 * Horizontal scrolling row of filter chips. "All" is always present and
 * always reads the unfiltered total; per-reason chips show their own
 * category counts and are dimmed when the count is zero (still tappable —
 * lets the user see the empty-category message rather than silently doing
 * nothing).
 */
@Composable
private fun FilterChipRow(
    rows: List<HistoryRow>,
    active: String?,
    onPick: (String?) -> Unit
) {
    val byReason = remember(rows) { rows.groupingBy { it.checkpoint.reason }.eachCount() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // "All" gets a leading icon for visual rhythm with the per-reason
        // chips below. Without one, the row alternates icon-vs-no-icon and
        // chip widths fight each other (the "Sessions" chip in particular
        // looked oddly wide next to the icon-less All). Uniform chip shape
        // = readable rhythm even when counts vary.
        FilterChip(
            selected = active == null,
            onClick = { onPick(null) },
            label = { Text("All (${rows.size})") },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.list_24),
                    null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        val reasonOrder = listOf(
            PlayerController.REASON_PROMOTED_TO_MOST_EXCELLENT,
            PlayerController.REASON_JUMP_FROM,
            PlayerController.REASON_SESSION_END
        )
        reasonOrder.forEach { reason ->
            val count = byReason[reason] ?: 0
            FilterChip(
                selected = active == reason,
                onClick = { onPick(reason) },
                label = { Text("${shortReasonLabel(reason)} ($count)") },
                leadingIcon = { Icon(reasonIcon(reason), null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            reasonIcon(row.checkpoint.reason),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            reasonLabel(row.checkpoint.reason),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onJump, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.play_circle_24),
                        contentDescription = "Jump to this position",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.delete_24),
                        contentDescription = "Delete checkpoint",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun reasonLabel(reason: String): String = when (reason) {
    PlayerController.REASON_JUMP_FROM -> "Before a note jump"
    PlayerController.REASON_SESSION_END -> "End of a listening session"
    PlayerController.REASON_PROMOTED_TO_MOST_EXCELLENT -> "Promoted to most-excellent"
    else -> reason
}

/** Compact label for the filter chips — full label crowds the chip row. */
private fun shortReasonLabel(reason: String): String = when (reason) {
    PlayerController.REASON_JUMP_FROM -> "Jumps"
    PlayerController.REASON_SESSION_END -> "Sessions"
    PlayerController.REASON_PROMOTED_TO_MOST_EXCELLENT -> "Promotions"
    else -> reason
}

@Composable
private fun reasonIcon(reason: String) = when (reason) {
    PlayerController.REASON_JUMP_FROM -> painterResource(R.drawable.undo_24)
    PlayerController.REASON_SESSION_END -> painterResource(R.drawable.stop_circle_24)
    PlayerController.REASON_PROMOTED_TO_MOST_EXCELLENT -> painterResource(R.drawable.favorite_24)
    else -> painterResource(R.drawable.play_circle_24)
}

/**
 * Bucket [rows] into day groups while preserving overall order. Returns a list
 * of (label, rowsInDay) pairs so the caller can emit a header before each
 * group. Today / Yesterday get friendly labels; older days fall back to a
 * "Mon, Apr 28" style. Buckets are timezone-local — the user's perception of
 * "today" should match the bucket boundary.
 */
private fun groupByDay(rows: List<HistoryRow>): List<Pair<String, List<HistoryRow>>> {
    if (rows.isEmpty()) return emptyList()
    val now = Calendar.getInstance()
    val todayKey = dayKey(now.timeInMillis)
    val yesterdayKey = dayKey(now.timeInMillis - 24L * 3600 * 1000)
    val older = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    val out = mutableListOf<Pair<String, MutableList<HistoryRow>>>()
    var lastKey: String? = null
    rows.forEach { row ->
        val key = dayKey(row.checkpoint.recordedAt)
        if (key != lastKey) {
            val label = when (key) {
                todayKey -> "Today"
                yesterdayKey -> "Yesterday"
                else -> older.format(Date(row.checkpoint.recordedAt))
            }
            out += (label to mutableListOf())
            lastKey = key
        }
        out.last().second += row
    }
    return out.map { (l, r) -> l to r.toList() }
}

/** "yyyy-DDD" key — stable per local-timezone calendar day. */
private fun dayKey(epochMs: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = epochMs }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
}

private data class HistoryRow(
    val checkpoint: PlaybackCheckpointEntity,
    val podcastTitle: String,
    val episodeTitle: String
)
