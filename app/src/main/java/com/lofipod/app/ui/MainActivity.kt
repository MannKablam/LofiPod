@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui

import android.Manifest
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.lofipod.app.player.PlayerController
import com.lofipod.app.player.PlayerState
import com.lofipod.app.ui.screens.*
import com.lofipod.app.ui.theme.LofiPodTheme
import com.lofipod.app.ui.theme.ThemedArtwork
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var playerController: PlayerController

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
                AppNav(playerController)
            }
        }

        playerController.connect { /* ready */ }
    }

    override fun onDestroy() {
        playerController.release()
        super.onDestroy()
    }
}

@Composable
private fun AppNav(controller: PlayerController) {
    val nav = rememberNavController()
    val playerState by controller.state.collectAsState()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

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
            startDestination = "library",
            modifier = Modifier.padding(padding)
        ) {
            composable("library") {
                LibraryScreen(
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
                    onOpenNowPlaying = { nav.navigate("player") }
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
                    onOpenNotes = { guid ->
                        val encoded = URLEncoder.encode(guid, "UTF-8")
                        nav.navigate("notes/$encoded")
                    },
                    onOpenHistory = { nav.navigate("history") }
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
                    Text(
                        "${formatMiniTime(positionMs)} / ${formatMiniTime(durationMs)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
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
