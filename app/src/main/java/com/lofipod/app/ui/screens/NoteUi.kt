package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.data.Settings
import com.lofipod.app.data.db.EpisodeNoteEntryEntity

/**
 * Shared note card. Three actions — jump-to-position, edit, delete — kept in
 * lockstep across the per-episode Notes screen and the Player screen's Notes
 * tab so changes (label tweaks, layout, future fields) flow to both call
 * sites for free. Optional [episodeTitle] for the global notes browser case
 * where the row needs to show which episode the note belongs to.
 */
@Composable
fun NoteCard(
    entry: EpisodeNoteEntryEntity,
    onJump: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    episodeTitle: String? = null,
) {
    // Notes-text size from Settings — applied to the note body only, not
    // the citation row, so the citation stays compact regardless of how
    // large the user wants their note text. Theme typography still drives
    // the rest of the card; this just nudges the size of the user's prose.
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val noteSizeSp by settings.notesTextSizeSp.collectAsState(initial = 14f)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (episodeTitle != null) {
                Text(
                    episodeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    citationOf(entry.createdAt, entry.playbackPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onJump, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "Jump to position",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = noteSizeSp.sp),
            )
        }
    }
}

/**
 * Shared editor dialog used for both Add and Edit. Single source of truth for
 * the keyboard options, placeholder copy, and "save when non-empty" rule.
 */
@Composable
fun NoteEditorDialog(
    citation: String,
    initialText: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    // Independent text size for the editor surface — typing benefits from
    // a slightly larger size than reading the resulting card. Read live
    // from Settings so the user's adjustment on the Text settings screen
    // takes effect immediately on the next dialog open.
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val popupSizeSp by settings.notesPopupTextSizeSp.collectAsState(initial = 16f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(citation, style = MaterialTheme.typography.bodyMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = popupSizeSp.sp),
                placeholder = { Text("Your thoughts on this moment…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
