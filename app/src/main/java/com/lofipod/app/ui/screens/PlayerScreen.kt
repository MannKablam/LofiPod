package com.lofipod.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.Settings
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.model.Episode
import com.lofipod.app.data.model.Podcast
import com.lofipod.app.player.PlayerController
import com.lofipod.app.ui.theme.ThemedArtwork
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMyLists: () -> Unit,
    onOpenSettings: () -> Unit,
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
) {
    val state by controller.state.collectAsState()
    val pendingReturn by controller.pendingReturn.collectAsState()
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    val app = LocalContext.current.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()

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
            delay(500)
        }
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
                            Icons.Filled.GraphicEq,
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        else -> Text("Now playing")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Back",
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
                                withContext(Dispatchers.IO) {
                                    val dao = app.db.episodeStateDao()
                                    if (dao.get(guid) == null) {
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
                                }
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
                    IconButton(onClick = onOpenMyLists) {
                        Icon(
                            Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = "My lists",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenEq) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "EQ",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Overflow on the far right — Notes is gone from the top
                    // bar entirely (the in-Player Notes tab covers it), and
                    // History + Settings remain inside the menu.
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
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
                                text = { Text("Playback history") },
                                onClick = { menuExpanded = false; onOpenHistory() },
                                leadingIcon = { Icon(Icons.Filled.History, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuExpanded = false; onOpenSettings() },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Top: artwork + title + scrubber + transport (compact, wraps content).
            Column(
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
                Text(
                    displayedArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                val frac = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                Slider(
                    value = frac.coerceIn(0f, 1f),
                    onValueChange = { v ->
                        // Preview slider is read-only — no seek target to apply
                        // to since nothing is playing. The saved position from
                        // episode_state is shown for context only.
                        if (!isPreview && durationMs > 0) {
                            controller.seekTo((v * durationMs).toLong())
                        }
                    },
                    enabled = !isPreview,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(durationMs), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Skip-back/forward only make sense for live playback.
                    // Drop them in preview so the transport row collapses to
                    // a single big Play button.
                    if (!isPreview) {
                        IconButton(
                            onClick = { controller.seekRelative(-15_000) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = "Back 15s",
                                modifier = Modifier.size(56.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                    }
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
                                    controller.togglePlay()
                                }
                            },
                            modifier = Modifier.size(88.dp),
                            enabled = !isPreview || previewData != null,
                        ) {
                            Icon(
                                if (state.isPlaying && !isPreview) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                contentDescription = if (isPreview) "Play" else "Play/Pause",
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        // Buffering ring — drawn on top of the play button when
                        // the player is in BUFFERING with playWhenReady=true. Tells
                        // the user "I heard your tap, I'm trying" rather than
                        // letting them wonder if the button is dead.
                        if (state.isBuffering && !isPreview) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(88.dp),
                                strokeWidth = 3.dp,
                            )
                        }
                    }
                    if (!isPreview) {
                        Spacer(Modifier.width(16.dp))
                        IconButton(
                            onClick = { controller.seekRelative(30_000) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                Icons.Filled.Forward30,
                                contentDescription = "Forward 30s",
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }
                // Status line: buffering text under the transport, OR error
                // chip if the player just failed. Both are live-only — preview
                // mode has nothing to buffer or fail at.
                if (!isPreview) {
                    if (state.errorMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = {
                                // Retry. togglePlay will re-prepare from IDLE
                                // or seek-to-0 from ENDED, so this works
                                // regardless of which terminal state the
                                // error left the player in.
                                controller.togglePlay()
                            },
                            label = { Text("Failed: ${state.errorMessage} — tap to retry") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    } else if (state.isBuffering) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Buffering…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Speed chip is a live-playback tweak — the per-podcast
                // default-speed picker (top of the per-podcast Episodes list)
                // is the equivalent affordance for preview mode.
                if (!isPreview) {
                    Spacer(Modifier.height(8.dp))
                    SpeedChip(
                        speed = state.speed,
                        onPick = { controller.setSpeed(it) }
                    )
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
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
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
            if (tier > 0) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
                Icons.Filled.Favorite,
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
                    Icons.Filled.Speed,
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
                        { Icon(Icons.Filled.Check, contentDescription = null) }
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
                if (reached) Icons.Filled.Check else Icons.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
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
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Notes", "Details", "Transcript")

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tabIndex == i,
                    onClick = { tabIndex = i },
                    text = { Text(label) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tabIndex) {
                0 -> NotesTab(
                    episodeGuid = episodeGuid,
                    controller = controller,
                    isPreview = isPreview,
                )
                1 -> DetailsTab(episodeGuid = episodeGuid, controller = controller)
                else -> TranscriptContent(
                    episodeGuid = episodeGuid,
                    showFullPageButton = true,
                    onOpenFullPage = { episodeGuid?.let(onOpenTranscript) },
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
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)
    val entries by app.db.episodeNoteEntryDao()
        .observeForEpisode(episodeGuid)
        .collectAsState(initial = emptyList())

    var addOpen by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<EpisodeNoteEntryEntity?>(null) }
    var resumeAfterDialog by remember { mutableStateOf(false) }

    fun openAdd() {
        if (pauseOnNote && controller.state.value.isPlaying) {
            resumeAfterDialog = true
            controller.pause()
        }
        addOpen = true
    }

    fun closeAdd() {
        addOpen = false
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
            // "Add note" needs a live playback position to anchor against.
            // In preview the button is disabled — past notes still surface
            // below and remain tappable to jump-and-play.
            FilledTonalButton(onClick = ::openAdd, enabled = !isPreview) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    InlineNoteCard(
                        entry = entry,
                        onJump = { controller.jumpToNotePosition(entry) },
                        onDelete = { deleteEntry = entry }
                    )
                }
            }
        }
    }

    if (addOpen) {
        val nowMs = remember { System.currentTimeMillis() }
        val posMs = remember { controller.currentPositionMs() }
        InlineNoteDialog(
            citation = citationOf(nowMs, posMs),
            onDismiss = { closeAdd() },
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
                closeAdd()
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

@Composable
private fun InlineNoteCard(
    entry: EpisodeNoteEntryEntity,
    onJump: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    citationOf(entry.createdAt, entry.playbackPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onJump, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "Jump",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InlineNoteDialog(
    citation: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(citation, style = MaterialTheme.typography.bodyMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("Your thoughts on this moment…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DetailsTab(episodeGuid: String?, controller: PlayerController) {
    if (episodeGuid == null) {
        EmptyTab("Nothing playing.")
        return
    }
    val app = LocalContext.current.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    var details by remember(episodeGuid) { mutableStateOf<EpisodeDetails?>(null) }
    var eqDisabled by remember(episodeGuid) { mutableStateOf(false) }
    var kabodMeta by remember(episodeGuid) { mutableStateOf<com.lofipod.app.data.db.EpisodeKabodEntity?>(null) }

    LaunchedEffect(episodeGuid) {
        val state = withContext(Dispatchers.IO) { app.db.episodeStateDao().get(episodeGuid) }
        val pod = state?.let { app.repo.cached(it.feedUrl) }
        val ep = pod?.episodes?.find { it.guid == episodeGuid }
        eqDisabled = state?.eqDisabled ?: false
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

        Spacer(Modifier.height(12.dp))
        // Per-episode EQ override. Useful when an episode features a guest whose
        // voice doesn't match the host's EQ profile. Applies immediately to the
        // shared EQ; persists in episode_state.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = eqDisabled,
                onCheckedChange = { v ->
                    eqDisabled = v
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            app.db.episodeStateDao().setEqDisabled(episodeGuid, v)
                        }
                        controller.applyEqOverrideFor(episodeGuid)
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Disable EQ for this episode", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Useful when guests' voices don't match the host's tuning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (ep == null) {
            Text(
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
