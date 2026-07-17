package com.lofipod.app.ui.screens

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.DeviceFileEntry
import com.lofipod.app.data.Settings
import com.lofipod.app.data.model.Episode
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Synthetic feedUrl for device files — never fetched, only an identity. */
const val DEVICE_FEED_URL = "device://files"

/**
 * The user's device as a source: audio files picked via SAF, persisted
 * (URI + display name + a persistable read grant) and played through the
 * exact same [PlayerController.playEpisode] path as podcast episodes —
 * so resume positions, checkpoints, notes, the voice suite and the EQ
 * all just work. The synthetic guid is the content:// URI itself, which
 * is stable per document across grants.
 *
 * Downloads never trigger for these (LofiPodDownloader.start ignores
 * non-http audio URLs) — the file is already local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFilesScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }
    val files by settings.deviceFiles.collectAsState(initial = emptyList())
    val playerState by controller.state.collectAsState()
    var removeTarget by remember { mutableStateOf<DeviceFileEntry?>(null) }

    val deviceName = remember {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        // Keep read access across reboots/process death.
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        val name = ctx.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment ?: "Audio file"
                        DeviceFileEntry(uri = uri.toString(), name = name)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            settings.addDeviceFiles(entries)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.smartphone_24),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
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
                    IconButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                        Icon(painterResource(R.drawable.add_24), contentDescription = "Add files")
                    }
                }
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No device files yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add audio files from this device — they play through " +
                        "the full LofiPod chain (EQ, voice suite, notes, " +
                        "resume positions) just like episodes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                    Icon(
                        painterResource(R.drawable.add_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add audio files")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(files, key = { it.uri }) { entry ->
                    val isCurrent = playerState.currentEpisodeGuid == entry.uri
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.playEpisode(
                                    ep = deviceFileEpisode(entry),
                                    podcastTitle = deviceName,
                                    podcastArt = null,
                                )
                                onOpenPlayer()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(
                                    if (isCurrent && playerState.isPlaying) R.drawable.graphic_eq_24
                                    else R.drawable.play_circle_24
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { removeTarget = entry }) {
                                Icon(
                                    painterResource(R.drawable.close_24),
                                    contentDescription = "Remove from list",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    removeTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from list?") },
            text = {
                Text(
                    "\"${entry.name}\" is only removed from LofiPod's list — " +
                        "the file itself stays on the device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.removeDeviceFile(entry.uri) }
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Synthetic Episode for a device file. guid = the content:// URI (stable
 * per document), feedUrl = [DEVICE_FEED_URL]. Position/duration land in
 * episode_state keyed by this guid, so resume works like any episode.
 */
private fun deviceFileEpisode(entry: DeviceFileEntry): Episode = Episode(
    guid = entry.uri,
    feedUrl = DEVICE_FEED_URL,
    title = entry.name,
    description = null,
    pubDateMillis = null,
    audioUrl = entry.uri,
    audioMimeType = null,
    durationSeconds = null,
    episodeArtworkUrl = null,
)
