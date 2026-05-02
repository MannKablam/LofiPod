package com.lofipod.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rich gold for the Most-excellent pulsing heart. */
private val MostExcellentGold = Color(0xFFD4A017)

/**
 * Single home for all of the user's curated lists. Tabs: Queue, Excellent,
 * Most-excellent, Downloaded. Most-excellent gets a pulsing gold heart accent
 * to mark it as the top-tier destination — both on the tab strip itself and
 * per-row in the list.
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
                    text = { Text("Excellent (${excellent.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = {
                    // Pulsing gold heart leads the tab label so the highest
                    // tier is visually distinct from plain "Excellent".
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingGoldHeart(size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("Most-excellent (${mostExcellent.size})")
                    }
                })
                Tab(selected = tab == 3, onClick = { tab = 3 },
                    text = { Text("Downloaded") })
            }
            when (tab) {
                0 -> QueueTab(queue = queue, controller = controller)
                1 -> EntityList(excellent, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
                2 -> EntityList(mostExcellent, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
                else -> EntityList(downloadedRows, onPlay = onPlayEntity, onShare = { e ->
                    ctx.shareEnclosure(e.audioUrl, e.title)
                })
            }
        }
    }
}

/**
 * Animated gold heart — pulses alpha + a subtle scale on a 1.6 s loop.
 * Used to mark Most-excellent (top-tier favorite) at the tab strip and on
 * each row of the most-excellent list.
 */
@Composable
private fun PulsingGoldHeart(size: Dp) {
    val transition = rememberInfiniteTransition(label = "mostExcellentPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )
    // Map phase 0..1 to alpha 0.55..1.0 and scale 0.92..1.04 — gentle, not flashy.
    val alpha = 0.55f + 0.45f * phase
    val scale = 0.92f + 0.12f * phase
    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = MostExcellentGold,
        modifier = Modifier
            .size(size)
            .graphicsLayer(alpha = alpha, scaleX = scale, scaleY = scale)
    )
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
    val app = LocalContext.current.applicationContext as LofiPodApp
    // Pull pub date / duration from the in-memory cache when possible — gives
    // queue rows the same scannable meta line as the Episodes screen instead
    // of a bare title.
    val meta = remember(entry.feedUrl, entry.guid) {
        val ep = app.repo.cached(entry.feedUrl)?.episodes?.find { it.guid == entry.guid }
        episodeMetaLine(ep, fallbackDurationMs = 0L)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemedArtwork(artworkUrl = entry.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2
                )
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    val app = LocalContext.current.applicationContext as LofiPodApp
    val meta = remember(e.feedUrl, e.guid, e.durationMs) {
        val ep = app.repo.cached(e.feedUrl)?.episodes?.find { it.guid == e.guid }
        episodeMetaLine(ep, fallbackDurationMs = e.durationMs)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemedArtwork(artworkUrl = e.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (e.favoriteTier > 0) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.favoriteTier >= 2) {
                            // Single big pulsing gold heart for the top tier —
                            // distinct from the plain primary-tinted pip used
                            // for "Excellent" rows.
                            PulsingGoldHeart(size = 16.dp)
                        } else {
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

/** "MMM d, yyyy • XX min" using the cached Episode when available, falling
 *  back to the last-known duration in EpisodeStateEntity. */
private fun episodeMetaLine(ep: Episode?, fallbackDurationMs: Long): String = buildString {
    ep?.pubDateMillis?.let {
        append(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it)))
    }
    val durSec = ep?.durationSeconds
        ?: (fallbackDurationMs / 1000).takeIf { it > 0 }
    if (durSec != null) {
        if (isNotEmpty()) append(" • ")
        append("${durSec / 60} min")
    }
}
