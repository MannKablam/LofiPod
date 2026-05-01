package com.lofipod.app.ui.screens

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
import coil.compose.AsyncImage
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
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
    onBack: () -> Unit,
    onPlay: (Episode, Podcast) -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val pod = remember(feedUrl) { app.repo.cached(feedUrl) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pod?.title ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                EpisodeRow(
                    ep = ep,
                    podcastArt = pod.artworkUrl,
                    rating = rating,
                    isFavorite = fav,
                    onPlay = { onPlay(ep, pod) },
                    onShare = { ctx.shareEnclosure(ep.audioUrl, ep.title) },
                    onToggleFav = {
                        val newFav = !fav
                        episodeStates[ep.guid] = rating to newFav
                        scope.launch { upsertState(app, ep, pod, newFav = newFav) }
                    },
                    onSetRating = { r ->
                        episodeStates[ep.guid] = r to fav
                        scope.launch { upsertState(app, ep, pod, newRating = r) }
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
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onToggleFav: () -> Unit,
    onSetRating: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
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
                    Text(ep.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
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
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                Spacer(Modifier.width(8.dp))
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
private fun StarRow(rating: Int, onClick: (Int) -> Unit) {
    Row {
        for (i in 1..5) {
            IconButton(
                onClick = { onClick(if (rating == i) 0 else i) },
                modifier = Modifier.size(28.dp)
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
