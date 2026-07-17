package com.lofipod.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.share.StudySheet
import com.lofipod.app.share.StudySheetMode
import com.lofipod.app.share.shareStudySheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared study-sheet flow for every entry point (per-episode Notes screen,
 * Player Notes tab). Pass a non-null [episodeGuid] to start; the host
 * checks whether a transcript is cached — if so it offers the inclusion
 * modes, otherwise it exports notes-only directly. Call sites reset their
 * guid state via [onDone].
 */
@Composable
fun StudySheetDialogHost(
    episodeGuid: String?,
    onDone: () -> Unit,
) {
    if (episodeGuid == null) return
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp

    var hasTranscript by remember(episodeGuid) { mutableStateOf<Boolean?>(null) }
    var durationKnown by remember(episodeGuid) { mutableStateOf(true) }
    var chosenMode by remember(episodeGuid) { mutableStateOf(StudySheetMode.NOTES_WITH_PARAGRAPHS) }

    // One IO probe decides the flow: no transcript → export immediately
    // (no dialog to show); transcript → let the user pick what to include.
    LaunchedEffect(episodeGuid) {
        val (transcript, durKnown) = withContext(Dispatchers.IO) {
            val t = app.db.episodeTranscriptDao().get(episodeGuid) != null
            val d = StudySheet.load(app, episodeGuid)?.durationMs ?: 0L
            t to (d > 0L)
        }
        durationKnown = durKnown
        if (!transcript) {
            exportAndShare(ctx, app, episodeGuid, StudySheetMode.NOTES_ONLY)
            onDone()
        } else {
            hasTranscript = true
            if (!durKnown) chosenMode = StudySheetMode.NOTES_ONLY
        }
    }

    if (hasTranscript != true) return

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Study sheet") },
        text = {
            Column {
                Text(
                    "This episode has a transcript. Include it?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ModeRow(
                    label = "Notes only",
                    selected = chosenMode == StudySheetMode.NOTES_ONLY,
                    enabled = true,
                ) { chosenMode = StudySheetMode.NOTES_ONLY }
                ModeRow(
                    label = "Notes + cited paragraphs",
                    selected = chosenMode == StudySheetMode.NOTES_WITH_PARAGRAPHS,
                    enabled = durationKnown,
                ) { chosenMode = StudySheetMode.NOTES_WITH_PARAGRAPHS }
                ModeRow(
                    label = "Notes + full transcript",
                    selected = chosenMode == StudySheetMode.NOTES_WITH_FULL_TRANSCRIPT,
                    enabled = true,
                ) { chosenMode = StudySheetMode.NOTES_WITH_FULL_TRANSCRIPT }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (durationKnown)
                        "Paragraphs are matched by position estimate — the " +
                            "transcript has no timestamps, so citations are approximate."
                    else
                        "Paragraph matching needs a known episode duration — " +
                            "play a few seconds first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mode = chosenMode
                // The export must outlive this dialog (onDone() removes the
                // host from composition, cancelling any rememberCoroutineScope
                // job), so it runs on a process-lifetime scope. One-shot,
                // ~100ms of IO + a share intent — fire-and-forget is correct.
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                    exportAndShare(ctx, app, episodeGuid, mode)
                }
                onDone()
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel") }
        },
    )
}

private suspend fun exportAndShare(
    ctx: android.content.Context,
    app: LofiPodApp,
    guid: String,
    mode: StudySheetMode,
) {
    val (markdown, fileName, title) = withContext(Dispatchers.IO) {
        val data = StudySheet.load(app, guid) ?: return@withContext null
        Triple(
            StudySheet.renderMarkdown(data, mode),
            StudySheet.fileNameFor(data.episodeTitle),
            data.episodeTitle,
        )
    } ?: run {
        android.widget.Toast.makeText(ctx, "Episode data missing.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    ctx.shareStudySheet(markdown, fileName, title)
}

@Composable
private fun ModeRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
