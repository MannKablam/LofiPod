@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val INITIAL_LIMIT_FALLBACK = 25
private const val PAGE_SIZE = 50
private const val TWO_WEEKS_MS = 14L * 24 * 3600 * 1000

/**
 * Global notes browser. Default view: most-recent notes within the past two weeks,
 * or the latest 25 — whichever is more. Scrolling near the bottom loads 50 more.
 * Tap the search icon to switch to a live search across all note text.
 */
@Composable
fun NotesBrowserScreen(
    controller: PlayerController,
    onOpenEpisodeNotes: (episodeGuid: String) -> Unit,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var entries by remember { mutableStateOf<List<EpisodeNoteEntryEntity>>(emptyList()) }
    var titlesByGuid by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }

    // Bulk-selection mode (v0.10.15+). Long-press a note card to enter;
    // selection set stores "guid@createdAt" composite keys (matching the
    // primary-key shape of episode_note_entry). Top bar transforms while
    // non-empty (count + Clear + bulk delete). Tap in selection mode
    // toggles the tapped card's membership rather than opening it.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val inSelectionMode = selection.isNotEmpty()
    var confirmBulkDelete by remember { mutableStateOf(false) }

    fun selectionKey(entry: EpisodeNoteEntryEntity) = "${entry.guid}@${entry.createdAt}"

    BackHandler(enabled = inSelectionMode) {
        selection = emptySet()
    }

    suspend fun resolveTitles(forEntries: List<EpisodeNoteEntryEntity>) {
        val newGuids = forEntries.map { it.guid }.toSet() - titlesByGuid.keys
        if (newGuids.isEmpty()) return
        val states = withContext(Dispatchers.IO) {
            app.db.episodeStateDao().getByGuids(newGuids.toList())
        }
        titlesByGuid = titlesByGuid + states.associate { it.guid to it.title }
    }

    suspend fun loadInitial() {
        loading = true
        val dao = app.db.episodeNoteEntryDao()
        val cutoff = System.currentTimeMillis() - TWO_WEEKS_MS
        val (batch, hasMore) = withContext(Dispatchers.IO) {
            val recentCount = dao.countSince(cutoff)
            val limit = max(INITIAL_LIMIT_FALLBACK, recentCount)
            val list = dao.getMostRecent(limit, 0)
            list to (list.size == limit)  // if full page, more may exist
        }
        entries = batch
        endReached = !hasMore
        resolveTitles(batch)
        loading = false
    }

    suspend fun loadMore() {
        if (loading || endReached || searchMode) return
        loading = true
        val dao = app.db.episodeNoteEntryDao()
        val next = withContext(Dispatchers.IO) { dao.getMostRecent(PAGE_SIZE, entries.size) }
        if (next.isEmpty()) {
            endReached = true
        } else {
            entries = entries + next
            if (next.size < PAGE_SIZE) endReached = true
            resolveTitles(next)
        }
        loading = false
    }

    suspend fun runSearch(q: String) {
        loading = true
        val list = if (q.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            app.db.episodeNoteEntryDao().search(q)
        }
        entries = list
        endReached = true     // search is one-shot, no pagination
        resolveTitles(list)
        loading = false
    }

    LaunchedEffect(Unit) { loadInitial() }

    // Re-run search when query changes (debounced via distinctUntilChanged on the snapshot).
    LaunchedEffect(searchMode) {
        if (!searchMode) {
            query = ""
            loadInitial()
        } else {
            snapshotFlow { query }.distinctUntilChanged().collectLatest { runSearch(it) }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to listState.layoutInfo.totalItemsCount
        }.collectLatest { (last, total) ->
            if (!searchMode && total > 0 && last >= total - 5) loadMore()
        }
    }

    Scaffold(
        topBar = {
            // Three top-bar modes:
            //   selection — [X clear] "N selected" | [Delete bulk]
            //   search    — [back] [text field] | [Clear-query]
            //   default   — [back] "Notes" | [Search]
            // Selection wins over search if both somehow flip true.
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("${selection.size} selected", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(
                                painterResource(R.drawable.close_24),
                                contentDescription = "Clear selection",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmBulkDelete = true }) {
                            Icon(
                                painterResource(R.drawable.delete_24),
                                contentDescription = "Delete selected notes",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchMode) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search notes…") },
                                singleLine = true
                            )
                        } else {
                            Text("Notes")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (searchMode) searchMode = false else onBack()
                        }) {
                            Icon(
                                if (searchMode) painterResource(R.drawable.arrow_back_24) else painterResource(R.drawable.arrow_back_24),
                                contentDescription = if (searchMode) "Exit search" else "Back"
                            )
                        }
                    },
                    actions = {
                        if (searchMode) {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(painterResource(R.drawable.close_24), contentDescription = "Clear")
                                }
                            }
                        } else {
                            IconButton(onClick = { searchMode = true }) {
                                Icon(painterResource(R.drawable.search_24), contentDescription = "Search notes")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (entries.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchMode && query.isNotBlank()) "No notes match \"$query\"."
                        else "No notes yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { selectionKey(it) }) { entry ->
                        val key = selectionKey(entry)
                        BrowserNoteCard(
                            entry = entry,
                            episodeTitle = titlesByGuid[entry.guid],
                            highlight = if (searchMode) query else "",
                            inSelectionMode = inSelectionMode,
                            isSelected = key in selection,
                            onLongPress = {
                                selection = if (key in selection) selection - key
                                            else selection + key
                            },
                            onJump = {
                                if (inSelectionMode) {
                                    selection = if (key in selection) selection - key
                                                else selection + key
                                } else controller.jumpToNotePosition(entry)
                            },
                            onOpenInEpisode = {
                                if (inSelectionMode) {
                                    selection = if (key in selection) selection - key
                                                else selection + key
                                } else onOpenEpisodeNotes(entry.guid)
                            },
                            onShare = {
                                scope.launch { shareNoteEntry(ctx, entry) }
                            },
                        )
                    }
                    if (loading) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                        }
                    }
                }
            }
        }
    }

    if (confirmBulkDelete) {
        val count = selection.size
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete $count note${if (count == 1) "" else "s"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = selection.toList()
                    confirmBulkDelete = false
                    selection = emptySet()
                    scope.launch {
                        // Decompose composite keys back into (guid,
                        // createdAt) pairs for the per-row delete. The
                        // dao doesn't have a multi-delete; the loop is
                        // fine for typical bulk-select counts.
                        withContext(Dispatchers.IO) {
                            val dao = app.db.episodeNoteEntryDao()
                            for (key in toDelete) {
                                val at = key.lastIndexOf('@')
                                if (at < 0) continue
                                val guid = key.substring(0, at)
                                val createdAt = key.substring(at + 1).toLongOrNull() ?: continue
                                dao.delete(guid, createdAt)
                            }
                        }
                        // Drop the deleted keys from the local entries
                        // list so the LazyColumn updates immediately.
                        // The collectAsState path on the per-episode
                        // NotesScreen would re-emit; here we hold the
                        // list ourselves so we have to maintain it.
                        val deletedSet = toDelete.toSet()
                        entries = entries.filter { selectionKey(it) !in deletedSet }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowserNoteCard(
    entry: EpisodeNoteEntryEntity,
    episodeTitle: String?,
    highlight: String,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onJump: () -> Unit,
    onOpenInEpisode: () -> Unit,
    onShare: () -> Unit,
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.tertiaryContainer
                         else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenInEpisode,
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                episodeTitle ?: "(unknown episode)",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    citationOf(entry.createdAt, entry.playbackPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onJump, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.play_circle_24),
                        contentDescription = "Jump to position",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.share_24),
                        contentDescription = "Share note",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Highlight every case-insensitive occurrence of the search query
            // in the note body. AnnotatedString lets us mix highlighted and
            // plain runs without splitting into multiple Text nodes.
            Text(
                text = highlightedText(
                    source = entry.text,
                    needle = highlight,
                    highlightBg = MaterialTheme.colorScheme.primary,
                    highlightFg = MaterialTheme.colorScheme.onPrimary
                ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5
            )
        }
    }
}

/**
 * Build an AnnotatedString that paints every case-insensitive occurrence of
 * [needle] in [source] with [highlightBg] background + [highlightFg] foreground.
 * Returns plain AnnotatedString of [source] when [needle] is blank.
 */
private fun highlightedText(
    source: String,
    needle: String,
    highlightBg: androidx.compose.ui.graphics.Color,
    highlightFg: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    if (needle.isBlank()) return androidx.compose.ui.text.AnnotatedString(source)
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        val lower = source.lowercase()
        val target = needle.lowercase()
        while (cursor < source.length) {
            val hit = lower.indexOf(target, cursor)
            if (hit < 0) {
                append(source.substring(cursor))
                break
            }
            if (hit > cursor) append(source.substring(cursor, hit))
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = highlightBg,
                    color = highlightFg
                )
            ) {
                append(source.substring(hit, hit + needle.length))
            }
            cursor = hit + needle.length
        }
    }
}
