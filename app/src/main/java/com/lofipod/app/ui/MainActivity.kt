package com.lofipod.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lofipod.app.LofiPodApp
import com.lofipod.app.player.PlayerController
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

@androidx.compose.runtime.Composable
private fun AppNav(controller: PlayerController) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "library") {

        composable("library") {
            LibraryScreen(
                onPodcastClick = { pod ->
                    val encoded = URLEncoder.encode(pod.feedUrl, "UTF-8")
                    nav.navigate("episodes/$encoded")
                },
                onOpenFavorites = { nav.navigate("favorites") },
                onOpenEq = { nav.navigate("eq") }
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
                onOpenEq = { nav.navigate("eq") }
            )
        }

        composable("eq") {
            EqScreen(onBack = { nav.popBackStack() })
        }

        composable("favorites") {
            FavoritesScreen(
                onBack = { nav.popBackStack() },
                onPlayEntity = { entity ->
                    // Build a minimal MediaItem and play it directly
                    val item = MediaItem.Builder()
                        .setMediaId(entity.guid)
                        .setUri(entity.audioUrl)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entity.title)
                                .setArtworkUri(entity.artworkUrl?.let(android.net.Uri::parse))
                                .build()
                        )
                        .build()
                    // The controller exposes playEpisode for Episode; here we replicate inline
                    // using the underlying player via reflection-free path:
                    playEntity(controller, entity)
                    nav.navigate("player")
                }
            )
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
