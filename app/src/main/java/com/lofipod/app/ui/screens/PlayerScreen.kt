package com.lofipod.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Settings
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import com.lofipod.app.ui.theme.ThemedArtwork
import com.lofipod.app.util.shareEnclosure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshot of an episode loaded for preview-mode rendering. Carries the bits
 * the screen needs to display without an active playback session.
 */
private data class PreviewData(
    val episode: Episode,
    val podcast: Podcast?,
    val savedPositionMs: Long,
    val savedDurationMs: Long,
)

/**
 * Resolve [guid] into a PreviewData, or null if we can't find the episode in
 * any cached feed (and there's no fallback row in episode_state). Searches
 * `repo.allCached()` because a never-played episode has no episode_state row,
 * so we can't go from guid → feedUrl that way.
 */
private suspend fun resolvePreviewData(app: LofiPodApp, guid: String): PreviewData? =
    withContext(Dispatchers.IO) {
        val state = app.db.episodeStateDao().get(guid)
        // Look across every cached feed — we don't know feedUrl up-front for
        // an unplayed episode. ~10 feeds × 100 episodes is trivial.
        var foundPod: Podcast? = null
        var foundEp: Episode? = null
        for (pod in app.repo.allCached()) {
            val ep = pod.episodes.find { it.guid == guid }
            if (ep != null) { foundPod = pod; foundEp = ep; break }
        }
        when {
            foundEp != null -> PreviewData(
                episode = foundEp,
                podcast = foundPod,
                savedPositionMs = state?.positionMs ?: 0L,
                savedDurationMs = state?.durationMs
                    ?: ((foundEp.durationSeconds ?: 0L) * 1000L),
            )
            // No cached feed has it — last-ditch reconstruct from episode_state
            // alone. Won't have description but title/artwork survive.
            state != null -> PreviewData(
                episode = Episode(
                    guid = state.guid,
                    feedUrl = state.feedUrl,
                    title = state.title,
                    description = null,
                    pubDateMillis = null,
                    audioUrl = state.audioUrl,
                    audioMimeType = null,
                    durationSeconds = null,
                    episodeArtworkUrl = state.artworkUrl,
                ),
                podcast = null,
                savedPositionMs = state.positionMs,
                savedDurationMs = state.durationMs,
            )
            else -> null
        }
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMyLists: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * One-tap jump to the catalog (root destination). Distinct from
     * `onBack` which walks the back stack one step at a time via
     * smartBack; this lets a user on Player → Episodes → Catalog get
     * home in one tap instead of two. v0.10.9+.
     */
    onOpenCatalog: () -> Unit,
    /**
     * When non-null AND it doesn't match the currently-playing episode, the
     * screen renders in "preview mode": all the same UI elements, but the
     * episode isn't playing. Hitting Play starts playback of the preview
     * episode and the screen seamlessly flips to live mode (because
     * `state.currentEpisodeGuid` then matches `previewGuid`).
     *
     * If `previewGuid` matches what's already playing, preview mode does NOT
     * activate — we just show the live state. Same screen, no surprise.
     */
    previewGuid: String? = null,
    /** Open the full-page Transcript route for the given guid. */
    onOpenTranscript: (String) -> Unit = {},
    /**
     * Notifies the host (MainActivity / AppNav) when the bottom-tabs region
     * enters or exits full-screen mode. The host uses this to keep the
     * mini-player anchored to the bottom of the screen during full-screen
     * — without it, full-screen mode hides every transport control along
     * with the player chrome, which is fine for reading-mode Notes but
     * leaves the user stranded if they want to pause / skip while watching
     * Diagnostics scroll by. Default no-op so existing call sites don't
     * have to change.
     */
    onPlayerTabsFullscreenChange: (Boolean) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val pendingReturn by controller.pendingReturn.collectAsState()
    val autoplayTimer by controller.autoplayTimer.collectAsState()
    val sleepTimerState by controller.sleepTimer.collectAsState()
    var sleepDialogOpen by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var scrubberPauses by remember {
        mutableStateOf<List<com.lofipod.app.audio.PauseTapProcessor.PauseSpan>>(emptyList())
    }

    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    // Pause-skip ("back to previous audible pause") sensitivity dialog,
    // opened by long-pressing the transport control. Settings collection
    // lives inside the dialog block so the screen root isn't subscribed
    // to a DataStore flow it only needs while the dialog is up.
    var pauseSkipDialogOpen by remember { mutableStateOf(false) }
    // Per-episode download state for the inline DownloadButton. Recomposes
    // automatically as downloads progress / change state.
    val downloadsByGuid by app.downloadsApi.byId.collectAsState()

    // Audio file size for the displayed episode — drives the single-figure
    // size readout below the playback bar. For preview, comes from the
    // already-loaded Episode in [previewData]. For live, looked up via
    // episode_state -> repo cache -> Episode. Refreshed when [displayedGuid]
    // changes (track transition / live-vs-preview swap).
    var episodeSizeBytes by remember { mutableStateOf<Long?>(null) }
    // v0.10.8+: track the audio URL alongside the RSS-supplied size, so
    // when the feed reports `length="0"` (Megaphone-hosted shows) we can
    // kick off the size prober and pick up the actual size once it lands.
    var episodeAudioUrl by remember { mutableStateOf<String?>(null) }

    // Resolve preview data once per [previewGuid]. Loaded async because we
    // hit the DB; stays null until ready (we render a brief loading state
    // for that window — usually invisible because the cache lookup is fast).
    var previewData by remember(previewGuid) { mutableStateOf<PreviewData?>(null) }
    LaunchedEffect(previewGuid) {
        if (previewGuid == null) { previewData = null; return@LaunchedEffect }
        previewData = resolvePreviewData(app, previewGuid)
    }

    // Preview is only active when the requested guid is set AND it isn't
    // already what's playing. A user previewing the live episode just sees
    // live state — no point in a static preview of a moving target.
    val isPreview = previewGuid != null && previewGuid != state.currentEpisodeGuid

    /** The guid the screen is rendering data for. Drives heart, tabs, etc. */
    val displayedGuid: String? = if (isPreview) previewGuid else state.currentEpisodeGuid

    // Refresh size readout when the displayed guid changes. For preview,
    // pulled directly from the Episode object once it's loaded. For live,
    // looked up via episode_state -> repo cache to find the parsed Episode
    // (single DB read + one in-memory list scan, runs off the IO dispatcher).
    LaunchedEffect(displayedGuid, isPreview, previewData) {
        when {
            displayedGuid == null -> {
                episodeSizeBytes = null
                episodeAudioUrl = null
            }
            isPreview -> {
                episodeSizeBytes = previewData?.episode?.audioByteSize
                episodeAudioUrl = previewData?.episode?.audioUrl
            }
            else -> withContext(Dispatchers.IO) {
                val state = app.db.episodeStateDao().get(displayedGuid)
                val pod = state?.let { app.repo.cached(it.feedUrl) }
                val ep = pod?.episodes?.find { it.guid == displayedGuid }
                episodeSizeBytes = ep?.audioByteSize
                episodeAudioUrl = ep?.audioUrl
            }
        }
    }
    // Kick off a size probe when the RSS feed didn't supply a length.
    // Idempotent — re-firing for the same URL is a no-op inside the prober.
    LaunchedEffect(episodeAudioUrl, episodeSizeBytes) {
        if (episodeSizeBytes == null) {
            episodeAudioUrl?.let { app.episodeSizes.probe(it) }
        }
    }
    // Observe the prober's size cache. resolvedSizeBytes is the RSS value
    // when present, else whatever the probe came back with (which may be
    // null while still in-flight or if the host didn't return a size).
    val probedSizes by app.episodeSizes.sizes.collectAsState()
    val resolvedSizeBytes = episodeSizeBytes
        ?: episodeAudioUrl?.let { probedSizes[it] }

    // Live favorite tier for whichever episode is being displayed (live or
    // preview). Observed so the top-bar heart stays in sync if the user
    // changes the tier elsewhere while this screen is open.
    val currentTier by remember(displayedGuid) {
        val g = displayedGuid
        if (g == null) kotlinx.coroutines.flow.flowOf(0)
        else app.db.episodeStateDao().observe(g)
            .map { it?.favoriteTier ?: 0 }
    }.collectAsState(initial = 0)

    LaunchedEffect(state.isPlaying, isPreview) {
        // In preview mode there's no live playback to poll — surface the
        // saved position / duration once and stop.
        if (isPreview) {
            positionMs = previewData?.savedPositionMs ?: 0L
            durationMs = previewData?.savedDurationMs ?: 0L
            return@LaunchedEffect
        }
        while (true) {
            positionMs = controller.currentPositionMs()
            durationMs = controller.durationMs()
            // Scrubber pause ticks ride the same 500ms cadence — a cheap
            // synchronized copy of the tap's ring, never per-frame.
            scrubberPauses = com.lofipod.app.player.PlaybackService
                .sharedPauseTap.snapshotPauses()
            delay(500)
        }
    }

    // Structured-scrubber data layers (live playback only).
    // Heat: observe the episode's bucket row — Room re-emits on each 10s
    // ticker upsert so the just-listened region brightens live.
    val heatBuckets by remember(state.currentEpisodeGuid) {
        val g = state.currentEpisodeGuid
        if (g == null) kotlinx.coroutines.flow.flowOf<IntArray?>(null)
        else app.db.episodeHeatDao().observe(g)
            .map { row -> row?.let { com.lofipod.app.data.EpisodeHeatRecorder.decode(it.bucketsCsv) } }
    }.collectAsState(initial = null)
    // Scripture markers: one-shot per episode, cached-transcript-only (no
    // network — transcripts arrive when the user opens the Transcript tab).
    var scrubberMarkers by remember(state.currentEpisodeGuid) {
        mutableStateOf<List<ScrubberMarker>>(emptyList())
    }
    LaunchedEffect(state.currentEpisodeGuid) {
        val g = state.currentEpisodeGuid ?: return@LaunchedEffect
        scrubberMarkers = withContext(Dispatchers.IO) { buildScriptureMarkers(app, g) }
    }

    // Re-snapshot the saved position into the displayed values whenever
    // previewData hydrates after the LaunchedEffect already initialised.
    LaunchedEffect(previewData, isPreview) {
        if (isPreview && previewData != null) {
            positionMs = previewData!!.savedPositionMs
            durationMs = previewData!!.savedDurationMs
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Bottom-tabs fullscreen toggle. Tapping the currently-selected tab
    // expands the tabs container to take the whole screen below the top
    // bar, hiding the artwork / title / scrubber / controls block above
    // it. Tap the active tab again to collapse back. Per-screen state
    // (resets when the user navigates away and back).
    var tabsFullscreen by remember { mutableStateOf(false) }

    // Mirror the fullscreen flag up to the host (MainActivity / AppNav) so
    // it can flip the mini-player from hidden (default on player route) to
    // visible during full-screen — necessary so the user keeps transport
    // controls when the full-screen tab hides the player chrome above. Also
    // resets to false on dispose so a back-navigation while still in
    // fullscreen mode doesn't leave the host with a stale flag.
    LaunchedEffect(tabsFullscreen) {
        onPlayerTabsFullscreenChange(tabsFullscreen)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onPlayerTabsFullscreenChange(false) }
    }

    // Diagnostics-tab toggle from Settings. When false (default) the player
    // tab strip is Notes / Details / Transcript only. When true, a 4th
    // "Diagnostics" tab joins for in-player live audio-chain health.
    val showDiagnosticsTab by remember(app) {
        com.lofipod.app.data.Settings(app).showDiagnosticsTabInPlayer
    }.collectAsState(initial = false)

    // Manual flush button (flush-valve icon). v0.10.1+; off by default. When
    // enabled, sits to the right of the Speed chip with the chip itself
    // staying horizontally centered.
    val showFlushButton by remember(app) {
        com.lofipod.app.data.Settings(app).showFlushButtonInPlayer
    }.collectAsState(initial = false)

    // Surface controller-side transient messages as snackbars so play-button
    // taps that hit a no-op state (player not bound, no media loaded,
    // already buffering) get explicit user feedback rather than feeling
    // dead. SharedFlow with replay=0 means leftover messages from a prior
    // composition don't replay on screen revisit.
    LaunchedEffect(controller) {
        controller.transientMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // Title bar shifts based on mode:
                    //  - Live (something playing): GraphicEq glyph (same icon
                    //    used to mark the active episode in the per-podcast
                    //    list — visual continuity).
                    //  - Preview (looking at a different episode): "Preview"
                    //    text so the user knows audio isn't running here.
                    //  - Idle (nothing loaded): "Now playing" placeholder.
                    when {
                        isPreview -> Text("Preview")
                        state.currentEpisodeGuid != null -> Icon(
                            painterResource(R.drawable.graphic_eq_24),
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        else -> Text("Now playing")
                    }
                },
                navigationIcon = {
                    // v0.10.15+: Home is the far-left affordance now.
                    // Tap → Catalog. The "back to episodes" behaviour is
                    // owned by the system back gesture (wired in
                    // MainActivity's BackHandler to feedAwareBackFromPlayer
                    // for the player route), so the down-chevron that used
                    // to sit here is gone. One-tap-to-Catalog was the
                    // common ask; for back-to-episodes use the system
                    // back gesture / button.
                    IconButton(onClick = onOpenCatalog) {
                        Icon(
                            painterResource(R.drawable.home_24),
                            contentDescription = "Catalog",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    // Heart-tier toggle for the displayed episode (live OR
                    // preview). Single tap cycles 0 -> 1 (Excellent) -> 2
                    // (Most-excellent) -> 0, mirroring the per-row heart in
                    // the episode list. Hitting tier 2 also drops a "promoted
                    // to most-excellent" checkpoint so the moment of
                    // anointment is recoverable from the global Playback
                    // History. In preview mode, ensures an episode_state row
                    // exists before flipping the tier (the UPDATE query in
                    // setFavoriteTier is a no-op without one).
                    displayedGuid?.let { guid ->
                        IconButton(onClick = {
                            val next = (currentTier + 1) % 3
                            scope.launch {
                                val existing = withContext(Dispatchers.IO) {
                                    val dao = app.db.episodeStateDao()
                                    val row = dao.get(guid)
                                    if (row == null) {
                                        // Synthesise a row so the tier sticks. Pull
                                        // identity from previewData if we have it.
                                        previewData?.episode?.let { ep ->
                                            dao.upsert(
                                                EpisodeStateEntity(
                                                    guid = ep.guid,
                                                    feedUrl = ep.feedUrl,
                                                    title = ep.title,
                                                    audioUrl = ep.audioUrl,
                                                    artworkUrl = ep.episodeArtworkUrl
                                                        ?: previewData?.podcast?.artworkUrl,
                                                    favoriteTier = next,
                                                )
                                            )
                                        }
                                    } else {
                                        dao.setFavoriteTier(guid, next)
                                    }
                                    row
                                }
                                // Promotions surface in the notes system too —
                                // canned auto-note (no-op on the clear-to-0 leg)
                                // stamped at the live playback position if this
                                // episode is loaded, else its saved resume
                                // position (matches the episode-list heart).
                                val posMs = if (state.currentEpisodeGuid == guid)
                                    controller.currentPositionMs()
                                else existing?.positionMs ?: 0L
                                com.lofipod.app.data.recordPromotionNote(
                                    app.db, guid, next, posMs
                                )
                                if (next == 2) {
                                    controller.recordMostExcellentPromotion(guid)
                                    snackbarHostState.showSnackbar("Promoted to most-excellent")
                                }
                            }
                        }) {
                            PlayerHeartIcon(tier = currentTier)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Mark this moment (v0.11): one tap drops a timestamped
                    // note with a canned body — no keyboard, designed for
                    // driving/walking. Expand it later from the Notes tab
                    // (it's an ordinary note: jumpable, editable, shareable).
                    // Live playback only — a moment needs a playhead.
                    if (!isPreview && state.currentEpisodeGuid != null) {
                        IconButton(onClick = {
                            val guid = state.currentEpisodeGuid ?: return@IconButton
                            val posMs = controller.currentPositionMs()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    app.db.episodeNoteEntryDao().upsert(
                                        EpisodeNoteEntryEntity(
                                            guid = guid,
                                            createdAt = System.currentTimeMillis(),
                                            playbackPosMs = posMs,
                                            text = "Marked while listening",
                                        )
                                    )
                                }
                                snackbarHostState.showSnackbar(
                                    "Moment marked at ${formatTime(posMs)}"
                                )
                            }
                        }) {
                            Icon(
                                painterResource(R.drawable.note_add_24),
                                contentDescription = "Mark this moment",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Home moved to navigationIcon slot (far left) in
                    // v0.10.15 — see the navigationIcon block above. The
                    // actions row now starts directly with My lists.
                    IconButton(onClick = onOpenMyLists) {
                        Icon(
                            painterResource(R.drawable.format_list_bulleted_24),
                            contentDescription = "My lists",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenEq) {
                        // Tune (slider knobs) for Audio Fine-tuning, distinct
                        // from GraphicEq (used as the live "now playing" glyph
                        // in the top-bar title above + Catalog overflow).
                        // Keeps the two affordances visually separable.
                        Icon(
                            painterResource(R.drawable.tune_24),
                            contentDescription = "Audio Fine-tuning",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Overflow on the far right — Notes is gone from the top
                    // bar entirely (the in-Player Notes tab covers it), and
                    // History + Settings remain inside the menu.
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                painterResource(R.drawable.more_vert_24),
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
                                text = { Text("Playback history") },
                                onClick = { menuExpanded = false; onOpenHistory() },
                                leadingIcon = { Icon(painterResource(R.drawable.history_24), null) }
                            )
                            // Share lives in the overflow (under "Playback
                            // history" per the player's existing ordering):
                            // resolves the audio URL via the cached feed for
                            // the current episode, then hands off to the
                            // standard enclosure share intent. Disabled if no
                            // episode is loaded.
                            val shareGuid = state.currentEpisodeGuid
                            DropdownMenuItem(
                                text = { Text("Share") },
                                enabled = shareGuid != null,
                                onClick = {
                                    menuExpanded = false
                                    if (shareGuid == null) return@DropdownMenuItem
                                    val pod = app.repo.allCached().firstOrNull { p ->
                                        p.episodes.any { it.guid == shareGuid }
                                    }
                                    val ep = pod?.episodes?.firstOrNull { it.guid == shareGuid }
                                    if (ep != null) {
                                        ctx.shareEnclosure(ep.audioUrl, ep.title)
                                    }
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.share_24), null) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (sleepTimerState != null) "Sleep timer (on)"
                                        else "Sleep timer"
                                    )
                                },
                                enabled = !isPreview,
                                onClick = { menuExpanded = false; sleepDialogOpen = true },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.hourglass_empty_24), null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuExpanded = false; onOpenSettings() },
                                leadingIcon = { Icon(painterResource(R.drawable.settings_24), null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Top: artwork + title + scrubber + transport (compact, wraps content).
            // Hidden entirely when tabsFullscreen is on so the bottom tabs
            // get the whole screen real estate.
            if (!tabsFullscreen) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pending-return chip is a live-mode affordance only — it
                // exists to bring the user back to where they were *before
                // jumping* during playback. Hidden in preview.
                if (!isPreview) {
                    pendingReturn?.takeIf { it.guid == state.currentEpisodeGuid }?.let { pr ->
                        // Once the user has organically listened past the return
                        // point, the "go back" affordance no longer makes sense.
                        // Flip the chip to a "Listened" confirmation, hold it for
                        // 5 seconds so the user notices, then auto-dismiss.
                        val reached = positionMs >= pr.positionMs
                        if (reached) {
                            LaunchedEffect(pr.createdAt) {
                                delay(5_000)
                                controller.dismissPendingReturn()
                            }
                        }
                        ReturnChip(
                            label = if (reached) "Listened to ${formatTime(pr.positionMs)}"
                                    else "Return to ${formatTime(pr.positionMs)}",
                            reached = reached,
                            onJump = { if (!reached) controller.consumePendingReturn() },
                            onDismiss = { controller.dismissPendingReturn() }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // Display data flips based on mode. In preview mode the source
                // is the resolved PreviewData; in live mode it's the player's
                // state flow. Same UI surface, different feed.
                val displayedArtwork: String? = if (isPreview)
                    previewData?.episode?.episodeArtworkUrl
                        ?: previewData?.podcast?.artworkUrl
                else state.currentArtworkUri
                val displayedTitle: String = if (isPreview)
                    previewData?.episode?.title ?: "Loading…"
                else state.currentTitle ?: "Nothing playing"
                val displayedArtist: String = if (isPreview)
                    previewData?.podcast?.title ?: ""
                else state.currentArtist ?: ""

                // Bigger artwork with a thin-but-bold ink stroke between the
                // image and the screen edge (the chunky border is part of the
                // theme language — same as cassette/reel/ticker placeholder
                // surfaces). Uses outline at 0.7-alpha so it reads on every theme.
                ThemedArtwork(
                    artworkUrl = displayedArtwork,
                    size = 260.dp,
                    modifier = Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    displayedTitle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2
                )
                // Artist line + per-episode download status. The download
                // button mirrors the EpisodesScreen affordance (download,
                // cancel, delete, retry) and surfaces the same Media3
                // Download object — auto-download in playEpisode means a
                // fresh live play almost always lands here on QUEUED →
                // DOWNLOADING → COMPLETED, while preview lets the user kick
                // it off explicitly before committing to playing.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayedArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    // Device files (guid == their content:// URI) are already
                    // local — the downloader ignores them by design, so the
                    // button would be a dead control. Hide it instead.
                    if (displayedGuid != null && !displayedGuid.startsWith("content://")) {
                        val download = downloadsByGuid[displayedGuid]
                        DownloadButton(
                            download = download,
                            onClick = {
                                val d = downloadsByGuid[displayedGuid]
                                if (d == null || d.state == com.lofipod.app.data.LofiDownload.State.FAILED) {
                                    // Start. Preview has full Episode in
                                    // hand; live looks up via episode_state.
                                    // Both paths are MANUAL — user pressed
                                    // the download button — so any prior
                                    // auto-download flag must be cleared
                                    // (live path's controller.startDownloadForCurrent
                                    // already handles its own cleanup; preview
                                    // path needs an inline clear).
                                    if (isPreview) {
                                        previewData?.episode?.let { ep ->
                                            app.downloadsApi.start(ep)
                                            scope.launch(Dispatchers.IO) {
                                                app.db.autoDownloadDao().delete(ep.guid)
                                            }
                                        }
                                    } else {
                                        controller.startDownloadForCurrent(displayedGuid)
                                    }
                                } else {
                                    // remove() inside Downloads clears the auto_download
                                    // row internally, so no extra cleanup needed.
                                    app.downloadsApi.remove(displayedGuid)
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (isPreview) {
                    // Preview keeps the plain read-only Slider — no live
                    // pause data, no heat accruing, nothing to structure.
                    // The saved position from episode_state is context only.
                    val frac = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                    Slider(
                        value = frac.coerceIn(0f, 1f),
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    StructuredScrubber(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        heatBuckets = heatBuckets,
                        pauses = scrubberPauses,
                        markers = scrubberMarkers,
                        onSeek = { controller.seekTo(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(durationMs), style = MaterialTheme.typography.bodyLarge)
                }
                // Single-figure size readout, bottom-right of the playback
                // bar. Streaming and downloading both pull the full
                // enclosure file so one number covers both questions
                // ("how much data?" / "how much disk?"). Hidden when the
                // feed didn't publish an enclosure length.
                resolvedSizeBytes?.let { bytes ->
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatMb(bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // SpaceEvenly + fillMaxWidth instead of fixed spacers: four
                // fixed-size controls total 268dp, which fits a 320dp-wide
                // window (272dp usable inside the 24dp side padding) only if
                // the gaps flex. Fixed 8-16dp spacers overflowed and clipped
                // the outer buttons on compact/split-screen widths.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Skip-back/forward only make sense for live playback.
                    // Drop them in preview so the transport row collapses to
                    // a single big Play button.
                    if (!isPreview) {
                        // Pause-skip: tap = seek back to the previous audible
                        // pause; long-press = sensitivity dialog. Box +
                        // combinedClickable because IconButton has no
                        // long-press slot.
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .combinedClickable(
                                    onClick = { controller.skipBackToPreviousPause() },
                                    onLongClick = { pauseSkipDialogOpen = true },
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.skip_previous_24),
                                contentDescription = "Back to previous pause (hold to adjust sensitivity)",
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        IconButton(
                            onClick = { controller.seekBack() },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.replay_24),
                                contentDescription = "Back 15s",
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                    val countdown = rememberAutoplayCountdown(
                        if (isPreview) null else autoplayTimer
                    )
                    Box(contentAlignment = Alignment.Center) {
                        FilledIconButton(
                            onClick = {
                                if (isPreview) {
                                    // Promote preview → live. Same call path the
                                    // EpisodeRow's Play button uses, so checkpoint
                                    // recording, queue interactions, etc. all
                                    // behave identically.
                                    previewData?.let { pd ->
                                        controller.playEpisode(
                                            ep = pd.episode,
                                            podcastTitle = pd.podcast?.title ?: "",
                                            podcastArt = pd.podcast?.artworkUrl,
                                        )
                                    }
                                } else {
                                    // togglePlay short-circuits to
                                    // confirmAutoplayContinuation when the timer
                                    // is active and the player is playing — so a
                                    // tap on the countdown morph above naturally
                                    // confirms continuation here.
                                    controller.togglePlay()
                                }
                            },
                            modifier = Modifier.size(88.dp),
                            enabled = !isPreview || previewData != null,
                        ) {
                            if (countdown != null) {
                                // Hide the regular icon while the morph overlays
                                // the button — the FilledIconButton's tonal
                                // background still sits behind the ring + text.
                                Spacer(Modifier.size(48.dp))
                            } else {
                                Icon(
                                    if (state.isPlaying && !isPreview) painterResource(R.drawable.pause_24)
                                    else painterResource(R.drawable.play_arrow_24),
                                    contentDescription = if (isPreview) "Play" else "Play/Pause",
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                        when {
                            // Autoplay-confirmation morph: drainable progress
                            // ring + digital "M:SS" replaces the Pause icon
                            // from T=60s onward (rememberAutoplayCountdown
                            // returns null before the first beep).
                            countdown != null -> AutoplayCountdownContent(
                                info = countdown,
                                ringSize = 88.dp,
                            )
                            // Buffering ring — drawn on top of the play button
                            // when the player is in BUFFERING with
                            // playWhenReady=true OR when the stall watchdog
                            // has flagged a renderer underrun. Tells the user
                            // "I heard your tap / I see this is stuck, I'm
                            // working on it" rather than letting them wonder
                            // if the button is dead. Suppressed during the
                            // countdown so the two indicators don't stack.
                            (state.isBuffering || state.isStalled) && !isPreview -> CircularProgressIndicator(
                                modifier = Modifier.size(88.dp),
                                strokeWidth = 3.dp,
                            )
                        }
                    }
                    if (!isPreview) {
                        IconButton(
                            onClick = { controller.seekForward() },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.forward_30_24),
                                contentDescription = "Forward 30s",
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }
                // Error chip if the player just failed (live only). The
                // buffering signal lives on the play button itself (the
                // CircularProgressIndicator ring around it) — a separate text
                // line below was bouncing the speed chip and tabs every time
                // a rewind transiently hit STATE_BUFFERING.
                if (!isPreview && state.errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {
                            // Retry via a fresh setMediaItem cycle through
                            // playEpisode — stronger than togglePlay's plain
                            // prepare()+play() because any in-memory player
                            // state that was confused by the original
                            // failure gets reset alongside a fresh
                            // MediaItem. If the source URL itself is bad,
                            // retry will re-fail with the same chip — that's
                            // correct, it tells the user the source is the
                            // problem, not a transient hiccup.
                            controller.retryCurrentEpisode()
                        },
                        label = { Text("Failed: ${state.errorMessage} — tap to retry") },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.error_outline_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                // Speed chip is a live-playback tweak — the per-podcast
                // default-speed picker (top of the per-podcast Episodes list)
                // is the equivalent affordance for preview mode.
                //
                // The Box keeps SpeedChip centered horizontally; the
                // optional flush-valve icon (v0.10.1+) sits aligned to
                // CenterEnd without disturbing the chip's center position.
                if (!isPreview) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SpeedChip(
                            speed = state.speed,
                            onPick = { controller.setSpeed(it) }
                        )
                        // Active sleep-timer readout. Recomposition rides
                        // the 500ms position poll while playing (the only
                        // time a timer can be live — manual pause cancels
                        // it), so the countdown stays fresh without its
                        // own ticker. Tap to adjust/turn off.
                        sleepTimerState?.let { st ->
                            val label = when (st.mode) {
                                is com.lofipod.app.player.SleepTimerMode.Minutes -> {
                                    val rem = ((st.endAtElapsedMs ?: 0L) -
                                        android.os.SystemClock.elapsedRealtime())
                                        .coerceAtLeast(0L)
                                    "Sleep %d:%02d".format(rem / 60_000, (rem % 60_000) / 1000)
                                }
                                com.lofipod.app.player.SleepTimerMode.EndOfEpisode ->
                                    "Sleep: end of episode"
                            }
                            AssistChip(
                                onClick = { sleepDialogOpen = true },
                                label = {
                                    Text(
                                        label,
                                        color = if (st.fading) MaterialTheme.colorScheme.primary
                                        else LocalContentColor.current,
                                    )
                                },
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                        if (showFlushButton) {
                            IconButton(
                                onClick = { controller.flushAudio() },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.valve_24),
                                    contentDescription = "Flush audio buffer",
                                )
                            }
                        }
                    }
                }
            }

            // Bottom: tabbed container that fills the remaining space.
            // Tabs key off the displayed guid (preview or live) so the Notes
            // and Details tab content matches whatever's on screen above.
            BottomTabs(
                episodeGuid = displayedGuid,
                controller = controller,
                isPreview = isPreview,
                onOpenTranscript = onOpenTranscript,
                fullscreen = tabsFullscreen,
                onToggleFullscreen = { tabsFullscreen = !tabsFullscreen },
                showDiagnosticsTab = showDiagnosticsTab,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    if (sleepDialogOpen) {
        AlertDialog(
            onDismissRequest = { sleepDialogOpen = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text(
                        "Playback fades out over the last 30 seconds, then " +
                            "pauses. Resume is always full volume.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(15, 30, 45, 60).forEach { min ->
                        TextButton(onClick = {
                            controller.startSleepTimer(
                                com.lofipod.app.player.SleepTimerMode.Minutes(min)
                            )
                            sleepDialogOpen = false
                        }) { Text("$min minutes") }
                    }
                    TextButton(onClick = {
                        controller.startSleepTimer(
                            com.lofipod.app.player.SleepTimerMode.EndOfEpisode
                        )
                        sleepDialogOpen = false
                    }) { Text("End of episode") }
                    if (sleepTimerState != null) {
                        TextButton(onClick = {
                            controller.cancelSleepTimer()
                            sleepDialogOpen = false
                        }) { Text("Turn off") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sleepDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (pauseSkipDialogOpen) {
        val pauseSkipSettings = remember { Settings(app) }
        val pauseSkipSensitivity by pauseSkipSettings.pauseSkipSensitivity
            .collectAsState(
                initial = com.lofipod.app.audio.PauseTapProcessor.DEFAULT_SENSITIVITY
            )
        AlertDialog(
            onDismissRequest = { pauseSkipDialogOpen = false },
            title = { Text("Pause-skip sensitivity") },
            text = {
                Column {
                    Text(
                        "How small a gap in the audio counts as a pause when " +
                            "skipping back. Higher catches short breaths between " +
                            "sentences; lower only stops at real breaks.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = pauseSkipSensitivity.toFloat(),
                        onValueChange = { v ->
                            val level = v.toInt().coerceIn(1, 5)
                            if (level != pauseSkipSensitivity) {
                                // Write-through: live processor immediately,
                                // DataStore for persistence (same pattern as
                                // the EQ screen's skip-silence level).
                                com.lofipod.app.player.PlaybackService
                                    .sharedPauseTap.setSensitivity(level)
                                scope.launch {
                                    pauseSkipSettings.setPauseSkipSensitivity(level)
                                }
                            }
                        },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                    // Label pulls the min-silence value from the same table
                    // the detector runs on, so re-tuning the processor can't
                    // leave the UI advertising stale thresholds.
                    val gapSec = com.lofipod.app.audio.PauseTapProcessor
                        .minSilenceMsFor(pauseSkipSensitivity) / 1000f
                    Text(
                        String.format(Locale.getDefault(), "%d — gaps of ~%.1fs and longer", pauseSkipSensitivity, gapSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pauseSkipDialogOpen = false }) { Text("Done") }
            },
        )
    }
}

/**
 * Top-bar heart icon for the now-playing episode. Tier 0 = outline; tier 1 =
 * filled; tier 2 = filled with a small second pip overlapping (matches the
 * inline HeartTierButton used in the episode list so the visual language is
 * consistent across screens).
 */
@Composable
private fun PlayerHeartIcon(tier: Int) {
    val tint = if (tier > 0) MaterialTheme.colorScheme.primary
               else LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (tier > 0) painterResource(R.drawable.favorite_24) else painterResource(R.drawable.favorite_border_24),
            contentDescription = when (tier) {
                2 -> "Most-excellent (tap to clear)"
                1 -> "Excellent (tap to upgrade)"
                else -> "Mark as Excellent"
            },
            tint = tint,
            modifier = Modifier.size(26.dp)
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

/**
 * Compact speed picker chip. Tapping the chip opens a dropdown of the most
 * common speeds. Active speed is highlighted; "1.0x" is always present so the
 * reset case is one tap. The full 0.5..3.0 continuous slider still lives in EQ
 * for users who want unusual speeds.
 */
@Composable
private fun SpeedChip(
    speed: Float,
    onPick: (Float) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val choices = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Speed: ${"%.2fx".format(speed)}") },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.speed_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            choices.forEach { v ->
                val active = kotlin.math.abs(v - speed) < 0.01f
                DropdownMenuItem(
                    text = {
                        Text(
                            "%.2fx".format(v),
                            color = if (active) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current
                        )
                    },
                    onClick = {
                        onPick(v)
                        open = false
                    },
                    leadingIcon = if (active) {
                        { Icon(painterResource(R.drawable.check_24), contentDescription = null) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun ReturnChip(
    label: String,
    reached: Boolean,
    onJump: () -> Unit,
    onDismiss: () -> Unit
) {
    AssistChip(
        onClick = onJump,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (reached) painterResource(R.drawable.check_24) else painterResource(R.drawable.undo_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painterResource(R.drawable.close_24),
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

/**
 * Bottom tabs container. New tabs slot in here by adding to the [tabs] list and
 * [tabIndex] match — keep the tab labels short so they fit in the strip.
 */
@Composable
private fun BottomTabs(
    episodeGuid: String?,
    controller: PlayerController,
    isPreview: Boolean,
    onOpenTranscript: (String) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    showDiagnosticsTab: Boolean,
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableStateOf(0) }

    // Transcript tab visibility. Driven by whether kabod metadata for this
    // episode has a non-blank transcriptUrl. Most podcasts won't have a
    // transcript URL bundled, so by default the tab is hidden — surface
    // it only when there's actually something to show.
    val app = LocalContext.current.applicationContext as LofiPodApp
    var hasTranscript by remember(episodeGuid) { mutableStateOf(false) }
    LaunchedEffect(episodeGuid) {
        if (episodeGuid == null) {
            hasTranscript = false
            return@LaunchedEffect
        }
        val url = withContext(Dispatchers.IO) {
            app.db.episodeKabodDao().get(episodeGuid)?.transcriptUrl
        }
        hasTranscript = !url.isNullOrBlank()
    }

    // Build the tabs list dynamically. Transcript only when the episode
    // has one; Diagnostics only when the user has opted in via Settings.
    // remember key includes both flags so the list rebuilds when either
    // flips. tabIndex bounds-check after the rebuild keeps the strip from
    // rendering against a stale index when a tab disappears.
    val tabs = remember(hasTranscript, showDiagnosticsTab) {
        buildList {
            add("Notes")
            add("Details")
            if (hasTranscript) add("Transcript")
            if (showDiagnosticsTab) add("Diagnostics")
        }
    }
    if (tabIndex >= tabs.size) tabIndex = 0

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tabIndex == i,
                    onClick = {
                        // Tap the active tab → toggle fullscreen.
                        // Tap a different tab → switch (keep fullscreen
                        // state as it is). Lets the user switch between
                        // tabs while staying in fullscreen reading mode.
                        if (tabIndex == i) onToggleFullscreen()
                        else tabIndex = i
                    },
                    text = { Text(label) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tabs[tabIndex]) {
                "Notes" -> NotesTab(
                    episodeGuid = episodeGuid,
                    controller = controller,
                    isPreview = isPreview,
                )
                "Details" -> DetailsTab(episodeGuid = episodeGuid, controller = controller)
                "Transcript" -> TranscriptContent(
                    episodeGuid = episodeGuid,
                    showFullPageButton = true,
                    onOpenFullPage = { episodeGuid?.let(onOpenTranscript) },
                )
                "Diagnostics" -> PlayerDiagnosticsTab(
                    episodeGuid = episodeGuid,
                    controller = controller,
                    isFullScreen = fullscreen,
                    // The chip-style "Full-screen" button in the
                    // Diagnostics tab is the third path into / out of
                    // fullscreen mode (the others being a tap on the
                    // active tab in the strip and back-navigation off
                    // the player). Reuses the existing toggle.
                    onToggleFullScreen = onToggleFullscreen,
                )
            }
        }
    }
}

@Composable
private fun NotesTab(
    episodeGuid: String?,
    controller: PlayerController,
    isPreview: Boolean,
) {
    if (episodeGuid == null) {
        EmptyTab(if (isPreview) "Loading episode…" else "Nothing playing.")
        return
    }
    val app = LocalContext.current.applicationContext as LofiPodApp
    val noteShareCtx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)
    val entries by app.db.episodeNoteEntryDao()
        .observeForEpisode(episodeGuid)
        .collectAsState(initial = emptyList())

    var addOpen by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<EpisodeNoteEntryEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EpisodeNoteEntryEntity?>(null) }
    var resumeAfterDialog by remember { mutableStateOf(false) }
    var studySheetGuid by remember { mutableStateOf<String?>(null) }

    StudySheetDialogHost(
        episodeGuid = studySheetGuid,
        onDone = { studySheetGuid = null },
    )

    fun pauseIfWanted() {
        if (pauseOnNote && controller.state.value.isPlaying) {
            resumeAfterDialog = true
            controller.pause()
        }
    }

    fun openAdd() { pauseIfWanted(); addOpen = true }

    fun closeDialog() {
        addOpen = false
        editEntry = null
        if (resumeAfterDialog) {
            controller.play()
            resumeAfterDialog = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Switch-style "Pause while writing" toggle, mirroring the
            // "Disable EQ for this episode" pattern in the Details tab so the
            // controls feel like one family. Backed by settings.pauseOnNote.
            // Disabled in preview — there's nothing to pause.
            Switch(
                checked = pauseOnNote,
                onCheckedChange = { v -> scope.launch { settings.setPauseOnNote(v) } },
                enabled = !isPreview,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Pause",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPreview) MaterialTheme.colorScheme.onSurfaceVariant
                        else LocalContentColor.current,
                modifier = Modifier.weight(1f)
            )
            // Study sheet export — enabled once there are notes to export.
            IconButton(
                onClick = { studySheetGuid = episodeGuid },
                enabled = entries.isNotEmpty(),
            ) {
                Icon(
                    painterResource(R.drawable.share_24),
                    contentDescription = "Share study sheet",
                    modifier = Modifier.size(20.dp)
                )
            }
            // "Add note" needs a live playback position to anchor against.
            // In preview the button is disabled — past notes still surface
            // below and remain tappable to jump-and-play.
            FilledTonalButton(onClick = ::openAdd, enabled = !isPreview) {
                Icon(painterResource(R.drawable.add_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add note")
            }
        }
        Text(
            "${entries.size} note${if (entries.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No notes for this episode yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.createdAt }) { entry ->
                    NoteCard(
                        entry = entry,
                        onJump = { controller.jumpToNotePosition(entry) },
                        onEdit = { pauseIfWanted(); editEntry = entry },
                        onDelete = { deleteEntry = entry },
                        onShare = { scope.launch { shareNoteEntry(noteShareCtx, entry) } },
                    )
                }
            }
        }
    }

    if (addOpen) {
        val nowMs = remember { System.currentTimeMillis() }
        val posMs = remember { controller.currentPositionMs() }
        NoteEditorDialog(
            citation = citationOf(nowMs, posMs),
            initialText = "",
            confirmLabel = "Save",
            onDismiss = { closeDialog() },
            onConfirm = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.episodeNoteEntryDao().upsert(
                            EpisodeNoteEntryEntity(
                                guid = episodeGuid,
                                createdAt = nowMs,
                                playbackPosMs = posMs,
                                text = text
                            )
                        )
                    }
                }
                closeDialog()
            }
        )
    }

    editEntry?.let { entry ->
        NoteEditorDialog(
            citation = citationOf(entry.createdAt, entry.playbackPosMs),
            initialText = entry.text,
            confirmLabel = "Save",
            onDismiss = { closeDialog() },
            onConfirm = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.episodeNoteEntryDao().upsert(entry.copy(text = text))
                    }
                }
                closeDialog()
            }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Delete note?") },
            text = { Text(citationOf(entry.createdAt, entry.playbackPosMs)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            app.db.episodeNoteEntryDao().delete(entry.guid, entry.createdAt)
                        }
                    }
                    deleteEntry = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("Cancel") }
            }
        )
    }
}

// InlineNoteCard / InlineNoteDialog merged into the shared NoteCard /
// NoteEditorDialog in NoteUi.kt — used by both this Notes tab and the
// per-episode Notes screen so the play/edit/delete row stays in lockstep.

@Composable
private fun DetailsTab(episodeGuid: String?, controller: PlayerController) {
    if (episodeGuid == null) {
        EmptyTab("Nothing playing.")
        return
    }
    val app = LocalContext.current.applicationContext as LofiPodApp
    var details by remember(episodeGuid) { mutableStateOf<EpisodeDetails?>(null) }
    var kabodMeta by remember(episodeGuid) { mutableStateOf<com.lofipod.app.data.db.EpisodeKabodEntity?>(null) }

    LaunchedEffect(episodeGuid) {
        val state = withContext(Dispatchers.IO) { app.db.episodeStateDao().get(episodeGuid) }
        val pod = state?.let { app.repo.cached(it.feedUrl) }
        val ep = pod?.episodes?.find { it.guid == episodeGuid }
        kabodMeta = withContext(Dispatchers.IO) { app.db.episodeKabodDao().get(episodeGuid) }
        details = EpisodeDetails(
            episode = ep,
            podcastTitle = pod?.title,
            podcastAuthor = pod?.author,
            durationMs = state?.durationMs ?: 0L,
        )
    }

    val d = details
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        d.podcastTitle?.let {
            Text(it, style = MaterialTheme.typography.titleSmall)
        }
        d.podcastAuthor?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        val ep = d.episode
        if (ep != null) {
            Text(ep.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(metaLine(ep, d.durationMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Kabod metadata: scripture passage + part number, when present.
        // Surfaced here (rather than only in the Transcript tab) so users
        // browsing the Details tab see the sermon's scripture at a glance.
        kabodMeta?.let { km ->
            val parts = listOfNotNull(
                km.partNumber?.let { "Part $it" },
                km.scripture,
            )
            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Embedded ID3 chapters (v0.11), when the CURRENT item carries
        // them and this Details tab is showing that item. Tap to seek —
        // controller.seekTo is latency-compensated.
        val liveState by controller.state.collectAsState()
        if (liveState.currentEpisodeGuid == episodeGuid && liveState.chapters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Chapters", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            liveState.chapters.forEachIndexed { i, chp ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.seekTo(chp.startMs) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(chp.startMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        chp.title ?: "Chapter ${i + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                }
            }
        }

        // Per-episode EQ controls (Disable / one-off override) live on the
        // EQ screen now — that's where they belong alongside the chain. Tap
        // the EQ icon in the player's top bar to find them.

        Spacer(Modifier.height(12.dp))
        if (ep == null) {
            Text(
                // Device files have no feed — "refresh the feed" is a
                // nonsense instruction for them. guid == content:// URI
                // identifies them cheaply here.
                if (episodeGuid.startsWith("content://"))
                    "A file from this device — no feed metadata to show."
                else
                    "Episode metadata is not in the cache. Open this feed in Catalog to refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ep.description?.stripHtmlForDetails()?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            } ?: Text(
                "No description in the feed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class EpisodeDetails(
    val episode: Episode?,
    val podcastTitle: String?,
    val podcastAuthor: String?,
    val durationMs: Long,
)

private fun metaLine(ep: Episode, durationMs: Long): String = buildString {
    ep.pubDateMillis?.let {
        append(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it)))
    }
    val durSec = ep.durationSeconds ?: (durationMs / 1000).takeIf { it > 0 }
    if (durSec != null) {
        if (isNotEmpty()) append("  •  ")
        append("${durSec / 60} min")
    }
}

private fun String.stripHtmlForDetails(): String =
    this.replace(Regex("<[^>]*>"), "")
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("&#39;|&apos;"), "'")
        .replace(Regex("\\s+"), " ")
        .trim()

@Composable
private fun EmptyTab(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Same byte-size formatter the EpisodeRow uses — single number, whole
 *  megabytes, "<1mb" floor for tiny clips. Duplicated here rather than
 *  shared because the call sites are local to two screens; if a third
 *  screen needs it, extract to a util. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576L
    return if (mb == 0L) "<1mb" else "${mb}mb"
}
