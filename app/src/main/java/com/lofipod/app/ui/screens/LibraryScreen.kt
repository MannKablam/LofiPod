@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import com.lofipod.app.ui.FeedLoadStatus
import com.lofipod.app.ui.LibraryViewModel
import com.lofipod.app.ui.theme.PressStart2P

@Composable
fun LibraryScreen(
    controller: PlayerController,
    onPodcastClick: (Podcast) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenMetrics: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit
) {
    val vm: LibraryViewModel = viewModel()
    val state by vm.state.collectAsState()
    val playerState by controller.state.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LofiPod",
                        fontFamily = PressStart2P,
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
                    IconButton(onClick = onOpenFavorites) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorites",
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
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
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
                            PodcastRow(pod = pod, onClick = { onPodcastClick(pod) })
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
private fun PodcastRow(pod: Podcast, onClick: () -> Unit) {
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
            AsyncImage(
                model = pod.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
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
            }
        }
    }
}
