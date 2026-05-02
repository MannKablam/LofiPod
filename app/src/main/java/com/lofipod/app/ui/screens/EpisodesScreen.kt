package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import com.lofipod.app.util.shareEnclosure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodesScreen(
    feedUrl: String,
    controller: PlayerController,
    onBack: () -> Unit,
    onPlay: (Episode, Podcast) -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val pod = remember(feedUrl) { app.repo.cached(feedUrl) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by controller.state.collectAsState()

    // Map of guid -> (rating, isFavorite). Loaded once when entering the screen.
    val episodeStates = remember { mutableStateMapOf<String, Pair<Int, Boolean>>() }
    LaunchedEffect(pod) {
        if (pod == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dao = app.db.episodeStateDao()
            for (ep in pod.episodes) {
                val s = dao.get(ep.guid) ?: continue
                episodeStates[ep.guid] = s.rating to s.isFavorite
            }
        }
    }

    val downloadsByGuid by app.downloadsApi.byId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pod?.title ?: "Loading…", maxLines = 1) },
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
        if (pod == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Feed not loaded.")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(pod.episodes, key = { it.guid }) { ep ->
                val (rating, fav) = episodeStates[ep.guid] ?: (0 to false)
                val isCurrent = playerState.currentEpisodeGuid == ep.guid
                EpisodeRow(
                    ep = ep,
                    podcastArt = pod.artworkUrl,
                    rating = rating,
                    isFavorite = fav,
                    download = downloadsByGuid[ep.guid],
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && playerState.isPlaying,
                    onPlay = {
                        if (isCurrent) controller.togglePlay()
                        else onPlay(ep, pod)
                    },
                    onShare = { ctx.shareEnclosure(ep.audioUrl, ep.title) },
                    onToggleFav = {
                        val newFav = !fav
                        episodeStates[ep.guid] = rating to newFav
                        scope.launch { upsertState(app, ep, pod, newFav = newFav) }
                    },
                    onSetRating = { r ->
                        episodeStates[ep.guid] = r to fav
                        scope.launch { upsertState(app, ep, pod, newRating = r) }
                    },
                    onToggleDownload = {
                        val d = downloadsByGuid[ep.guid]
                        if (d == null || d.state == Download.STATE_FAILED) {
                            // Persist a row so the Downloaded tab can resolve metadata later.
                            scope.launch { upsertState(app, ep, pod) }
                            app.downloadsApi.start(ep)
                        } else {
                            app.downloadsApi.remove(ep.guid)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    podcastArt: String?,
    rating: Int,
    isFavorite: Boolean,
    download: Download?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onToggleFav: () -> Unit,
    onSetRating: (Int) -> Unit,
    onToggleDownload: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        // Tint the row when this episode is loaded in the player so users can spot
        // "what's currently playing" in the list at a glance.
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            // Tap anywhere on the card (outside inner buttons, which consume their
            // own taps) toggles expanded state — full description, no playback.
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ep.episodeArtworkUrl ?: podcastArt,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCurrent) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Now playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            ep.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        buildString {
                            ep.pubDateMillis?.let {
                                append(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it)))
                            }
                            ep.durationSeconds?.let {
                                if (isNotEmpty()) append(" • ")
                                append("${it / 60} min")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            ep.description?.stripHtml()?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(6.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(when {
                        isPlaying -> "Pause"
                        isCurrent -> "Resume"
                        else -> "Play"
                    })
                }
                Spacer(Modifier.width(8.dp))
                DownloadButton(download = download, onClick = onToggleDownload)
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share raw link")
                }
                IconButton(onClick = onToggleFav) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                Spacer(Modifier.weight(1f))
                StarRow(rating, onSetRating)
            }
        }
    }
}

@Composable
private fun DownloadButton(download: Download?, onClick: () -> Unit) {
    when (download?.state) {
        null -> {
            IconButton(onClick = onClick) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> {
            val pct = download.percentDownloaded
            IconButton(onClick = onClick) {
                Box(contentAlignment = Alignment.Center) {
                    if (pct.isFinite() && pct >= 0f) {
                        CircularProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cancel download",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Download.STATE_COMPLETED -> {
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Download.STATE_FAILED -> {
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> {
            IconButton(onClick = onClick) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
    }
}

@Composable
private fun StarRow(rating: Int, onClick: (Int) -> Unit) {
    Row {
        for (i in 1..5) {
            IconButton(
                onClick = { onClick(if (rating == i) 0 else i) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$i star",
                    tint = if (i <= rating) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Best-effort plain-text view of an HTML-ish description. */
private fun String.stripHtml(): String =
    this.replace(Regex("<[^>]*>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;|&apos;"), "'")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Read-modify-write for episode state. Runs on IO. */
private suspend fun upsertState(
    app: LofiPodApp,
    ep: Episode,
    pod: Podcast,
    newFav: Boolean? = null,
    newRating: Int? = null
) = withContext(Dispatchers.IO) {
    val dao = app.db.episodeStateDao()
    val existing = dao.get(ep.guid)
    val merged = (existing ?: EpisodeStateEntity(
        guid = ep.guid,
        feedUrl = ep.feedUrl,
        title = ep.title,
        audioUrl = ep.audioUrl,
        artworkUrl = ep.episodeArtworkUrl ?: pod.artworkUrl
    )).copy(
        isFavorite = newFav ?: existing?.isFavorite ?: false,
        rating = newRating ?: existing?.rating ?: 0
    )
    dao.upsert(merged)
}
