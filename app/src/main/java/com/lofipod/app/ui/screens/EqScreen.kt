package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lofipod.app.audio.EqAudioProcessor
import com.lofipod.app.audio.EqBand
import com.lofipod.app.audio.EqPresets
import com.lofipod.app.player.PlaybackService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(onBack: () -> Unit) {
    val eq: EqAudioProcessor = PlaybackService.sharedEq

    var bands by remember { mutableStateOf(eq.currentBands()) }
    var gainDb by remember { mutableStateOf(eq.currentGainDb()) }
    var enabled by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { bands = EqPresets.FLAT; eq.setBands(bands) }, label = { Text("Flat") })
                AssistChip(onClick = { bands = EqPresets.VOICE_BOOST; eq.setBands(bands) }, label = { Text("Voice") })
                AssistChip(onClick = { bands = EqPresets.BASS_BOOST; eq.setBands(bands) }, label = { Text("Bass") })
                AssistChip(onClick = { bands = EqPresets.BRIGHT; eq.setBands(bands) }, label = { Text("Bright") })
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
                }
            }
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
