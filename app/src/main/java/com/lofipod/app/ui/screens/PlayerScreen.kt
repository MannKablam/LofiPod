package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenEq: () -> Unit
) {
    val state by controller.state.collectAsState()
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Tick the position once a second while playing
    LaunchedEffect(state.isPlaying) {
        while (true) {
            positionMs = controller.currentPositionMs()
            durationMs = controller.durationMs()
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenEq) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = "EQ")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = state.currentArtworkUri,
                contentDescription = null,
                modifier = Modifier.size(280.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                state.currentTitle ?: "Nothing playing",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2
            )
            Text(
                state.currentArtist ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            // Scrubber
            val frac = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            Slider(
                value = frac.coerceIn(0f, 1f),
                onValueChange = { v ->
                    if (durationMs > 0) controller.seekTo((v * durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(positionMs), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(formatTime(durationMs), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.seekRelative(-15_000) }) {
                    Icon(Icons.Filled.Replay, contentDescription = "Back 15s", modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = { controller.togglePlay() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { controller.seekRelative(30_000) }) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Forward 30s", modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            // Speed
            Text("Speed: ${"%.2fx".format(state.speed)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.speed,
                onValueChange = { controller.setSpeed(it) },
                valueRange = 0.5f..3.0f,
                steps = 24,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
