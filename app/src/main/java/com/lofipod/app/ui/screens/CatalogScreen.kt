@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.db.FeedVisitEntity
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import com.lofipod.app.ui.FeedLoadStatus
import com.lofipod.app.ui.CatalogViewModel
import com.lofipod.app.ui.theme.ThemedArtwork
import com.lofipod.app.ui.theme.lofiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CatalogScreen(
    controller: PlayerController,
    onPodcastClick: (Podcast) -> Unit,
    onOpenMyLists: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenMetrics: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val vm: CatalogViewModel = viewModel()
    val state by vm.state.collectAsState()
    val playerState by controller.state.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    // Per-feed last-visited timestamps. Drives the "new episodes" badge.
    val app = LocalContext.current.applicationContext as LofiPodApp
    val feedVisits by app.db.feedVisitDao().observeAll()
        .collectAsState(initial = emptyList<FeedVisitEntity>())
    val visitsByFeed = remember(feedVisits) {
        feedVisits.associate { it.feedUrl to it.lastVisitedAt }
    }

    // First-visit seeding: a podcast we've never seen a visit for gets a row
    // stamped to NOW so we don't lump every previously-released episode into
    // the "new" count. Subsequent loads find the row and use it for the diff.
    LaunchedEffect(state.podcasts.map { it.feedUrl }, visitsByFeed.keys) {
        if (state.podcasts.isEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val toSeed = state.podcasts
            .map { it.feedUrl }
            .filter { it !in visitsByFeed }
        if (toSeed.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val dao = app.db.feedVisitDao()
                for (url in toSeed) dao.seedIfMissing(url, now)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LofiPod",
                        fontFamily = lofiTheme.displayFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp
                    )
                },
                actions = {
                    if (playerState.currentEpisodeGuid != null) {
                        IconButton(onClick = onOpenNowPlaying) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = "Now playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onOpenNotes) {
                        Icon(
                            Icons.Filled.EditNote,
                            contentDescription = "Notes",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenMyLists) {
                        Icon(
                            Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = "My lists",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            // Tinted with onSurfaceVariant so it doesn't render
                            // as harsh near-black on light themes (Daylight in
                            // particular). Still readable on dark themes —
                            // onSurfaceVariant is the standard "secondary
                            // foreground" slot in every Material color scheme.
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Search episodes") },
                                onClick = { menuExpanded = false; onOpenSearch() },
                                leadingIcon = { Icon(Icons.Filled.Search, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Playback history") },
                                onClick = { menuExpanded = false; onOpenHistory() },
                                leadingIcon = { Icon(Icons.Filled.History, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Metrics") },
                                onClick = { menuExpanded = false; onOpenMetrics() },
                                leadingIcon = { Icon(Icons.Filled.BarChart, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("EQ & speed") },
                                onClick = { menuExpanded = false; onOpenEq() },
                                leadingIcon = { Icon(Icons.Filled.GraphicEq, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh feeds") },
                                onClick = { menuExpanded = false; vm.refresh() },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                state.loading -> {
                    if (state.feedProgress.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        FeedProgressList(state.feedProgress)
                    }
                }
                state.error != null -> {
                    ErrorState(state.error!!) { vm.refresh() }
                }
                state.podcasts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No podcasts in the canon.")
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.podcasts) { pod ->
                            val lastSeen = visitsByFeed[pod.feedUrl]
                            val newCount = newEpisodesSince(pod, lastSeen)
                            PodcastRow(
                                pod = pod,
                                newEpisodeCount = newCount,
                                onClick = { onPodcastClick(pod) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedProgressList(entries: List<FeedLoadStatus>) {
    val done = entries.count { it.status != FeedStatus.LOADING }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Loading feeds  ($done / ${entries.size})",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (entries.isEmpty()) 0f else done.toFloat() / entries.size },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries) { fs ->
                FeedProgressRow(fs)
            }
        }
    }
}

@Composable
private fun FeedProgressRow(fs: FeedLoadStatus) {
    val label = fs.source.displayName ?: fs.source.feedUrl.shortLabel()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (fs.status) {
                FeedStatus.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                FeedStatus.OK -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Loaded",
                    tint = MaterialTheme.colorScheme.primary
                )
                FeedStatus.FAILED -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error
                )
                FeedStatus.TIMEOUT -> Icon(
                    Icons.Filled.HourglassEmpty,
                    contentDescription = "Timed out",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (fs.status == FeedStatus.FAILED || fs.status == FeedStatus.TIMEOUT) {
                fs.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * "N new" pill in the row, styled in primary so it pops against the surface
 * card. Uses a [Surface] rather than Material's [Badge] so the count reads as
 * normal text alongside the episode count rather than a notification dot.
 */
@Composable
private fun NewEpisodesBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "$count new",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Count of episodes whose pubDate is strictly after the last-visited timestamp.
 * Returns 0 when [lastVisitedAt] is null (no visit row yet — the LaunchedEffect
 * in [CatalogScreen] seeds one to NOW so this is a transient state on first
 * Catalog load and never a "show all episodes as new" surprise).
 */
private fun newEpisodesSince(pod: Podcast, lastVisitedAt: Long?): Int {
    val cutoff = lastVisitedAt ?: return 0
    return pod.episodes.count { ep -> (ep.pubDateMillis ?: 0L) > cutoff }
}

private fun String.shortLabel(): String = try {
    val u = java.net.URI(this)
    (u.host ?: this).removePrefix("www.").removePrefix("feed.").removePrefix("feeds.")
} catch (_: Exception) {
    this
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun PodcastRow(
    pod: Podcast,
    newEpisodeCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemedArtwork(artworkUrl = pod.artworkUrl, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pod.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )
                Text(
                    "${pod.episodes.size} episodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (newEpisodeCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    NewEpisodesBadge(count = newEpisodeCount)
                }
            }
        }
    }
}
