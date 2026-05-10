@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.PodcastRepository.FeedStatus
import com.lofipod.app.data.Sources
import com.lofipod.app.data.db.FeedVisitEntity
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.parser.SourceEntry
import com.lofipod.app.parser.SourceGroup
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
    onOpenSearch: () -> Unit,
    onOpenCanonBrowse: () -> Unit,
) {
    val vm: CatalogViewModel = viewModel()
    val state by vm.state.collectAsState()
    val playerState by controller.state.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // SAF picker for "Import pack…" — accepts any file (SAF can't filter by
    // .kabod extension), validates after open by attempting to parse. On
    // success, the pack lands in the catalog automatically via vm.refresh().
    val packPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val app = ctx.applicationContext as LofiPodApp
            val result = try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    app.kabodLoader.importStream(input)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Import failed: ${e.message}")
                null
            }
            if (result != null) {
                snackbarHostState.showSnackbar(
                    "Imported \"${result.podcast.title}\" — ${result.podcast.episodes.size} entries"
                )
                vm.refresh()
            } else {
                snackbarHostState.showSnackbar("That file isn't a valid Kabod Pack.")
            }
        }
    }

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
                            // GraphicEq (pulsing-bar live-audio glyph) for the
                            // Now Playing top-bar action — matches the same
                            // glyph used on PlayerScreen's top-bar title and
                            // EpisodesScreen's per-row "currently playing"
                            // marker. Replaced MusicNote here as part of the
                            // Audio Fine-tuning ↔ Now Playing icon split.
                            Icon(
                                Icons.Filled.GraphicEq,
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
                                text = { Text("Bible index") },
                                onClick = { menuExpanded = false; onOpenCanonBrowse() },
                                leadingIcon = { Icon(Icons.Filled.MenuBook, null) }
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
                                text = { Text("Audio Fine-tuning") },
                                onClick = { menuExpanded = false; onOpenEq() },
                                leadingIcon = { Icon(Icons.Filled.Tune, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh feeds") },
                                onClick = { menuExpanded = false; vm.refresh() },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Import pack…") },
                                onClick = {
                                    menuExpanded = false
                                    // SAF can't filter on .kabod; allow any
                                    // file and validate on parse.
                                    packPicker.launch(arrayOf("*/*"))
                                },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    // Loaded pods are flat — keyed by feed URL — but we render
                    // hierarchically off [Sources.PODCASTS] so groups can fold
                    // their children into one expandable card. Lookups by URL
                    // are O(1) via this map.
                    val podsByUrl = remember(state.podcasts) {
                        state.podcasts.associateBy { it.feedUrl }
                    }
                    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Kabod Packs render first — these are weighty, archived
                        // bodies of work (verse-by-verse expositions, etc.) given
                        // visual prominence by their distinguished card chrome.
                        Sources.KABOD_PACKS.forEach { entry ->
                            val pod = podsByUrl[entry.feedUrl]
                            if (pod != null) {
                                item(key = entry.feedUrl) {
                                    val newCount = newEpisodesSince(
                                        pod, visitsByFeed[entry.feedUrl]
                                    )
                                    KabodPackRow(
                                        pod = pod,
                                        artworkUrl = entry.customArtworkUrl ?: pod.artworkUrl,
                                        newEpisodeCount = newCount,
                                        onClick = { onPodcastClick(pod) }
                                    )
                                }
                            }
                        }
                        Sources.PODCASTS.forEach { catalogItem ->
                            when (catalogItem) {
                                is SourceEntry -> {
                                    val pod = podsByUrl[catalogItem.feedUrl]
                                    if (pod != null) {
                                        item(key = catalogItem.feedUrl) {
                                            val newCount = newEpisodesSince(
                                                pod, visitsByFeed[catalogItem.feedUrl]
                                            )
                                            PodcastRow(
                                                pod = pod,
                                                artworkUrl = catalogItem.customArtworkUrl ?: pod.artworkUrl,
                                                newEpisodeCount = newCount,
                                                onClick = { onPodcastClick(pod) }
                                            )
                                        }
                                    }
                                }
                                is SourceGroup -> {
                                    val loadedChildren = catalogItem.children.mapNotNull { entry ->
                                        podsByUrl[entry.feedUrl]?.let { pod -> entry to pod }
                                    }
                                    if (loadedChildren.isNotEmpty()) {
                                        val expanded = expandedGroups[catalogItem.groupId] == true
                                        val totalEps = loadedChildren.sumOf { it.second.episodes.size }
                                        val newSum = loadedChildren.sumOf { (entry, pod) ->
                                            newEpisodesSince(pod, visitsByFeed[entry.feedUrl])
                                        }
                                        // Group banner: explicit override wins, then
                                        // first child's custom artwork, then first
                                        // child's feed artwork. Three-step fall-through
                                        // covers groups where the cluster has its own
                                        // brand image AND groups where it doesn't.
                                        val groupArt = catalogItem.groupArtworkUrl
                                            ?: loadedChildren.first().let { (entry, pod) ->
                                                entry.customArtworkUrl ?: pod.artworkUrl
                                            }
                                        item(key = catalogItem.groupId) {
                                            GroupRow(
                                                group = catalogItem,
                                                leadArtworkUrl = groupArt,
                                                feedCount = loadedChildren.size,
                                                totalEpisodes = totalEps,
                                                newEpisodeCount = newSum,
                                                expanded = expanded,
                                                onToggle = {
                                                    expandedGroups[catalogItem.groupId] = !expanded
                                                }
                                            )
                                        }
                                        if (expanded) {
                                            items(
                                                items = loadedChildren,
                                                key = { (entry, _) ->
                                                    "${catalogItem.groupId}:${entry.feedUrl}"
                                                }
                                            ) { (entry, pod) ->
                                                val newCount = newEpisodesSince(
                                                    pod, visitsByFeed[entry.feedUrl]
                                                )
                                                PodcastRow(
                                                    pod = pod,
                                                    artworkUrl = entry.customArtworkUrl ?: pod.artworkUrl,
                                                    newEpisodeCount = newCount,
                                                    onClick = { onPodcastClick(pod) },
                                                    titleOverride = entry.displayName,
                                                    indent = true
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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

/**
 * Top-level catalog card for a single feed. Reused for [SourceGroup] children
 * with [indent] true (start padding marks them as nested) and [titleOverride]
 * set (since group children carry short labels like "Topical Studies" that
 * differ from the feed's own <title>).
 *
 * [artworkUrl] is decoupled from [pod] so callers can route through the
 * [SourceEntry.customArtworkUrl] override when a feed's own art is broken
 * or missing. Pass `pod.artworkUrl` to keep the original behavior.
 */
@Composable
private fun PodcastRow(
    pod: Podcast,
    artworkUrl: String?,
    newEpisodeCount: Int,
    onClick: () -> Unit,
    titleOverride: String? = null,
    indent: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemedArtwork(artworkUrl = artworkUrl, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    titleOverride ?: pod.title,
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

/**
 * Distinguished catalog row for a Kabod Pack — RSS-shaped pointers to
 * *completed* bodies of work (verse-by-verse expositions, lecture series, etc).
 *
 * Visual differentiation from the regular [PodcastRow]:
 *  - A primary-tinted border. The card sits on `surface` (not `surfaceVariant`)
 *    so the border reads cleanly across all themes — these aren't "just another
 *    podcast" cards.
 *  - A small `MenuBook` glyph badged onto the lower-right of the artwork.
 *    Reads as "open book / scripture exposition" without spelling out a
 *    denomination or imposing a logo.
 *  - The Hebrew word כָּבוֹד ("kabod" — weight, glory, presence) in a chip
 *    floating in the upper-right corner. The word names the format and
 *    carries the gravitas the pastor's labor deserves.
 *  - "{author} · {N} entries" subtitle (vs RSS's "{N} episodes") since these
 *    feeds are archived and the speaker identity is a primary identifier.
 */
@Composable
private fun KabodPackRow(
    pod: Podcast,
    artworkUrl: String?,
    newEpisodeCount: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.5.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        ),
    ) {
        Box {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork + book-glyph badge anchored to the lower-right
                // corner. Surface gives the badge a circular plate so the icon
                // reads cleanly against any artwork.
                Box {
                    ThemedArtwork(artworkUrl = artworkUrl, size = 64.dp)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(24.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                // 56dp end-padding reserves room for the kabod chip in the
                // upper-right so a long title doesn't wrap behind it.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 56.dp)
                ) {
                    Text(
                        pod.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2
                    )
                    val subtitle = pod.author?.let { "$it  ·  ${pod.episodes.size} entries" }
                        ?: "${pod.episodes.size} entries"
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (newEpisodeCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        NewEpisodesBadge(count = newEpisodeCount)
                    }
                }
            }
            // Hebrew kabod chip (כָּבוֹד), upper-right. System fonts on
            // Android pick up Noto Sans Hebrew automatically; no font asset
            // needed. Bold + slightly larger than labelMedium so the
            // characters read clearly at chip size.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    // Unpointed spelling — cleaner at chip size and what the
                    // word looks like in everyday written Hebrew. The pointed
                    // form (כָּבוֹד) carries the same meaning but the niqqud
                    // dots clash with a small chip's vertical metrics.
                    text = "כבוד",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Card-stack header for a [SourceGroup]. Uses the first child's artwork as a
 * stand-in (cheap and recognizable; a bespoke stacked-thumbnail visual is a
 * later iteration). Tapping toggles inline expansion of the children below.
 */
@Composable
private fun GroupRow(
    group: SourceGroup,
    leadArtworkUrl: String?,
    feedCount: Int,
    totalEpisodes: Int,
    newEpisodeCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "group-chevron"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemedArtwork(artworkUrl = leadArtworkUrl, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.groupName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )
                Text(
                    if (totalEpisodes > 0) "$feedCount feeds  ·  $totalEpisodes episodes"
                    else "$feedCount feeds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (newEpisodeCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    NewEpisodesBadge(count = newEpisodeCount)
                }
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}
