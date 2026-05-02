@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings
import com.lofipod.app.ui.theme.specFor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as LofiPodApp
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(app) }

    val theme by settings.theme.collectAsState(initial = LofiTheme.CASSETTE)
    val pauseOnNote by settings.pauseOnNote.collectAsState(initial = true)
    val autoPlayNextInFeed by settings.autoPlayNextInFeed.collectAsState(initial = true)
    val showPlayedInList by settings.showPlayedInList.collectAsState(initial = true)
    val textScale by settings.textScale.collectAsState(initial = 1.0f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SectionHeader("Theme")
            Spacer(Modifier.height(4.dp))
            LofiTheme.values().forEach { t ->
                ThemeRow(
                    theme = t,
                    selected = (t == theme),
                    onSelect = { scope.launch { settings.setTheme(t) } }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Playback")
            SwitchRow(
                checked = autoPlayNextInFeed,
                title = "Auto-play next in feed",
                subtitle = "When the queue is empty, advance to the next published " +
                    "episode of the same podcast at the end of one.",
                onCheckedChange = { v -> scope.launch { settings.setAutoPlayNextInFeed(v) } }
            )
            SwitchRow(
                checked = showPlayedInList,
                title = "Show played episodes",
                subtitle = "Already-finished episodes stay visible (dimmed and " +
                    "struck through) instead of disappearing from the per-podcast list.",
                onCheckedChange = { v -> scope.launch { settings.setShowPlayedInList(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Notes")
            SwitchRow(
                checked = pauseOnNote,
                title = "Pause playback while writing a note",
                subtitle = "Audio resumes once the note is saved or cancelled.",
                onCheckedChange = { v -> scope.launch { settings.setPauseOnNote(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Display")
            TextScaleRow(
                value = textScale,
                onChange = { v -> scope.launch { settings.setTextScale(v) } }
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Audio")
            Text(
                "Playback speed and EQ live in the EQ screen (top-bar overflow menu).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Fonts")
            Text(
                "Press Start 2P by Cody \"CodeMan38\" Boisclair, used under the SIL Open Font License 1.1. Full license text bundled at assets/PressStart2P-OFL.txt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("About")
            Text(
                "LofiPod — a personal-canon podcast app. Backups + restore live " +
                    "in Metrics. Theme, queue auto-play, archive, and EQ-per-episode " +
                    "preferences persist across reinstalls only when the new build " +
                    "is signed with the same key as the previous one (see BUILD_LOG).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Two-line settings row with a leading [Switch]. Used for every boolean
 *  toggle in this screen so they line up visually. */
@Composable
private fun SwitchRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Text-scale slider. Local preview while dragging — only commits to settings
 * on release so the whole-app fontScale doesn't thrash on every drag tick.
 * The sample line under the slider renders at the previewed scale so the
 * user sees the effect of the slider directly under their thumb instead of
 * having to look around the screen for what changed.
 *
 * Range matches Settings.textScale (0.85 .. 1.4).
 */
@Composable
private fun TextScaleRow(value: Float, onChange: (Float) -> Unit) {
    // Local drag state — initialized from the persisted value and re-synced
    // any time it changes externally (e.g. backup restore, second device).
    var preview by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Text size: ${"%.0f".format(preview * 100)}%",
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Used everywhere except the playback artwork. Bumping it up makes " +
                "longer reading sessions easier on the eyes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Slider(
            value = preview,
            onValueChange = { preview = it },
            onValueChangeFinished = { onChange(preview) },
            valueRange = 0.85f..1.4f,
            steps = 10  // 11 stops between 0.85 and 1.4 (~5% increments)
        )
        Spacer(Modifier.height(4.dp))
        // Live preview line. fontSize derives from bodyLarge (16sp default)
        // scaled by the previewed multiplier so the user can see the effect
        // of the slider directly while dragging, without committing to the
        // whole-app rescale.
        val baseSp = 16f
        Text(
            "The quick brown fox jumps over the lazy dog.",
            style = MaterialTheme.typography.bodyLarge,
            fontSize = (baseSp * preview).sp
        )
    }
}

/**
 * Theme picker row: 4-stripe palette swatch (background, surface, primary,
 * secondary) + name + tagline + check mark when active. Renders the swatches
 * from each theme's own [specFor] so the row previews the actual look without
 * having to switch into it.
 */
@Composable
private fun ThemeRow(
    theme: LofiTheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val spec = specFor(theme)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PaletteSwatch(
            colors = listOf(
                spec.colors.background,
                spec.colors.surface,
                spec.colors.primary,
                spec.colors.secondary
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                theme.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PaletteSwatch(colors: List<Color>) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
    ) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 28.dp)
                    .background(c)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
