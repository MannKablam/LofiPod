package com.lofipod.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lofipod.app.audio.EqAudioProcessor
import com.lofipod.app.audio.EqBand
import com.lofipod.app.audio.EqPresets
import com.lofipod.app.audio.PresetId
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(controller: PlayerController, onBack: () -> Unit) {
    val eq: EqAudioProcessor = PlaybackService.sharedEq
    val playerState by controller.state.collectAsState()

    var bands by remember { mutableStateOf(eq.currentBands()) }
    var gainDb by remember { mutableStateOf(eq.currentGainDb()) }
    var enabled by remember { mutableStateOf(true) }

    // Active preset + its current level. activeLevel == 0 means flat / no preset
    // currently lit. Tapping a different preset switches and starts at level 1.
    var activePreset by remember { mutableStateOf<PresetId?>(null) }
    var activeLevel by remember { mutableStateOf(0) }

    fun applyFlat() {
        activePreset = null
        activeLevel = 0
        bands = EqPresets.FLAT
        eq.setBands(bands)
    }

    fun cyclePreset(preset: PresetId) {
        if (activePreset != preset) {
            activePreset = preset
            activeLevel = 1
        } else {
            activeLevel = (activeLevel + 1) % (preset.maxLevel + 1)
            if (activeLevel == 0) activePreset = null
        }
        bands = if (activePreset == null) EqPresets.FLAT
                else preset.levels[activeLevel - 1]
        eq.setBands(bands)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio") },
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
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    eq.setEnabled(it)
                })
                Spacer(Modifier.width(12.dp))
                Text("Audio enhancement", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(20.dp))

            // ---- Playback speed ----
            Text(
                "Playback speed: ${"%.2fx".format(playerState.speed)}",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = playerState.speed,
                onValueChange = { controller.setSpeed(it) },
                valueRange = 0.5f..3.0f,
                steps = 24
            )
            Text(
                "Lives here so it's not bumped by accident on the player.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Text("Volume boost: ${"%+.1f".format(gainDb)} dB", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = gainDb,
                onValueChange = {
                    gainDb = it
                    eq.setGainDb(it)
                },
                valueRange = 0f..12f,
                steps = 23
            )
            Text(
                "Soft-clipped — pushing past +6 dB stays musical instead of harsh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FlatButton(
                    isActive = activePreset == null,
                    onClick = ::applyFlat,
                    modifier = Modifier.weight(1f)
                )
                PresetId.values().forEach { preset ->
                    PresetButton(
                        preset = preset,
                        currentLevel = if (activePreset == preset) activeLevel else 0,
                        anyOtherActive = activePreset != null && activePreset != preset,
                        onClick = { cyclePreset(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            Text("Graphic EQ", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            bands.forEachIndexed { idx, band ->
                BandRow(band) { newGain ->
                    val newBands = bands.toMutableList()
                    newBands[idx] = band.copy(gainDb = newGain)
                    bands = newBands
                    eq.setBands(newBands)
                    // Manual band edits drop us off the preset rail.
                    activePreset = null
                    activeLevel = 0
                }
            }
        }
    }
}

/**
 * A single-level "Flat" button, styled the same way as the multi-level preset
 * buttons but without divisions — either fully filled (active) or hollow.
 */
@Composable
private fun FlatButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val fill = if (isActive) colors.primary else colors.surfaceVariant
    val ink = if (isActive) colors.onPrimary else colors.onSurfaceVariant
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("Flat", color = ink, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Multi-level preset button. Background is split into [maxLevel] vertical
 * slices; the leftmost [currentLevel] slices light up. When another preset is
 * active, this one's slices are drawn dimmed/grayed-out — making it visually
 * obvious only one preset can be lit at a time.
 */
@Composable
private fun PresetButton(
    preset: PresetId,
    currentLevel: Int,
    anyOtherActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isActive = currentLevel > 0
    val fillColor = colors.primary
    val emptyColor = colors.surfaceVariant
    val grayedFill = colors.onSurface.copy(alpha = 0.20f)
    val grayedEmpty = colors.onSurface.copy(alpha = 0.06f)

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        // Background slices first.
        Row(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until preset.maxLevel) {
                val lit = i < currentLevel
                val sliceColor: Color = when {
                    anyOtherActive && lit -> grayedFill
                    anyOtherActive -> grayedEmpty
                    lit -> fillColor
                    else -> emptyColor
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(sliceColor)
                )
                if (i < preset.maxLevel - 1) {
                    // Hairline separator between slices so divisions read clearly
                    // even when adjacent slices are the same color.
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.outline.copy(alpha = 0.3f))
                    )
                }
            }
        }
        // Foreground label
        val labelColor = when {
            isActive -> colors.onPrimary
            anyOtherActive -> colors.onSurface.copy(alpha = 0.5f)
            else -> colors.onSurfaceVariant
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                preset.displayName,
                color = labelColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BandRow(band: EqBand, onChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            formatHz(band.centerHz),
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = band.gainDb,
            onValueChange = onChange,
            valueRange = -12f..12f,
            steps = 23,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%+.0f".format(band.gainDb),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatHz(hz: Float): String =
    if (hz >= 1000) "${(hz / 1000).toInt()}kHz" else "${hz.toInt()}Hz"
