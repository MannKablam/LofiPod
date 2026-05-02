package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.db.QueueEntryEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.player.PlayerController
import com.lofipod.app.ui.theme.ThemedArtwork
import com.lofipod.app.util.shareEnclosure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single home for all of the user's curated lists. Tabs: Queue, Favorites,
 * Rated, Downloaded. The Queue tab is the only one that can mutate ordering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListsScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onPlayEntity: (EpisodeStateEntity) -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val ctx = LocalContext.current

    val queue by app.db.queueEntryDao().observeAll().collectAsState(initial = emptyList())
    val excellent by app.db.episodeStateDao().observeAtTier(1)
        .collectAsState(initial = emptyList())
    val mostExcellent by app.db.episodeStateDao().observeAtTier(2)
        .collectAsState(initial = emptyList())
    val downloads by app.downloadsApi.byId.collectAsState()

    var tab by remember { mutableStateOf(0) }

    val completed = remember(downloads) {
        downloads.values.filter { it.state == Download.STATE_COMPLETED }.map { it.request.id }
    }
    var downloadedRows by remember { mutableStateOf<List<EpisodeStateEntity>>(emptyList()) }
    LaunchedEffect(completed) {
        withContext(Dispatchers.IO) {
            val dao = app.db.episodeStateDao()
            downloadedRows = completed.mapNotNull { dao.get(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My lists") },
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
        Column(Modifier.padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Queue (${queue.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Most-excellent (${mostExcellent.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("Excellent (${excellent.size})") })
                Tab(selected = tab == 3, onClick = { tab = 3 },
                    text = { Text("Downloaded") })
            }
            when (tab) {
                0 -> QueueTab(queue = queue, controller = controller)
                1 -> EntityList(mostExcellent, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
                2 -> EntityList(excellent, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
                else -> EntityList(downloadedRows, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
            }
        }
    }
}

@Composable
private fun QueueTab(
    queue: List<QueueEntryEntity>,
    controller: PlayerController
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Queue is empty. Add episodes from a feed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(queue, key = { it.guid }) { entry ->
            val index = queue.indexOf(entry)
            QueueRow(
                entry = entry,
                isFirst = index == 0,
                isLast = index == queue.lastIndex,
                onPlay = {
                    // Build best-effort Episode and play immediately.
                    val cached = app.repo.cached(entry.feedUrl)
                    val ep = cached?.episodes?.find { it.guid == entry.guid } ?: Episode(
                        guid = entry.guid,
                        feedUrl = entry.feedUrl,
                        title = entry.title,
                        description = null,
                        pubDateMillis = null,
                        audioUrl = entry.audioUrl,
                        audioMimeType = null,
                        durationSeconds = null,
                        episodeArtworkUrl = entry.artworkUrl
                    )
                    controller.playEpisode(
                        ep,
                        podcastTitle = cached?.title ?: "",
                        podcastArt = cached?.artworkUrl ?: entry.artworkUrl
                    )
                },
                onMoveUp = {
                    val newOrder = queue.toMutableList().apply {
                        val i = indexOfFirst { it.guid == entry.guid }
                        if (i > 0) add(i - 1, removeAt(i))
                    }.map { it.guid }
                    controller.reorderQueue(newOrder)
                },
                onMoveDown = {
                    val newOrder = queue.toMutableList().apply {
                        val i = indexOfFirst { it.guid == entry.guid }
                        if (i in 0 until size - 1) add(i + 1, removeAt(i))
                    }.map { it.guid }
                    controller.reorderQueue(newOrder)
                },
                onRemove = { controller.removeFromQueue(entry.guid) }
            )
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntryEntity,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemedArtwork(artworkUrl = entry.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up",
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down",
                        modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove from queue")
            }
        }
    }
}

@Composable
private fun EntityList(
    list: List<EpisodeStateEntity>,
    onPlay: (EpisodeStateEntity) -> Unit,
    onShare: (EpisodeStateEntity) -> Unit
) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(list, key = { it.guid }) { e ->
            EpisodeStateRow(e = e, onPlay = { onPlay(e) }, onShare = { onShare(e) })
        }
    }
}

@Composable
private fun EpisodeStateRow(
    e: EpisodeStateEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemedArtwork(artworkUrl = e.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (e.favoriteTier > 0) {
                    Row {
                        repeat(e.favoriteTier) {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
            }
        }
    }
}
