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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.data.LofiDownload
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
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
    onPlay: (Episode, Podcast) -> Unit,
    /**
     * Tapping the episode title opens the Player in preview mode — same
     * screen, all the same elements, but the episode isn't playing yet.
     * Hitting Play in the preview promotes it to live. Tapping anywhere
     * else on the row keeps the in-card description-expansion behavior.
     */
    onPreview: (Episode) -> Unit,
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    // Resolve the podcast from the in-memory cache, falling back to a disk
    // hydration (cold start where this screen is reached before the Catalog
    // populated the cache — deep links, process-death restore) and, for
    // kabod:// feeds, to the bundled-asset loader (kabod packs aren't part
    // of the feed disk cache; they re-parse from assets in a few ms).
    // Previously this was a plain `remember { app.repo.cached(feedUrl) }`,
    // which rendered a permanent "Feed not loaded." on those paths.
    var podState by remember(feedUrl) { mutableStateOf(app.repo.cached(feedUrl)) }
    LaunchedEffect(feedUrl) {
        if (podState == null) {
            app.repo.hydrateFromDisk()
            podState = app.repo.cached(feedUrl)
                ?: app.kabodLoader.takeIf { it.isKabodFeed(feedUrl) }
                    ?.loadIntoCache(feedUrl)
        }
    }
    // Plain local val so the existing smart-cast-dependent code below keeps
    // compiling (delegated state properties can't be smart cast). Refreshes
    // on every recomposition, including the one triggered by the load above.
    val pod = podState
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by controller.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings = remember { com.lofipod.app.data.Settings(app) }
    val showPlayedInList by settings.showPlayedInList.collectAsState(initial = true)
    val autoArchiveDays by settings.autoArchiveDays.collectAsState(initial = 3)
    val autoplayDirectionUp by settings.autoplayDirectionUp.collectAsState(initial = true)
    val sortOrders by settings.episodeSortOrders.collectAsState(initial = emptyMap())
    val sortOrder = sortOrders[feedUrl] ?: com.lofipod.app.data.Settings.EPISODE_SORT_FEED

    val episodeStates = remember { mutableStateMapOf<String, EpisodeUiState>() }
    var showArchived by remember { mutableStateOf(false) }

    // Kabod pack metadata per guid — "Part N · <scripture>" line shown on
    // each episode row, so the sermon-series structure that previously only
    // surfaced in the Player's Details tab is visible while browsing the
    // pack. Empty for regular RSS feeds (single indexed query, kabod only).
    var kabodLineByGuid by remember(feedUrl) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(feedUrl) {
        if (!feedUrl.startsWith("kabod://")) return@LaunchedEffect
        val packId = feedUrl.removePrefix("kabod://")
        val rows = withContext(Dispatchers.IO) {
            app.db.episodeKabodDao().getForPack(packId)
        }
        kabodLineByGuid = rows.mapNotNull { row ->
            val parts = listOfNotNull(
                row.partNumber?.let { "Part $it" },
                row.scripture?.takeIf { it.isNotBlank() },
            )
            if (parts.isEmpty()) null else row.guid to parts.joinToString(" · ")
        }.toMap()
    }

    // Bulk-selection mode (v0.10.15+). Long-press any episode row to
    // enter; selection set tracks chosen guids. Top bar transforms while
    // non-empty (count + Done + bulk actions). Tap in selection mode
    // toggles the tapped row's membership rather than opening the row.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val inSelectionMode = selection.isNotEmpty()

    // System back gesture exits selection mode rather than navigating
    // away. The user's mental model after entering selection mode is
    // "I'm in a sub-mode of this screen" — popping the screen would
    // discard their selection silently.
    BackHandler(enabled = inSelectionMode) {
        selection = emptySet()
    }

    // Sweep + load. Auto-archive runs every time the screen is entered (cheap —
    // single indexed query). Then we hydrate per-episode state into the map.
    // [autoArchiveDays] in the key list re-runs the sweep if the user changes
    // the horizon while staying on the screen.
    LaunchedEffect(pod, autoArchiveDays) {
        if (pod == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dao = app.db.episodeStateDao()
            // 0 days = "off" — skip the sweep entirely. Otherwise the cutoff
            // is now minus N days (matched to lastPlayedMillis).
            if (autoArchiveDays > 0) {
                val cutoff = System.currentTimeMillis() -
                    autoArchiveDays.toLong() * 24L * 3600L * 1000L
                val eligible = dao.guidsEligibleForAutoArchive(feedUrl, cutoff)
                if (eligible.isNotEmpty()) {
                    dao.bulkArchive(eligible, System.currentTimeMillis())
                    // Archived episodes don't need their downloads taking up disk.
                    val haveDownloads = app.downloadsApi.byId.value.keys
                    for (g in eligible) {
                        if (g in haveDownloads) app.downloadsApi.remove(g)
                    }
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
            // Mark the feed as visited — clears the new-episodes badge on Catalog.
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
    val filteredEpisodes = (pod?.episodes ?: emptyList()).filter { ep ->
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
    // Sort AFTER filtering, per the feed's persisted preference. Feed
    // order (the default) is the RSS document's own sequence — most
    // shows publish newest-first, but series feeds and kabod packs run
    // in reading/part order, which is why this is a per-feed choice.
    // Undated episodes sink to the bottom under either explicit sort
    // (sortedBy is stable, so they keep their feed order among
    // themselves). Display-only: the autoplay feed-walk works over the
    // feed's own order + the direction setting, never this list.
    val visibleEpisodes = when (sortOrder) {
        com.lofipod.app.data.Settings.EPISODE_SORT_NEWEST ->
            filteredEpisodes.sortedByDescending { it.pubDateMillis ?: Long.MIN_VALUE }
        com.lofipod.app.data.Settings.EPISODE_SORT_OLDEST ->
            filteredEpisodes.sortedBy { it.pubDateMillis ?: Long.MAX_VALUE }
        else -> filteredEpisodes
    }

    // Scroll-to-now-playing on entry. One-shot per screen instance: when the
    // visible list first contains the current episode (paused or playing),
    // animate to its position. Subsequent scrolls aren't auto-driven — once
    // the user has been pointed at the right row, manual scrolling owns the
    // viewport. Keys on size + currentEpisodeGuid because the filtered list's
    // identity changes every recomposition; size is a stable proxy for "list
    // contents settled."
    val listState = rememberLazyListState()
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(visibleEpisodes.size, playerState.currentEpisodeGuid) {
        if (didInitialScroll) return@LaunchedEffect
        val guid = playerState.currentEpisodeGuid ?: return@LaunchedEffect
        if (visibleEpisodes.isEmpty()) return@LaunchedEffect
        val idx = visibleEpisodes.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            didInitialScroll = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Top bar morphs in selection mode (v0.10.15+):
            //   normal       — [back] Podcast title | [Archive-visibility-toggle]
            //   selection-on — [X close] "N selected" | [Archive] [Download] [Mark played]
            // Speed icon was removed in v0.10.15 per user direction. The
            // per-podcast default-speed override still applies if previously
            // set (lives in podcast_state); no in-app UI to edit it now.
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
                        // Bulk archive. Always archives (no toggle) — user
                        // can use the per-row Unarchive in More menu to
                        // reverse individual episodes.
                        IconButton(onClick = {
                            val targets = selection.toList()
                            selection = emptySet()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val dao = app.db.episodeStateDao()
                                    val now = System.currentTimeMillis()
                                    for (g in targets) {
                                        // Ensure row exists, then archive.
                                        val ep = pod?.episodes?.find { it.guid == g } ?: continue
                                        upsertState(app, ep, pod)
                                        dao.setArchivedAt(g, now)
                                    }
                                    // Archived episodes don't need their
                                    // downloads taking up disk.
                                    val haveDownloads = app.downloadsApi.byId.value.keys
                                    for (g in targets) {
                                        if (g in haveDownloads) app.downloadsApi.remove(g)
                                    }
                                }
                                // Update in-memory UI state so the rows
                                // reflect archived immediately.
                                val now = System.currentTimeMillis()
                                for (g in targets) {
                                    episodeStates[g] = (episodeStates[g] ?: EpisodeUiState())
                                        .copy(archivedAt = now)
                                }
                                snackbarHostState.showSnackbar("Archived ${targets.size}")
                            }
                        }) {
                            Icon(
                                painterResource(R.drawable.archive_24),
                                contentDescription = "Archive selected",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        // Bulk download. Starts downloads for any selected
                        // episodes that don't already have one in flight /
                        // completed. Manual user-driven, so we use the
                        // inline start path (no autoplay delayed-fire).
                        IconButton(onClick = {
                            val targets = selection.toList()
                            selection = emptySet()
                            scope.launch {
                                var started = 0
                                val byId = app.downloadsApi.byId.value
                                for (g in targets) {
                                    val ep = pod?.episodes?.find { it.guid == g } ?: continue
                                    val existing = byId[g]
                                    if (existing != null &&
                                        existing.state != LofiDownload.State.FAILED
                                    ) {
                                        // QUEUED / DOWNLOADING / COMPLETED — skip.
                                        continue
                                    }
                                    withContext(Dispatchers.IO) {
                                        upsertState(app, ep, pod)
                                        // Bulk-download is explicit user
                                        // intent; clear any auto-download
                                        // flag so the finished-TTL sweep
                                        // doesn't auto-remove it later.
                                        app.db.autoDownloadDao().delete(g)
                                    }
                                    app.downloadsApi.start(ep)
                                    started++
                                }
                                snackbarHostState.showSnackbar(
                                    if (started == targets.size) "Downloading ${targets.size}"
                                    else "Downloading $started of ${targets.size} (rest already in flight)"
                                )
                            }
                        }) {
                            Icon(
                                painterResource(R.drawable.download_24),
                                contentDescription = "Download selected",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        // Bulk mark-played. Pins position to duration so the
                        // isPlayed check (positionMs >= durationMs - 5_000)
                        // returns true and the row fades / line-throughs.
                        IconButton(onClick = {
                            val targets = selection.toList()
                            selection = emptySet()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val dao = app.db.episodeStateDao()
                                    val now = System.currentTimeMillis()
                                    for (g in targets) {
                                        val ep = pod?.episodes?.find { it.guid == g } ?: continue
                                        upsertState(app, ep, pod)
                                        val s = episodeStates[g]
                                        val dur = (s?.durationMs?.takeIf { it > 0 })
                                            ?: ep.durationSeconds?.let { it * 1000L }
                                            ?: 1L
                                        dao.updatePosition(
                                            guid = g,
                                            pos = dur,
                                            dur = dur,
                                            now = now,
                                            listenDelta = 0L,
                                        )
                                    }
                                }
                                for (g in targets) {
                                    val s = episodeStates[g] ?: EpisodeUiState()
                                    val ep = pod?.episodes?.find { it.guid == g }
                                    val dur = (s.durationMs.takeIf { it > 0 })
                                        ?: ep?.durationSeconds?.let { it * 1000L }
                                        ?: 1L
                                    episodeStates[g] = s.copy(positionMs = dur, durationMs = dur)
                                }
                                snackbarHostState.showSnackbar("Marked ${targets.size} played")
                            }
                        }) {
                            Icon(
                                painterResource(R.drawable.check_circle_24),
                                contentDescription = "Mark selected played",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        // Overflow: the less-frequent bulk actions live in a
                        // labeled menu — five bare icons would crowd a 360dp
                        // top bar, and "mark unplayed" needs words to not be
                        // mistaken for a rewind. Box wrapper anchors the menu
                        // to this icon (matches the app's other overflows).
                        var bulkMenuOpen by remember { mutableStateOf(false) }
                        Box {
                        IconButton(onClick = { bulkMenuOpen = true }) {
                            Icon(
                                painterResource(R.drawable.more_vert_24),
                                contentDescription = "More bulk actions",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = bulkMenuOpen,
                            onDismissRequest = { bulkMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mark as unplayed") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.replay_24), contentDescription = null)
                                },
                                onClick = {
                                    bulkMenuOpen = false
                                    val targets = selection.toList()
                                    selection = emptySet()
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            val dao = app.db.episodeStateDao()
                                            val now = System.currentTimeMillis()
                                            for (g in targets) {
                                                val ep = pod?.episodes?.find { it.guid == g } ?: continue
                                                upsertState(app, ep, pod)
                                                // Position back to zero; PRESERVE the
                                                // known duration (updatePosition writes
                                                // durationMs unconditionally, and the DB
                                                // often knows a duration the UI-state
                                                // map doesn't — zeroing it would break
                                                // isPlayed/progress/auto-archive for
                                                // this episode permanently).
                                                val dbDur = dao.get(g)?.durationMs?.takeIf { it > 0 }
                                                val dur = dbDur
                                                    ?: episodeStates[g]?.durationMs?.takeIf { it > 0 }
                                                    ?: ep.durationSeconds?.let { it * 1000L }
                                                    ?: 0L
                                                dao.updatePosition(
                                                    guid = g,
                                                    pos = 0L,
                                                    dur = dur,
                                                    now = now,
                                                    listenDelta = 0L,
                                                )
                                            }
                                        }
                                        for (g in targets) {
                                            val s = episodeStates[g] ?: EpisodeUiState()
                                            episodeStates[g] = s.copy(positionMs = 0L)
                                        }
                                        snackbarHostState.showSnackbar("Marked ${targets.size} unplayed")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add to queue") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.playlist_add_24), contentDescription = null)
                                },
                                onClick = {
                                    bulkMenuOpen = false
                                    val targets = selection.toList()
                                    selection = emptySet()
                                    scope.launch {
                                        val p = pod ?: return@launch
                                        var added = 0
                                        val queued = queueSet
                                        for (g in targets) {
                                            if (g in queued) continue
                                            val ep = p.episodes.find { it.guid == g } ?: continue
                                            controller.enqueue(
                                                ep,
                                                podcastTitle = p.title,
                                                podcastArt = p.artworkUrl,
                                            )
                                            added++
                                        }
                                        snackbarHostState.showSnackbar(
                                            if (added == targets.size) "Queued $added"
                                            else "Queued $added of ${targets.size} (rest already queued)"
                                        )
                                    }
                                },
                            )
                        }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(pod?.title ?: "Loading…", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.arrow_back_24),
                                contentDescription = "Back",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    actions = {
                        // Sort order. Menu rather than a blind cycle-toggle so
                        // the three states are visible and labeled; the icon
                        // tints primary while a non-default sort is active,
                        // matching the archive toggle's active treatment.
                        Box {
                            var sortMenuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    painterResource(R.drawable.swap_vert_24),
                                    contentDescription = "Sort episodes",
                                    tint = if (sortOrder != com.lofipod.app.data.Settings.EPISODE_SORT_FEED)
                                        MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                // "Feed order" label deliberately vague about
                                // direction — it IS whatever the publisher
                                // chose (part order for kabod packs).
                                val options = listOf(
                                    com.lofipod.app.data.Settings.EPISODE_SORT_FEED to "Feed order",
                                    com.lofipod.app.data.Settings.EPISODE_SORT_NEWEST to "Newest first",
                                    com.lofipod.app.data.Settings.EPISODE_SORT_OLDEST to "Oldest first",
                                )
                                for ((value, label) in options) {
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = if (sortOrder == value) {
                                            {
                                                Icon(
                                                    painterResource(R.drawable.check_24),
                                                    contentDescription = "Active",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            sortMenuOpen = false
                                            scope.launch {
                                                settings.setEpisodeSortOrder(feedUrl, value)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // Archive visibility toggle. The icon flips between open and
                        // closed-archive boxes so the active state reads at a glance.
                        IconButton(onClick = { showArchived = !showArchived }) {
                            Icon(
                                if (showArchived) painterResource(R.drawable.unarchive_24) else painterResource(R.drawable.archive_24),
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
        }
    ) { padding ->
        if (pod == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading feed…")
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleEpisodes, key = { it.guid }) { ep ->
                val s = episodeStates[ep.guid] ?: EpisodeUiState()
                val isCurrent = playerState.currentEpisodeGuid == ep.guid
                val isSelected = ep.guid in selection
                EpisodeRow(
                    ep = ep,
                    podcastArt = pod.artworkUrl,
                    kabodLine = kabodLineByGuid[ep.guid],
                    state = s,
                    isQueued = ep.guid in queueSet,
                    download = downloadsByGuid[ep.guid],
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && playerState.isPlaying,
                    autoplayDirectionUp = autoplayDirectionUp,
                    inSelectionMode = inSelectionMode,
                    isSelected = isSelected,
                    onLongPress = {
                        // Toggle this guid in the selection set. Entering
                        // selection mode if previously empty; staying in
                        // it otherwise.
                        selection = if (ep.guid in selection) selection - ep.guid
                                    else selection + ep.guid
                    },
                    onPlay = {
                        if (inSelectionMode) {
                            selection = if (ep.guid in selection) selection - ep.guid
                                        else selection + ep.guid
                        } else if (isCurrent) controller.togglePlay()
                        else onPlay(ep, pod)
                    },
                    onPreviewTitle = {
                        // In selection mode, a title tap toggles selection
                        // rather than opening the preview — consistent
                        // with the rest of the row's tap targets so the
                        // user doesn't escape selection mode by accident.
                        if (inSelectionMode) {
                            selection = if (ep.guid in selection) selection - ep.guid
                                        else selection + ep.guid
                        } else onPreview(ep)
                    },
                    onShare = { ctx.shareEnclosure(ep.audioUrl, ep.title) },
                    onToggleAutoplayDirection = {
                        val nextUp = !autoplayDirectionUp
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                settings.setAutoplayDirectionUp(nextUp)
                            }
                            // Snackbar dismisses on its own after ~3 seconds
                            // (Material Short duration). Sticking to a single
                            // line so it doesn't crowd out the cards behind.
                            // Date-based wording (not "up/down the list"):
                            // the sort control can flip the list's visual
                            // direction, but the walk is always by date.
                            snackbarHostState.showSnackbar(
                                if (nextUp) "Autoplay: toward newer episodes"
                                else "Autoplay: toward older episodes",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    onCycleHeart = {
                        val next = (s.favoriteTier + 1) % 3
                        episodeStates[ep.guid] = s.copy(favoriteTier = next)
                        scope.launch {
                            upsertState(app, ep, pod, newTier = next)
                            // Promotions surface in the notes system as a
                            // canned auto-note (no-op on the clear-to-0 leg).
                            // Position: live player position if this episode
                            // is loaded, else its persisted resume position.
                            val posMs = if (playerState.currentEpisodeGuid == ep.guid)
                                controller.currentPositionMs() else s.positionMs
                            com.lofipod.app.data.recordPromotionNote(
                                app.db, ep.guid, next, posMs
                            )
                            // Promotion to tier 2 drops a checkpoint into the
                            // global history so the moment of anointment is
                            // recoverable later. Snackbar gives the user a
                            // beat of feedback that something else happened
                            // beyond just the heart filling in.
                            if (next == 2) {
                                controller.recordMostExcellentPromotion(ep.guid)
                                snackbarHostState.showSnackbar("Promoted to most-excellent")
                            }
                        }
                    },
                    onToggleDownload = {
                        val d = downloadsByGuid[ep.guid]
                        if (d == null || d.state == LofiDownload.State.FAILED) {
                            scope.launch {
                                upsertState(app, ep, pod)
                                app.downloadsApi.start(ep)
                                // Manual trigger — clear any prior auto-download
                                // flag so the 1-hour-after-finished sweep won't
                                // expire this user-requested download.
                                withContext(Dispatchers.IO) {
                                    app.db.autoDownloadDao().delete(ep.guid)
                                }
                                snackbarHostState.showSnackbar("Download started")
                            }
                        } else {
                            // remove() inside Downloads also clears the auto_download
                            // row, so no extra cleanup needed here.
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
                    },
                    onMarkPlayed = {
                        // Mark-as-played: pin position to the duration so the
                        // isPlayed check (positionMs >= durationMs - 5_000)
                        // returns true. Stamps lastPlayedMillis so the
                        // auto-archive sweep can pick this row up.
                        val dur = s.durationMs.takeIf { it > 0 }
                            ?: ep.durationSeconds?.let { it * 1000L }
                            ?: 0L
                        val newDur = if (dur > 0) dur else 1L
                        episodeStates[ep.guid] = s.copy(positionMs = newDur, durationMs = newDur)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                upsertState(app, ep, pod)
                                app.db.episodeStateDao().updatePosition(
                                    guid = ep.guid,
                                    pos = newDur,
                                    dur = newDur,
                                    now = System.currentTimeMillis(),
                                    listenDelta = 0L
                                )
                            }
                            snackbarHostState.showSnackbar("Marked as played")
                        }
                    },
                    onMarkUnplayed = {
                        episodeStates[ep.guid] = s.copy(positionMs = 0L)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                upsertState(app, ep, pod)
                                app.db.episodeStateDao().updatePosition(
                                    guid = ep.guid,
                                    pos = 0L,
                                    dur = s.durationMs,
                                    now = System.currentTimeMillis(),
                                    listenDelta = 0L
                                )
                            }
                            snackbarHostState.showSnackbar("Marked as unplayed")
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
        // Quick-scroll: draggable thumb on the right edge for long archives.
        // The bubble shows the publish month (MM/yy) of the episode under
        // the thumb; kabod parts without a pubDate fall back to "n / total".
        val bubbleDateFmt = remember { SimpleDateFormat("MM/yy", Locale.getDefault()) }
        FastScroller(
            listState = listState,
            itemCount = visibleEpisodes.size,
            bubbleText = { idx ->
                visibleEpisodes.getOrNull(idx)?.let { ep ->
                    ep.pubDateMillis?.let { bubbleDateFmt.format(Date(it)) }
                        ?: "${idx + 1} / ${visibleEpisodes.size}"
                }
            },
        )
        }
    }

}

// DefaultSpeedDialog + speed-icon entry point removed in v0.10.15 per
// user direction. The per-podcast default-speed override still applies
// if previously set (stored in podcast_state.defaultSpeed; respected
// by PlayerController.playEpisode); there's just no in-app UI to edit
// it now. Restore from git history if a new entry point is desired.

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    ep: Episode,
    podcastArt: String?,
    /** "Part N · <scripture>" meta line for Kabod-pack episodes; null for
     *  regular RSS episodes (and kabod rows without part/scripture tags). */
    kabodLine: String?,
    state: EpisodeUiState,
    isQueued: Boolean,
    download: LofiDownload?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    autoplayDirectionUp: Boolean,
    /** v0.10.15+: true when ANY row on the screen is selected.
     *  Drives the card's interaction model (taps toggle selection rather
     *  than expand) and a subtle background tint on selected rows. */
    inSelectionMode: Boolean,
    /** v0.10.15+: true when this specific row is part of the current
     *  selection. Tints the card with primaryContainer + shows a check
     *  glyph in front of the title so the user can see at a glance
     *  which rows are queued for a bulk action. */
    isSelected: Boolean,
    /** v0.10.15+: long-press anywhere on the card toggles this row's
     *  membership in the selection set. Used both to enter selection
     *  mode (when nothing was selected) and to add/remove individual
     *  rows once selection mode is active. */
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
    onPreviewTitle: () -> Unit,
    onShare: () -> Unit,
    onCycleHeart: () -> Unit,
    onToggleDownload: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleArchive: () -> Unit,
    onToggleAutoplayDirection: () -> Unit,
    onMarkPlayed: () -> Unit,
    onMarkUnplayed: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val isArchived = state.archivedAt > 0
    val isPlayed = state.isPlayed

    // Played-but-not-active rows fade to a softer surface and dim the text so
    // the user's eye skips them when scanning. The "playing now" tint always
    // wins over the played gray-out so the current row stays prominent.
    // Selected rows (bulk-select mode, v0.10.15+) take a tertiaryContainer
    // tint so they're visually distinct from both the live "now playing"
    // primaryContainer and the regular surfaceVariant of unselected rows.
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.tertiaryContainer
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isPlayed || isArchived -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textAlpha = if (!isCurrent && !isSelected && (isPlayed || isArchived)) 0.55f else 1f
    val titleDecoration = if (isPlayed && !isCurrent && !isSelected) TextDecoration.LineThrough
                          else TextDecoration.None

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    // In selection mode the card tap toggles selection
                    // (delegated to onLongPress's same effect for
                    // consistency — we re-use that callback rather than
                    // routing a second one through every call site).
                    // Otherwise it expands the description as before.
                    if (inSelectionMode) onLongPress() else expanded = !expanded
                },
                onLongClick = onLongPress,
            )
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
                        when {
                            // Selection glyph wins over both the "now playing"
                            // and "played" indicators when the row is part of
                            // the active bulk-selection set (v0.10.15+).
                            isSelected -> {
                                Icon(
                                    painterResource(R.drawable.check_circle_24),
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            isCurrent -> {
                                Icon(
                                    painterResource(R.drawable.graphic_eq_24),
                                    contentDescription = "Now playing",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            isPlayed -> {
                                Icon(
                                    painterResource(R.drawable.check_circle_24),
                                    contentDescription = "Played",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Text(
                            ep.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            // Title is its own click target — tap navigates
                            // to the Player in preview mode (or toggles
                            // selection if selection mode is active; the
                            // routing is in the call site's onPreviewTitle
                            // lambda, not here). The card's surrounding
                            // combinedClickable still handles expand /
                            // collapse + long-press for everything else
                            // (chevron, meta line, description).
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onPreviewTitle),
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
                    kabodLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                                .copy(alpha = textAlpha),
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    if (expanded) painterResource(R.drawable.expand_less_24) else painterResource(R.drawable.expand_more_24),
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
                        if (isPlaying) painterResource(R.drawable.pause_24) else painterResource(R.drawable.play_arrow_24),
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
                        if (isQueued) painterResource(R.drawable.playlist_add_check_24) else painterResource(R.drawable.playlist_add_24),
                        contentDescription = if (isQueued) "Remove from queue" else "Add to queue",
                        tint = if (isQueued) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                // Autoplay-direction indicator. Tap to toggle the global
                // setting. The icon flips with the direction so a glance
                // tells the user which way the chain will walk after the
                // current episode finishes — only matters when the queue
                // is empty AND the per-feed auto-advance toggle is on.
                IconButton(onClick = onToggleAutoplayDirection) {
                    Icon(
                        if (autoplayDirectionUp) painterResource(R.drawable.arrow_upward_24)
                        else painterResource(R.drawable.arrow_downward_24),
                        contentDescription = if (autoplayDirectionUp)
                            "Autoplay walks toward newer episodes (tap to flip)"
                        else "Autoplay walks toward older episodes (tap to flip)",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Per-row overflow. Houses the lower-frequency actions:
                // share, archive (was previously inline — moved here to
                // give the autoplay-direction toggle visible-row real
                // estate), and mark-played/unplayed. Heart stays inline
                // since it's the highest-frequency curation action.
                Box {
                    var moreOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(
                            painterResource(R.drawable.more_vert_24),
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = moreOpen,
                        onDismissRequest = { moreOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isArchived) "Unarchive" else "Archive") },
                            onClick = {
                                moreOpen = false
                                onToggleArchive()
                            },
                            leadingIcon = {
                                Icon(
                                    if (isArchived) painterResource(R.drawable.unarchive_24)
                                    else painterResource(R.drawable.archive_24),
                                    null,
                                    tint = if (isArchived) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share raw link") },
                            onClick = {
                                moreOpen = false
                                onShare()
                            },
                            leadingIcon = { Icon(painterResource(R.drawable.share_24), null) }
                        )
                        if (isPlayed) {
                            DropdownMenuItem(
                                text = { Text("Mark as unplayed") },
                                onClick = {
                                    moreOpen = false
                                    onMarkUnplayed()
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.replay_24), null) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Mark as played") },
                                onClick = {
                                    moreOpen = false
                                    onMarkPlayed()
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.check_circle_24), null) }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                HeartTierButton(tier = state.favoriteTier, onCycle = onCycleHeart)
            }
            // Bandwidth + disk readout. Single number — streaming and
            // downloading both pull the full enclosure file, so one
            // figure covers both questions ("how much data will this
            // cost?" and "how much disk?"). Pinned to the bottom-right
            // corner of the card so it reads as a passive "vital stat"
            // rather than competing with the action buttons above.
            //
            // v0.10.8+: when the RSS feed doesn't populate `length`
            // (Megaphone-hosted shows like The Pour Over, some Castos
            // shows, etc.), kick off an HTTP HEAD/Range probe to the
            // audio URL and pick up the size once it lands. Results
            // cache to disk so future launches render instantly.
            val app = LocalContext.current.applicationContext as LofiPodApp
            val probedSizes by app.episodeSizes.sizes.collectAsState()
            val probedSize = probedSizes[ep.audioUrl]
            val resolvedSize = ep.audioByteSize ?: probedSize
            LaunchedEffect(ep.audioUrl, ep.audioByteSize) {
                if (ep.audioByteSize == null) {
                    app.episodeSizes.probe(ep.audioUrl)
                }
            }
            resolvedSize?.let { bytes ->
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        formatMb(bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = textAlpha),
                    )
                }
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
                painterResource(R.drawable.archive_24),
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

// DownloadButton moved to DownloadUi.kt for reuse on the Player screen.

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
                if (tier > 0) painterResource(R.drawable.favorite_24) else painterResource(R.drawable.favorite_border_24),
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
                    painterResource(R.drawable.favorite_24),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Best-effort plain-text view of an HTML-ish description. */
/** Format byte count as a compact "Nmb" string for the episode-row meta
 *  line. Sub-megabyte sizes show as "<1mb" rather than rounding to "0mb"
 *  (which would mis-imply zero bytes). Whole megabytes only — fractional
 *  precision isn't useful for a quick budget read. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576L  // 1024 * 1024
    return if (mb == 0L) "<1mb" else "${mb}mb"
}

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
