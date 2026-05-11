@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController
import com.lofipod.app.player.PlayerState
import com.lofipod.app.ui.screens.*
import com.lofipod.app.ui.theme.LofiPodTheme
import com.lofipod.app.ui.theme.ThemedArtwork
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var playerController: PlayerController

    /**
     * One-shot navigation events from outside the Compose tree (system media
     * notification taps, etc). Replay = 0 + buffer of 1 means a tap that fires
     * before the NavController is ready still gets delivered when the
     * collector starts up, but stale events don't replay on later collectors.
     */
    private val openPlayerEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission is best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        playerController = PlayerController(this)

        setContent {
            LofiPodTheme {
                AppNav(playerController, openPlayerEvents.asSharedFlow())
            }
        }

        playerController.connect { /* ready */ }

        // Cold-launch path: activity was created by the notification tap.
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Required so subsequent getIntent() calls reflect this one. Otherwise
        // a second notification tap with the activity already alive would
        // re-fire the cached original intent.
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == PlaybackService.ACTION_OPEN_PLAYER) {
            openPlayerEvents.tryEmit(Unit)
        }
    }

    override fun onDestroy() {
        playerController.release()
        super.onDestroy()
    }
}

/**
 * Routes that count as "primary" — the catalog -> episodes -> player chain.
 * Back inside this set walks down the chain (episodes -> catalog -> exit).
 *
 * The "feed refresh" state the user mentions is the loading state inside
 * Catalog itself, not a separate route, so catalog is the practical top.
 */
private val PRIMARY_ROUTES = setOf(
    "catalog",
    "episodes/{feed}",
    "player",
    "player/preview/{guid}",
)

/**
 * Routes that are "nested" under a specific parent secondary, so back from
 * them goes to the parent rather than skipping straight to a primary. Map
 * value is the parent route template.
 *
 * Anything NOT in this map and NOT in [PRIMARY_ROUTES] is a flat secondary
 * — back from it goes to the most recent primary in the back stack.
 */
private val NESTED_PARENTS = mapOf(
    // audioDiagnostics + audiophileNotes used to be pinned to "settings"
    // here, but they're now reachable from EqScreen too (right-justified
    // links at the top of the screen). Pinning to one parent route would
    // teleport the user past EqScreen on back when they arrived via that
    // path; plain popBackStack handles both entry points correctly. Kept
    // appDiagnostics + notes/{guid} pinned because those still have a
    // single canonical parent.
    "appDiagnostics" to "settings",
    "notes/{guid}" to "notesBrowser",
)

/**
 * Navigate to the player in a way that doesn't accumulate historical
 * entries. Walks the current back stack to find the most recent
 * "player flavor" entry (`player`, `player/preview/...`, or
 * `player/transcript/...`), pops up to (and excluding) the route
 * immediately below it, and then pushes a fresh `player` on top.
 *
 * The end-state stack is therefore `[..., naturalParent, player]` — where
 * `naturalParent` is whatever the user was on when they last navigated
 * into the player. Back from player goes there, regardless of how many
 * `player -> settings -> miniplayer -> player -> ...` cycles preceded it.
 *
 * Used by every "shortcut" entry to player: miniplayer tap, the
 * Catalog "Now Playing" link, and the system-media-notification's
 * `ACTION_OPEN_PLAYER` intent. NOT used for the explicit-play paths
 * (tap an episode in EpisodesScreen / MyLists / Search) — those
 * establish a new natural parent and should push fresh.
 *
 * Edge case: no player has ever been in the stack this session (e.g.
 * cold-start where audio resumed without the player being shown). In
 * that case we fall through to a plain navigate, accepting that "back
 * from player" goes to whatever screen the user was just on.
 */
private fun navigateToPlayerCleanly(nav: NavController) {
    val routes = nav.currentBackStack.value.map { it.destination.route }
    val lastPlayerIdx = routes.indexOfLast { route ->
        route == "player" ||
            route?.startsWith("player/preview/") == true ||
            route?.startsWith("player/transcript/") == true
    }
    if (lastPlayerIdx > 0) {
        val belowPlayer = routes[lastPlayerIdx - 1]
        if (belowPlayer != null) {
            // popBackStack(name, inclusive=false) pops top-down to the
            // FIRST (most recent) entry whose route template matches.
            // For `episodes/{feed}` this means we pop to the most-recent
            // episodes screen — the right one for the most-recent
            // player's natural parent in any multi-feed listening session.
            nav.popBackStack(belowPlayer, inclusive = false)
        }
    }
    nav.navigate("player") { launchSingleTop = true }
}

/**
 * Single back-handler used by every screen + the system back gesture so the
 * stack walks the right way regardless of the historical navigation order.
 *
 * Behaviour:
 *   - **Primary**: pop normally (episodes -> catalog -> exit, player -> episodes).
 *   - **Nested secondary**: pop up to the named parent (audioDiagnostics ->
 *     settings, notes/{guid} -> notesBrowser).
 *   - **Flat secondary**: pop up to the most recent primary in the back
 *     stack (settings -> last primary, eq -> last primary, etc.). If for
 *     some reason no primary is in the stack, fall back to a normal pop.
 */
private fun smartBack(nav: NavController, currentRoute: String?) {
    if (currentRoute == null) {
        nav.popBackStack()
        return
    }
    when {
        currentRoute in PRIMARY_ROUTES -> {
            nav.popBackStack()
        }
        currentRoute in NESTED_PARENTS -> {
            nav.popBackStack(NESTED_PARENTS[currentRoute]!!, inclusive = false)
        }
        else -> {
            // Flat secondary. Find the most recent primary still on the stack
            // and pop up to it. The current entry sits on top, so walk
            // backwards skipping it and look for the first primary template.
            val target = nav.currentBackStack.value
                .map { it.destination.route }
                .findLast { route -> route != null && route in PRIMARY_ROUTES }
            if (target != null) {
                nav.popBackStack(target, inclusive = false)
            } else {
                nav.popBackStack()
            }
        }
    }
}

@Composable
private fun AppNav(
    controller: PlayerController,
    openPlayerEvents: SharedFlow<Unit>
) {
    val nav = rememberNavController()
    val playerState by controller.state.collectAsState()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // System back gesture goes through the same smart router as the visible
    // top-bar back buttons. Only intercept for non-primary routes — primary
    // back behavior (player -> episodes -> catalog -> activity finish) is
    // already what we want by default, and intercepting on catalog (the
    // start destination) would swallow the back press that should otherwise
    // exit the app.
    BackHandler(enabled = currentRoute != null && currentRoute !in PRIMARY_ROUTES) {
        smartBack(nav, currentRoute)
    }

    // Route to the Player whenever the system media notification (or any other
    // out-of-Compose source) asks us to. Use the cleaning helper so the back
    // chain lands on the player's natural parent rather than walking through
    // whatever the user was incidentally on when the notification fired.
    LaunchedEffect(Unit) {
        openPlayerEvents.collect {
            if (nav.currentDestination?.route != "player") {
                navigateToPlayerCleanly(nav)
            }
        }
    }

    // Mini-player hides on every flavor of the player route — both the live
    // "player" and the preview "player/preview/{guid}" — so the user gets a
    // clean focus on whatever's on screen above. Audio keeps playing in the
    // background regardless; backing out reveals the dock again.
    val onPlayerRoute = currentRoute == "player" ||
        currentRoute?.startsWith("player/preview/") == true ||
        currentRoute?.startsWith("player/transcript/") == true

    Scaffold(
        bottomBar = {
            if (!onPlayerRoute && playerState.currentEpisodeGuid != null) {
                MiniPlayer(
                    controller = controller,
                    state = playerState,
                    onClick = { navigateToPlayerCleanly(nav) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "catalog",
            modifier = Modifier.padding(padding)
        ) {
            composable("catalog") {
                CatalogScreen(
                    controller = controller,
                    onPodcastClick = { pod ->
                        val encoded = URLEncoder.encode(pod.feedUrl, "UTF-8")
                        nav.navigate("episodes/$encoded")
                    },
                    onOpenMyLists = { nav.navigate("mylists") },
                    onOpenEq = { nav.navigate("eq") },
                    onOpenMetrics = { nav.navigate("metrics") },
                    onOpenNotes = { nav.navigate("notesBrowser") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenNowPlaying = { navigateToPlayerCleanly(nav) },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCanonBrowse = { nav.navigate("canonBrowse") },
                )
            }

            composable("search") {
                EpisodeSearchScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "search") },
                    onOpenPlayer = {
                        nav.navigate("player") { launchSingleTop = true }
                    }
                )
            }

            composable("episodes/{feed}") { back ->
                val raw = back.arguments?.getString("feed") ?: return@composable
                val feedUrl = URLDecoder.decode(raw, "UTF-8")
                EpisodesScreen(
                    feedUrl = feedUrl,
                    controller = controller,
                    onBack = { smartBack(nav, "episodes/{feed}") },
                    onPlay = { ep, pod ->
                        controller.playEpisode(ep, pod.title, pod.artworkUrl)
                        nav.navigate("player")
                    },
                    onPreview = { ep ->
                        val encoded = URLEncoder.encode(ep.guid, "UTF-8")
                        nav.navigate("player/preview/$encoded")
                    }
                )
            }

            composable("player") {
                PlayerScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "player") },
                    onOpenEq = { nav.navigate("eq") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenMyLists = { nav.navigate("mylists") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenTranscript = { guid ->
                        val encoded = URLEncoder.encode(guid, "UTF-8")
                        nav.navigate("player/transcript/$encoded")
                    }
                )
            }

            composable("player/transcript/{guid}") { back ->
                val raw = back.arguments?.getString("guid") ?: return@composable
                val guid = URLDecoder.decode(raw, "UTF-8")
                TranscriptScreen(
                    guid = guid,
                    controller = controller,
                    onBack = { smartBack(nav, "player/transcript/{guid}") }
                )
            }

            // Preview mode: same composable, different route. Carries a guid
            // arg the screen uses to load static episode data without
            // touching the live player. Hitting Play on the preview promotes
            // it to live (state.currentEpisodeGuid catches up and the screen
            // flips into live mode automatically).
            composable("player/preview/{guid}") { back ->
                val encoded = back.arguments?.getString("guid") ?: return@composable
                val guid = URLDecoder.decode(encoded, "UTF-8")
                PlayerScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "player/preview/{guid}") },
                    onOpenEq = { nav.navigate("eq") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenMyLists = { nav.navigate("mylists") },
                    onOpenSettings = { nav.navigate("settings") },
                    previewGuid = guid,
                    onOpenTranscript = { g ->
                        val enc = URLEncoder.encode(g, "UTF-8")
                        nav.navigate("player/transcript/$enc")
                    }
                )
            }

            composable("eq") {
                EqScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "eq") },
                    onOpenAudiophileNotes = { nav.navigate("audiophileNotes") },
                    onOpenLofiNotes = { nav.navigate("lofiNotes") },
                    onOpenAudioDiagnostics = { nav.navigate("audioDiagnostics") },
                )
            }

            composable("metrics") {
                MetricsScreen(onBack = { smartBack(nav, "metrics") })
            }

            composable("settings") {
                SettingsScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "settings") },
                    onOpenAudioDiagnostics = { nav.navigate("audioDiagnostics") },
                    onOpenAudiophileNotes = { nav.navigate("audiophileNotes") },
                    onOpenLofiNotes = { nav.navigate("lofiNotes") },
                    onOpenAppDiagnostics = { nav.navigate("appDiagnostics") },
                    onOpenTextSettings = { nav.navigate("textSettings") },
                )
            }

            composable("audioDiagnostics") {
                // Plain popBackStack so back returns to whoever sent us
                // here — Settings, Audio Fine-tuning, or wherever a
                // future entry point shows up.
                AudioDiagnosticsScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("audiophileNotes") {
                AudiophileNotesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenLofiNotes = {
                        // launchSingleTop so flipping back-and-forth between
                        // the two notes pages doesn't accumulate stack
                        // entries; popBackStack first if we're already
                        // descended from the lofi-notes screen.
                        nav.navigate("lofiNotes") { launchSingleTop = true }
                    },
                )
            }

            composable("lofiNotes") {
                NonAudiophileLofiNotesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAudiophileNotes = {
                        nav.navigate("audiophileNotes") { launchSingleTop = true }
                    },
                )
            }

            composable("textSettings") {
                TextSettingsScreen(
                    onBack = { nav.popBackStack() }
                )
            }

            composable("appDiagnostics") {
                AppDiagnosticsScreen(
                    onBack = { smartBack(nav, "appDiagnostics") }
                )
            }

            composable("canonBrowse") {
                CanonBrowseScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "canonBrowse") },
                )
            }

            composable("notesBrowser") {
                NotesBrowserScreen(
                    controller = controller,
                    onOpenEpisodeNotes = { guid ->
                        val encoded = URLEncoder.encode(guid, "UTF-8")
                        nav.navigate("notes/$encoded")
                    },
                    onBack = { smartBack(nav, "notesBrowser") }
                )
            }

            composable("notes/{guid}") { back ->
                val raw = back.arguments?.getString("guid") ?: return@composable
                val guid = URLDecoder.decode(raw, "UTF-8")
                NotesScreen(
                    episodeGuid = guid,
                    controller = controller,
                    onBack = { smartBack(nav, "notes/{guid}") }
                )
            }

            composable("history") {
                HistoryScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "history") }
                )
            }

            composable("mylists") {
                MyListsScreen(
                    controller = controller,
                    onBack = { smartBack(nav, "mylists") },
                    onPlayEntity = { entity ->
                        playEntity(controller, entity)
                        nav.navigate("player")
                    }
                )
            }
        }
    }
}

/**
 * Persistent mini-player anchored to every screen except the full Player itself.
 * Tinted with primaryContainer so it's clearly distinct from the surfaceVariant
 * cards used elsewhere. Includes scrubber, position/duration text, and skip ±15/30
 * buttons in addition to play/pause — tap anywhere outside a button to expand.
 */
@Composable
private fun MiniPlayer(
    controller: PlayerController,
    state: PlayerState,
    onClick: () -> Unit
) {
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    // While the user is dragging the scrubber, freeze the displayed value at
    // [dragFraction]. Reset to null once the drag finishes (and we've seeked).
    // Without this, the live position-poll loop would yank the thumb back to
    // the actual player position mid-drag, fighting the user's finger.
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.isPlaying, state.currentEpisodeGuid) {
        while (true) {
            positionMs = controller.currentPositionMs()
            durationMs = controller.durationMs()
            delay(500)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor   = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedArtwork(artworkUrl = state.currentArtworkUri, size = 56.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.currentTitle ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    state.currentArtist?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    val displayPosMs = dragFraction?.let { (it * durationMs).toLong() }
                        ?: positionMs
                    Text(
                        "${formatMiniTime(displayPosMs)} / ${formatMiniTime(durationMs)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            // Draggable scrubber. Slider's intrinsic touch target is ~48 dp
            // tall — that's intentional, lets the user grab + drag without
            // pixel-precise aim. The parent Card's onClick still fires when
            // the user taps anywhere outside the slider's hit region.
            val liveFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            Slider(
                value = (dragFraction ?: liveFraction).coerceIn(0f, 1f),
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { f ->
                        if (durationMs > 0) controller.seekTo((f * durationMs).toLong())
                    }
                    dragFraction = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { controller.seekBack() }) {
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = "Back 15s",
                        modifier = Modifier.size(28.dp)
                    )
                }
                val autoplayTimer by controller.autoplayTimer.collectAsState()
                val countdown = com.lofipod.app.ui.screens
                    .rememberAutoplayCountdown(autoplayTimer)
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = { controller.togglePlay() }) {
                        if (countdown != null) {
                            // Hide the regular icon — the countdown overlay
                            // sits on top of the IconButton's hit area.
                            Spacer(Modifier.size(36.dp))
                        } else {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    when {
                        // Mini-surface mirror of the autoplay-confirmation
                        // morph on PlayerScreen — same widget, smaller ring.
                        countdown != null -> com.lofipod.app.ui.screens
                            .AutoplayCountdownContent(
                                info = countdown,
                                ringSize = 40.dp,
                                textStyle = MaterialTheme.typography.labelMedium,
                            )
                        // Subtle buffering ring on the mini-player play
                        // button — gives the user the same "I'm trying"
                        // signal the full player shows, without taking extra
                        // vertical space. Driven by buffering OR stall so
                        // a renderer underrun on the audio chain (the
                        // watchdog's domain) is visible at the bottom of
                        // every screen, not only the full Player.
                        // Suppressed during the countdown so the two
                        // indicators don't stack.
                        (state.isBuffering || state.isStalled) -> CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                IconButton(onClick = { controller.seekForward() }) {
                    Icon(
                        Icons.Filled.Forward30,
                        contentDescription = "Forward 30s",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

private fun formatMiniTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun playEntity(
    controller: PlayerController,
    e: com.lofipod.app.data.db.EpisodeStateEntity
) {
    val ep = com.lofipod.app.data.model.Episode(
        guid = e.guid,
        feedUrl = e.feedUrl,
        title = e.title,
        description = null,
        pubDateMillis = null,
        audioUrl = e.audioUrl,
        audioMimeType = null,
        durationSeconds = null,
        episodeArtworkUrl = e.artworkUrl
    )
    controller.playEpisode(ep, podcastTitle = "", podcastArt = e.artworkUrl)
}
