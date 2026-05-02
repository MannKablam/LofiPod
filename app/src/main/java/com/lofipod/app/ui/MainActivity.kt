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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.lofipod.app.player.PlayerController
import com.lofipod.app.player.PlayerState
import com.lofipod.app.ui.screens.*
import com.lofipod.app.ui.theme.LofiPodTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var playerController: PlayerController

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is best-effort; playback still works */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for POST_NOTIFICATIONS on API 33+ so the media notification can show
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
            // Show the mini-player on every screen except the full Player itself.
            if (currentRoute != "player" && playerState.currentEpisodeGuid != null) {
                MiniPlayer(
                    state = playerState,
                    onClick = { nav.navigate("player") },
                    onPlayPause = { controller.togglePlay() }
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
                    onPodcastClick = { pod ->
                        val encoded = URLEncoder.encode(pod.feedUrl, "UTF-8")
                        nav.navigate("episodes/$encoded")
                    },
                    onOpenFavorites = { nav.navigate("favorites") },
                    onOpenEq = { nav.navigate("eq") },
                    onOpenMetrics = { nav.navigate("metrics") },
                    onOpenNotes = { nav.navigate("notesBrowser") }
                )
            }

            composable("episodes/{feed}") { back ->
                val raw = back.arguments?.getString("feed") ?: return@composable
                val feedUrl = URLDecoder.decode(raw, "UTF-8")
                EpisodesScreen(
                    feedUrl = feedUrl,
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
                    }
                )
            }

            composable("eq") {
                EqScreen(onBack = { nav.popBackStack() })
            }

            composable("metrics") {
                MetricsScreen(onBack = { nav.popBackStack() })
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

            composable("favorites") {
                FavoritesScreen(
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

@Composable
private fun MiniPlayer(
    state: PlayerState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = state.currentArtworkUri,
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause"
                )
            }
        }
    }
}

/** Helper: play a saved EpisodeStateEntity (no Episode object available). */
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
