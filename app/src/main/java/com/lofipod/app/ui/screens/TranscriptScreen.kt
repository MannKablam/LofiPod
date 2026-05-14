@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.player.PlayerController

/**
 * Full-page transcript view, reachable from the in-Player Transcript tab's
 * "Read full page" icon. Hides the artwork / scrubber / controls so the user
 * gets a clean reading surface; audio keeps playing in the background.
 *
 * Top bar: back arrow, episode title (1-line), play/pause icon. No nav rail
 * — readers shouldn't be one tap away from accidentally leaving the page.
 */
@Composable
fun TranscriptScreen(
    guid: String,
    controller: PlayerController,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val state by controller.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Resolve the title from in-memory state if this episode is loaded;
    // otherwise from episode_state for headers in non-loaded contexts.
    val titleFromState = if (state.currentEpisodeGuid == guid) state.currentTitle else null
    val titleFlow = remember(guid) { app.db.episodeStateDao().observe(guid) }
    val stateRow by titleFlow.collectAsState(initial = null)
    val title = titleFromState ?: stateRow?.title ?: "Transcript"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                actions = {
                    if (state.currentEpisodeGuid == guid) {
                        IconButton(onClick = { controller.togglePlay() }) {
                            Icon(
                                if (state.isPlaying) painterResource(R.drawable.pause_24) else painterResource(R.drawable.play_arrow_24),
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp),
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
            TranscriptContent(
                episodeGuid = guid,
                showFullPageButton = false,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
