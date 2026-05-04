@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

@Composable
private fun AppNav(
    controller: PlayerController,
    openPlayerEvents: SharedFlow<Unit>
) {
    val nav = rememberNavController()
    val playerState by controller.state.collectAsState()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Route to the Player whenever the system media notification (or any other
    // out-of-Compose source) asks us to. launchSingleTop avoids stacking
    // duplicate Player entries when the notification is tapped repeatedly.
    LaunchedEffect(Unit) {
        openPlayerEvents.collect {
            if (nav.currentDestination?.route != "player") {
                nav.navigate("player") { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != "player" && playerState.currentEpisodeGuid != null) {
                MiniPlayer(
                    controller = controller,
                    state = playerState,
                    onClick = { nav.navigate("player") }
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
                    onOpenNowPlaying = { nav.navigate("player") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenSearch = { nav.navigate("search") }
                )
            }

            composable("search") {
                EpisodeSearchScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() },
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
                    onBack = { nav.popBackStack() },
                    onPlay = { ep, pod ->
                        controller.playEpisode(ep, pod.title, pod.artworkUrl)
                        nav.navigate("player")
                    }
                )
            }

            composable("player") {
                PlayerScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() },
                    onOpenEq = { nav.navigate("eq") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenMyLists = { nav.navigate("mylists") },
                    onOpenSettings = { nav.navigate("settings") }
                )
            }

            composable("eq") {
                EqScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("metrics") {
                MetricsScreen(onBack = { nav.popBackStack() })
            }

            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }

            composable("notesBrowser") {
                NotesBrowserScreen(
                    controller = controller,
                    onOpenEpisodeNotes = { guid ->
                        val encoded = URLEncoder.encode(guid, "UTF-8")
                        nav.navigate("notes/$encoded")
                    },
                    onBack = { nav.popBackStack() }
                )
            }

            composable("notes/{guid}") { back ->
                val raw = back.arguments?.getString("guid") ?: return@composable
                val guid = URLDecoder.decode(raw, "UTF-8")
                NotesScreen(
                    episodeGuid = guid,
                    controller = controller,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("history") {
                HistoryScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("mylists") {
                MyListsScreen(
                    controller = controller,
                    onBack = { nav.popBackStack() },
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
                IconButton(onClick = { controller.seekRelative(-15_000) }) {
                    Icon(
                        Icons.Filled.Replay,
                        contentDescription = "Back 15s",
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { controller.togglePlay() }) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { controller.seekRelative(30_000) }) {
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
