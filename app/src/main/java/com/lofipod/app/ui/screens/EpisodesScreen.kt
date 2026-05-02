package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import com.lofipod.app.LofiPodApp
import com.lofipod.app.ui.theme.ThemedArtwork
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

/** Auto-archive horizon. Played episodes whose lastPlayedMillis is older than
 *  this get swept into the archive on EpisodesScreen open. */
private const val AUTO_ARCHIVE_MS = 3L * 24 * 3600 * 1000  // 3 days

/** UI snapshot of one episode's persisted state. */
private data class EpisodeUiState(
    val favoriteTier: Int = 0,
    val archivedAt: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    /** Played to completion — within 5s of the end with a known duration. */
    val isPlayed: Boolean
        get() = durationMs > 0 && positionMs >= durationMs - 5_000
}

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
    val snackbarHostState = remember { SnackbarHostState() }
    val settings = remember { com.lofipod.app.data.Settings(app) }
    val showPlayedInList by settings.showPlayedInList.collectAsState(initial = true)

    val episodeStates = remember { mutableStateMapOf<String, EpisodeUiState>() }
    var showArchived by remember { mutableStateOf(false) }

    // Sweep + load. Auto-archive runs every time the screen is entered (cheap —
    // single indexed query). Then we hydrate per-episode state into the map.
    LaunchedEffect(pod) {
        if (pod == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dao = app.db.episodeStateDao()
            val cutoff = System.currentTimeMillis() - AUTO_ARCHIVE_MS
            val eligible = dao.guidsEligibleForAutoArchive(feedUrl, cutoff)
            if (eligible.isNotEmpty()) {
                dao.bulkArchive(eligible, System.currentTimeMillis())
                // Archived episodes don't need their downloads taking up disk.
                val haveDownloads = app.downloadsApi.byId.value.keys
                for (g in eligible) {
                    if (g in haveDownloads) app.downloadsApi.remove(g)
                }
            }
            for (ep in pod.episodes) {
                val s = dao.get(ep.guid) ?: continue
                episodeStates[ep.guid] = EpisodeUiState(
                    favoriteTier = s.favoriteTier,
                    archivedAt = s.archivedAt,
                    positionMs = s.positionMs,
                    durationMs = s.durationMs,
                )
            }
            // Mark the feed as visited — clears the new-episodes badge on Library.
            app.db.feedVisitDao().upsert(
                com.lofipod.app.data.db.FeedVisitEntity(
                    feedUrl, System.currentTimeMillis()
                )
            )
        }
    }

    val downloadsByGuid by app.downloadsApi.byId.collectAsState()
    val queueGuids by app.db.queueEntryDao().observeAll()
        .collectAsState(initial = emptyList())
    val queueSet = remember(queueGuids) { queueGuids.map { it.guid }.toSet() }

    val archivedCount = episodeStates.values.count { it.archivedAt > 0 }
    val visibleEpisodes = (pod?.episodes ?: emptyList()).filter { ep ->
        val s = episodeStates[ep.guid]
        val archived = (s?.archivedAt ?: 0L) > 0L
        val played = s?.isPlayed == true
        when {
            showArchived -> true
            archived -> false
            !showPlayedInList && played -> false
            else -> true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                },
                actions = {
                    // Archive visibility toggle. The icon flips between open and
                    // closed-archive boxes so the active state reads at a glance.
                    IconButton(onClick = { showArchived = !showArchived }) {
                        Icon(
                            if (showArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                            contentDescription = if (showArchived)
                                "Hide archived ($archivedCount)"
                            else "Show archived ($archivedCount)",
                            tint = if (showArchived) MaterialTheme.colorScheme.primary
                                   else LocalContentColor.current,
                            modifier = Modifier.size(26.dp)
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
            items(visibleEpisodes, key = { it.guid }) { ep ->
                val s = episodeStates[ep.guid] ?: EpisodeUiState()
                val isCurrent = playerState.currentEpisodeGuid == ep.guid
                EpisodeRow(
                    ep = ep,
                    podcastArt = pod.artworkUrl,
                    state = s,
                    isQueued = ep.guid in queueSet,
                    download = downloadsByGuid[ep.guid],
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && playerState.isPlaying,
                    onPlay = {
                        if (isCurrent) controller.togglePlay()
                        else onPlay(ep, pod)
                    },
                    onShare = { ctx.shareEnclosure(ep.audioUrl, ep.title) },
                    onCycleHeart = {
                        val next = (s.favoriteTier + 1) % 3
                        episodeStates[ep.guid] = s.copy(favoriteTier = next)
                        scope.launch { upsertState(app, ep, pod, newTier = next) }
                    },
                    onToggleDownload = {
                        val d = downloadsByGuid[ep.guid]
                        if (d == null || d.state == Download.STATE_FAILED) {
                            scope.launch {
                                upsertState(app, ep, pod)
                                app.downloadsApi.start(ep)
                                snackbarHostState.showSnackbar("Download started")
                            }
                        } else {
                            app.downloadsApi.remove(ep.guid)
                        }
                    },
                    onToggleQueue = {
                        if (ep.guid in queueSet) controller.removeFromQueue(ep.guid)
                        else controller.enqueue(ep, pod.title, pod.artworkUrl)
                    },
                    onToggleArchive = {
                        val nowArchived = s.archivedAt > 0
                        val newAt = if (nowArchived) 0L else System.currentTimeMillis()
                        episodeStates[ep.guid] = s.copy(archivedAt = newAt)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // Make sure a row exists before flipping the flag —
                                // some episodes have never been touched by playback.
                                upsertState(app, ep, pod)
                                app.db.episodeStateDao().setArchivedAt(ep.guid, newAt)
                            }
                            if (!nowArchived && app.downloadsApi.byId.value.containsKey(ep.guid)) {
                                app.downloadsApi.remove(ep.guid)
                            }
                            snackbarHostState.showSnackbar(
                                if (nowArchived) "Unarchived" else "Archived"
                            )
                        }
                    }
                )
            }
            if (visibleEpisodes.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (showArchived) "No episodes."
                            else "All episodes are archived. Tap the archive icon to show them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    podcastArt: String?,
    state: EpisodeUiState,
    isQueued: Boolean,
    download: Download?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onCycleHeart: () -> Unit,
    onToggleDownload: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val isArchived = state.archivedAt > 0
    val isPlayed = state.isPlayed

    // Played-but-not-active rows fade to a softer surface and dim the text so
    // the user's eye skips them when scanning. The "playing now" tint always
    // wins over the played gray-out so the current row stays prominent.
    val containerColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isPlayed || isArchived -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textAlpha = if (!isCurrent && (isPlayed || isArchived)) 0.55f else 1f
    val titleDecoration = if (isPlayed && !isCurrent) TextDecoration.LineThrough
                          else TextDecoration.None

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(12.dp)) {
            if (isArchived) {
                ArchivedChip()
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedArtwork(
                    artworkUrl = ep.episodeArtworkUrl ?: podcastArt,
                    size = 56.dp
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
                        } else if (isPlayed) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Played",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            ep.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = textAlpha),
                            textDecoration = titleDecoration
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
                            .copy(alpha = textAlpha)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = textAlpha),
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
                        isPlayed -> "Replay"
                        else -> "Play"
                    })
                }
                Spacer(Modifier.width(8.dp))
                DownloadButton(download = download, onClick = onToggleDownload)
                IconButton(onClick = onToggleQueue) {
                    Icon(
                        if (isQueued) Icons.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = if (isQueued) "Remove from queue" else "Add to queue",
                        tint = if (isQueued) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                IconButton(onClick = onToggleArchive) {
                    Icon(
                        if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = if (isArchived) "Unarchive" else "Archive",
                        tint = if (isArchived) MaterialTheme.colorScheme.primary
                               else LocalContentColor.current
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share raw link")
                }
                Spacer(Modifier.weight(1f))
                HeartTierButton(tier = state.favoriteTier, onCycle = onCycleHeart)
            }
        }
    }
}

@Composable
private fun ArchivedChip() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Archive,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Archived",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic
            )
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
            // Show the percentage *next to* the spinner so progress reads even
            // when the download finishes in a couple of seconds. Without the
            // number, fast downloads look like the button just teleported to
            // the checkmark state.
            val pct = download.percentDownloaded
            val pctLabel = when {
                !pct.isFinite() -> "…"
                pct < 0f -> "…"
                else -> "${pct.toInt()}%"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    pctLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

/**
 * Single-button heart tier control. One tap cycles 0 → 1 (Excellent) →
 * 2 (Most-excellent) → 0. Tier 1 shows a single filled heart; tier 2 shows
 * two — a visual "more". Replaces the old 1-5 star row.
 */
@Composable
private fun HeartTierButton(tier: Int, onCycle: () -> Unit) {
    val tint = if (tier > 0) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onCycle, modifier = Modifier.size(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (tier > 0) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = when (tier) {
                    2 -> "Most-excellent"
                    1 -> "Excellent"
                    else -> "Mark as Excellent"
                },
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            if (tier == 2) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
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
    newTier: Int? = null
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
        favoriteTier = newTier ?: existing?.favoriteTier ?: 0
    )
    dao.upsert(merged)
}
