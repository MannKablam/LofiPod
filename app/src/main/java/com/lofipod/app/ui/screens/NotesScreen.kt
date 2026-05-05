@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
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
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotesScreen(
    episodeGuid: String,
    controller: PlayerController,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }

    val entries by app.db.episodeNoteEntryDao()
        .observeForEpisode(episodeGuid)
        .collectAsState(initial = emptyList())
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)

    var addOpen by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<EpisodeNoteEntryEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EpisodeNoteEntryEntity?>(null) }
    var resumeAfterDialog by remember { mutableStateOf(false) }

    fun openAdd() {
        if (pauseOnNote && controller.state.value.isPlaying) {
            resumeAfterDialog = true
            controller.pause()
        }
        addOpen = true
    }

    fun closeAddOrEdit() {
        addOpen = false
        editEntry = null
        if (resumeAfterDialog) {
            controller.play()
            resumeAfterDialog = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = ::openAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "Add note")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = pauseOnNote,
                    onCheckedChange = { v -> scope.launch { settings.setPauseOnNote(v) } }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Pause playback while writing a note",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Divider()

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No notes yet. Tap + to add one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.createdAt }) { entry ->
                        NoteCard(
                            entry = entry,
                            onJump = { controller.jumpToNotePosition(entry) },
                            onEdit = {
                                if (pauseOnNote && controller.state.value.isPlaying) {
                                    resumeAfterDialog = true
                                    controller.pause()
                                }
                                editEntry = entry
                            },
                            onDelete = { deleteEntry = entry }
                        )
                    }
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
            onDismiss = { closeAddOrEdit() },
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
                closeAddOrEdit()
            }
        )
    }

    editEntry?.let { entry ->
        NoteEditorDialog(
            citation = citationOf(entry.createdAt, entry.playbackPosMs),
            initialText = entry.text,
            confirmLabel = "Save",
            onDismiss = { closeAddOrEdit() },
            onConfirm = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.episodeNoteEntryDao().upsert(entry.copy(text = text))
                    }
                }
                closeAddOrEdit()
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

// NoteCard + NoteEditorDialog moved to NoteUi.kt for reuse on the Player
// screen's Notes tab.

