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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.EqAudioProcessor
import com.lofipod.app.audio.EqBand
import com.lofipod.app.audio.EqPresets
import com.lofipod.app.audio.PresetId
import com.lofipod.app.audio.SilenceSkippingProcessor
import com.lofipod.app.data.Settings
import com.lofipod.app.player.PlaybackService
import com.lofipod.app.player.PlayerController
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(controller: PlayerController, onBack: () -> Unit) {
    val eq: EqAudioProcessor = PlaybackService.sharedEq
    val skipSilence: SilenceSkippingProcessor = PlaybackService.sharedSkipSilence
    val playerState by controller.state.collectAsState()
    val app = LocalContext.current.applicationContext as LofiPodApp
    val settings = remember { Settings(app) }
    val persistedSkipLevel by settings.skipSilenceLevel.collectAsState(initial = skipSilence.currentLevel())
    val composeScope = rememberCoroutineScope()

    var bands by remember { mutableStateOf(eq.currentBands()) }
    var gainDb by remember { mutableStateOf(eq.currentGainDb()) }
    var enabled by remember { mutableStateOf(true) }

    // Active preset + its current level. activeLevel == 0 means flat / no preset
    // currently lit. Tapping a different preset switches and starts at level 1.
    var activePreset by remember { mutableStateOf<PresetId?>(null) }
    var activeLevel by remember { mutableStateOf(0) }

    // Scroll plumbing: capture the y-offset of the Graphic EQ header so any
    // preset tap can scroll it into view. Without this, the band sliders sit
    // below the fold and the user has no visual confirmation that the preset
    // actually moved them.
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    var graphicEqY by remember { mutableStateOf(0) }

    fun scrollToBands() {
        if (graphicEqY == 0) return
        scrollScope.launch { scrollState.animateScrollTo(graphicEqY) }
    }

    fun applyFlat() {
        activePreset = null
        activeLevel = 0
        bands = EqPresets.FLAT
        eq.setBands(bands)
        scrollToBands()
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
        scrollToBands()
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
                .verticalScroll(scrollState)
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

            // ---- Skip silence ----
            Text("Skip silence", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "Trims pauses out of voice content. L1 catches dead-air only; L3 tightens conversational gaps. Default off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            StagedLevelButton(
                currentLevel = persistedSkipLevel,
                maxLevel = 3,
                onCycle = {
                    val next = (persistedSkipLevel + 1) % (3 + 1)
                    skipSilence.setLevel(next)
                    composeScope.launch {
                        withContext(Dispatchers.IO) { settings.setSkipSilenceLevel(next) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                offLabel = "Skip silence: Off",
                onLabelPrefix = "Skip silence",
            )
            Spacer(Modifier.height(20.dp))

            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // Flat sits alone up top as the "reset" affordance. The five
            // named presets share a single Row below — keeps the whole
            // preset block to two lines of vertical real estate so the
            // graphic-EQ bands stay visible (or scrolling-distance close)
            // when a preset is tapped.
            FlatButton(
                isActive = activePreset == null,
                onClick = ::applyFlat,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
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

            Text(
                "Graphic EQ",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // Capture the section's y-offset within the scrolled Column
                    // so preset taps can animate the bands into view.
                    graphicEqY = coords.positionInParent().y.toInt()
                }
            )
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
 * Generic staged button — same vertical-slice visual language as [PresetButton]
 * but for a single named control with [maxLevel] stages. Tap cycles
 * 0 → 1 → ... → maxLevel → 0. Used for the Skip-silence row so it reads as
 * the same family of "discrete level" controls as the EQ presets.
 */
@Composable
private fun StagedLevelButton(
    currentLevel: Int,
    maxLevel: Int,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    offLabel: String,
    onLabelPrefix: String,
) {
    val colors = MaterialTheme.colorScheme
    val isActive = currentLevel > 0
    val fillColor = colors.primary
    val emptyColor = colors.surfaceVariant
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onCycle)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until maxLevel) {
                val lit = i < currentLevel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (lit) fillColor else emptyColor)
                )
                if (i < maxLevel - 1) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.outline.copy(alpha = 0.3f))
                    )
                }
            }
        }
        val labelColor = if (isActive) colors.onPrimary else colors.onSurfaceVariant
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (currentLevel == 0) offLabel else "$onLabelPrefix: L$currentLevel",
                color = labelColor,
                style = MaterialTheme.typography.labelMedium,
            )
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
            // Allow up to 2 lines so compound names ("Boom-kill", "Harsh-kill")
            // wrap rather than truncate at narrow weight-distributed widths.
            // textAlign centers the second line under the first.
            Text(
                preset.displayName,
                color = labelColor,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 2.dp)
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
