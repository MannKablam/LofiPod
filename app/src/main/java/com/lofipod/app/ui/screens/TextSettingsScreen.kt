@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R
import com.lofipod.app.data.Settings
import com.lofipod.app.ui.theme.BodyFontChoice
import com.lofipod.app.ui.theme.bodyFamilyFor
import com.lofipod.app.ui.theme.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text settings — body-font choice + notes-text-size sliders.
 *
 * Live preview at the top stays pinned as the user scrolls; renders a
 * representative slice of the app's typography (heading, body, label, a
 * fake note card, and a fake editor surface) using whatever's currently
 * selected. Real-time: writes propagate through Settings → Theme →
 * recomposition, so toggling a font radio or dragging a size slider
 * updates the preview instantly without a separate "apply" step.
 *
 * The font choice controls Material's body-text typography slots
 * (titleSmall through bodyLarge + labels). The display slots
 * (displayMedium / displayLarge / headlines) keep the active theme's
 * `displayFont` for visual character. Two size sliders are
 * notes-specific: they control [NoteCard]'s body text and the
 * [NoteEditorDialog]'s typing surface respectively.
 *
 * No license-credit UI section: bundled OFL fonts ship their license
 * files at `assets/EBGaramond-OFL.txt` + `assets/CormorantGaramond-OFL.txt`,
 * which is full OFL compliance. The user explicitly removed the prior
 * "Fonts" attribution panel; this screen is for making typography
 * choices, not for crediting them.
 */
@Composable
fun TextSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    val bodyFontKey by settings.bodyFontChoiceKey.collectAsState(initial = "THEME_DEFAULT")
    val bodyChoice = remember(bodyFontKey) { BodyFontChoice.fromKey(bodyFontKey) }
    val notesSizeSp by settings.notesTextSizeSp.collectAsState(initial = 14f)
    val popupSizeSp by settings.notesPopupTextSizeSp.collectAsState(initial = 16f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24),
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ---- Live preview ----
            Text(
                "Preview",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            PreviewCard(
                bodyChoice = bodyChoice,
                notesSizeSp = notesSizeSp,
                popupSizeSp = popupSizeSp,
            )

            Spacer(Modifier.height(20.dp))

            // ---- Body font ----
            Text(
                "Body font",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Applies to titles, body text, and labels across the app. " +
                    "Display headings keep the active theme's character.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BodyFontChoice.entries.forEach { choice ->
                FontChoiceRow(
                    choice = choice,
                    selected = bodyChoice == choice,
                    onSelect = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                settings.setBodyFontChoiceKey(choice.name)
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- Notes text size ----
            Text(
                "Notes text size — ${notesSizeSp.toInt()} sp",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Size of your notes' body text on each note card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = notesSizeSp,
                onValueChange = { v ->
                    scope.launch {
                        withContext(Dispatchers.IO) { settings.setNotesTextSizeSp(v) }
                    }
                },
                valueRange = 10f..28f,
                steps = (28 - 10) - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            // ---- Notes pop-up text size ----
            Text(
                "Notes pop-up text size — ${popupSizeSp.toInt()} sp",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Size of the typing surface in the note editor dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = popupSizeSp,
                onValueChange = { v ->
                    scope.launch {
                        withContext(Dispatchers.IO) { settings.setNotesPopupTextSizeSp(v) }
                    }
                },
                valueRange = 10f..28f,
                steps = (28 - 10) - 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Single radio row for [BodyFontChoice]. The label preview text is
 * rendered in that choice's actual family so the user can see what they're
 * about to pick. Tap anywhere on the row toggles the selection.
 */
@Composable
private fun FontChoiceRow(
    choice: BodyFontChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val previewFamily = bodyFamilyFor(choice) ?: MaterialTheme.typography.bodyMedium.fontFamily
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                choice.displayLabel(),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = previewFamily),
            )
            Text(
                "The quick brown fox jumps over the lazy dog. 0123456789",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = previewFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Preview card showing a representative slice of typography in the
 * currently-selected font + at the currently-selected sizes. A title, a
 * body line, a label, a faux note card, and a faux note-editor row.
 */
@Composable
private fun PreviewCard(
    bodyChoice: BodyFontChoice,
    notesSizeSp: Float,
    popupSizeSp: Float,
) {
    val previewFamily = bodyFamilyFor(bodyChoice) ?: MaterialTheme.typography.bodyMedium.fontFamily
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Title — Audio Fine-tuning",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = previewFamily),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "LofiPod runs a real-time audiophile DSP chain on every track. " +
                    "The chain is the same for podcasts and bundled content, " +
                    "built from scratch with no third-party DSP libraries on the audio path.",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewFamily),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "label · timestamp · 12:34",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = previewFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // Faux note card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Text(
                        "Note card — body text size",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = previewFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "What you'd actually type lives here. The size slider " +
                            "below moves this line up or down so you can dial " +
                            "in your preferred reading size.",
                        style = TextStyle(
                            fontFamily = previewFamily,
                            fontSize = notesSizeSp.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Faux editor surface
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Text(
                    "Note pop-up — typing surface size",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = previewFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your thoughts on this moment, at the size the editor will use.",
                    style = TextStyle(
                        fontFamily = previewFamily,
                        fontSize = popupSizeSp.sp,
                    ),
                )
            }
        }
    }
}
