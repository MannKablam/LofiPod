package com.lofipod.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.lofipod.app.audio.AudioChainTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10

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
    // Master "Audio enhancement" toggle, persisted in Settings so it doesn't
    // desync from the processor on screen revisit and isn't silently
    // overwritten by the per-episode eqDisabled override on track
    // transitions. Writes go through Settings + applyEqOverrideFor (single
    // source of truth for the effective enabled state).
    val audioEnhancementEnabled by settings.audioEnhancementEnabled.collectAsState(initial = true)
    // Pre-EQ DC blocker. Persisted globally; toggle pushes BOTH to Settings
    // (survives restart) and to the live processor (takes effect immediately
    // without a track transition). Mirrors the diagnostics screen's reset path.
    val dcBlockerEnabled by settings.dcBlockerEnabled.collectAsState(initial = false)
    // EQ phase mode. False (default) = minimum-phase biquad cascade,
    // ~6.4 ms latency. True = linear-phase 4096-tap FIR convolution,
    // ~52 ms latency, preserves transient waveform shape exactly.
    val phaseModeLinear by settings.phaseModeLinear.collectAsState(initial = false)

    // 250 ms poll for the live level meters. Same pattern as
    // AudioDiagnosticsScreen: audio thread updates @Volatile fields on every
    // frame; we sample them periodically so the UI doesn't recompose 44k
    // times a second. LaunchedEffect's coroutine cancels when the composable
    // leaves composition, so navigating away kills the polling.
    var meterTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250L)
            meterTick++
        }
    }
    // Triple of (inputPeakLinear, outputPeakLinear, reductionDb). Captured
    // once per tick so the three meters share a consistent snapshot.
    val meterSnap = remember(meterTick) {
        Triple(
            AudioChainTelemetry.inputPeak,
            AudioChainTelemetry.outputPeak,
            AudioChainTelemetry.reductionDb,
        )
    }

    // Per-podcast state for the podcast that owns the currently-playing
    // episode. Drives the two per-podcast toggles below (disable / one-off
    // EQ) AND the override-color tinting that signals "you're editing the
    // per-podcast preset, not the global one." When no episode is loaded,
    // both flows yield null and the toggles stay disabled.
    //
    // Per-podcast (not per-episode) since v0.6.11 — the user expectation
    // was always "tweaking EQ for an episode of a podcast applies to that
    // whole podcast." Per-episode granularity wasn't useful.
    val currentEpisodeGuid = playerState.currentEpisodeGuid
    val episodeStateFlow = remember(currentEpisodeGuid) {
        if (currentEpisodeGuid == null) {
            kotlinx.coroutines.flow.flowOf<com.lofipod.app.data.db.EpisodeStateEntity?>(null)
        } else {
            app.db.episodeStateDao().observe(currentEpisodeGuid)
        }
    }
    val episodeState by episodeStateFlow.collectAsState(initial = null)
    val currentFeedUrl = episodeState?.feedUrl
    val podcastStateFlow = remember(currentFeedUrl) {
        if (currentFeedUrl == null) {
            kotlinx.coroutines.flow.flowOf<com.lofipod.app.data.db.PodcastStateEntity?>(null)
        } else {
            app.db.podcastStateDao().observe(currentFeedUrl)
        }
    }
    val podcastState by podcastStateFlow.collectAsState(initial = null)
    val eqDisabledForPodcast = podcastState?.eqDisabled ?: false
    val podcastOverrideOn = (podcastState?.eqBandsCsvOverride != null)

    // When override is active, controls re-tint to the override color so the
    // user has a visible reminder that slider movement is shaping a
    // per-podcast preset, not the global one. Tertiary is distinct enough
    // from primary that it reads at a glance while still feeling part of
    // the palette.
    val overrideColor = MaterialTheme.colorScheme.tertiary
    val accentColor = if (podcastOverrideOn) overrideColor
                      else MaterialTheme.colorScheme.primary
    val sliderColors = SliderDefaults.colors(
        thumbColor = accentColor,
        activeTrackColor = accentColor,
    )

    // Active preset + its current level. activeLevel == 0 means flat / no preset
    // currently lit. Tapping a different preset switches and starts at level 1.
    // Derived from current bands so navigating away and back lands on the same
    // highlight the audio is actually playing — without this the screen showed
    // "Flat" while the EQ was still applying e.g. Voice L2.
    val initialPreset = remember { derivePresetFromBands(bands) }
    var activePreset by remember { mutableStateOf(initialPreset.first) }
    var activeLevel by remember { mutableStateOf(initialPreset.second) }

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

    /**
     * Persist the current band gains. When the per-podcast override is active
     * for the podcast that owns the currently-playing episode, writes go to
     * that podcast's row in `podcast_state.eqBandsCsvOverride`; otherwise
     * they go to the global `Settings.eqBandsCsv`. In both cases the CSV
     * format is one float per ISO band (gain in dB), in band order. Centers
     * + Q come from `EqPresets.DEFAULT_BANDS` so only user-controlled gains
     * round-trip.
     */
    fun persistBands(latest: List<EqBand>) {
        val csv = latest.joinToString(",") { it.gainDb.toString() }
        val routeToOverride = podcastOverrideOn && currentFeedUrl != null
        composeScope.launch {
            withContext(Dispatchers.IO) {
                if (routeToOverride) {
                    val dao = app.db.podcastStateDao()
                    dao.ensureRow(currentFeedUrl!!)
                    dao.setEqBandsCsvOverride(currentFeedUrl, csv)
                } else {
                    settings.setEqBandsCsv(csv)
                }
            }
        }
    }

    fun applyFlat() {
        activePreset = null
        activeLevel = 0
        bands = EqPresets.FLAT
        eq.setBands(bands)
        persistBands(bands)
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
        persistBands(bands)
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
                Switch(checked = audioEnhancementEnabled, onCheckedChange = { v ->
                    composeScope.launch {
                        withContext(Dispatchers.IO) { settings.setAudioEnhancementEnabled(v) }
                        // Re-apply against the currently playing episode so the
                        // change takes effect immediately (instead of waiting
                        // for the next track transition).
                        playerState.currentEpisodeGuid?.let {
                            controller.applyEqOverrideFor(it)
                        }
                    }
                })
                Spacer(Modifier.width(12.dp))
                Text("Audio enhancement", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "EQ + master gain are global — they apply to every podcast and " +
                    "every episode. For one-off shaping, use the toggles below " +
                    "while an episode is playing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = dcBlockerEnabled, onCheckedChange = { v ->
                    composeScope.launch {
                        withContext(Dispatchers.IO) { settings.setDcBlockerEnabled(v) }
                        // Push to the live processor too. Settings persistence
                        // alone wouldn't take effect until next configure / track
                        // transition; this makes the toggle audible immediately.
                        eq.setDcBlockerEnabled(v)
                    }
                })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("DC blocker", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Removes DC offset from poorly-encoded sources before the EQ amplifies it. Default off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---- Phase mode (Minimum / Linear) ----
            // Switches the EQ stage between the default minimum-phase biquad
            // cascade and the linear-phase FIR convolution. Distinct from
            // the master toggle: that turns the chain off entirely; this
            // chooses HOW the EQ shaping is implemented when the chain is
            // on. Mid-playback switches have a brief audible artifact
            // (~50 ms) at the transition since the two paths have different
            // group delays. Could be smoothed with a parallel cross-fade
            // later; acceptable for a manual-mode-switch affordance.
            Text("Phase mode", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "Minimum: ~6.4 ms latency, transparent for nearly all listeners (default). " +
                    "Linear: ~52 ms latency, preserves transient waveform shape exactly. " +
                    "Higher CPU; opt-in for audiophile-grade A/B testing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = !phaseModeLinear,
                    onClick = {
                        composeScope.launch {
                            withContext(Dispatchers.IO) {
                                settings.setPhaseModeLinear(false)
                            }
                            eq.setPhaseModeLinear(false)
                        }
                    },
                    label = { Text("Minimum") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = phaseModeLinear,
                    onClick = {
                        composeScope.launch {
                            withContext(Dispatchers.IO) {
                                settings.setPhaseModeLinear(true)
                            }
                            eq.setPhaseModeLinear(true)
                        }
                    },
                    label = { Text("Linear") }
                )
            }
            Spacer(Modifier.height(20.dp))

            // ---- Hold to A/B button ----
            // Press-and-hold flips the live processor to passthrough for the
            // duration of the press; releasing restores the chain to its prior
            // effective state. Pure transient — never writes to Settings or
            // per-episode state, so a forgotten release can't strand the user
            // in bypass mode. Lives directly under the global toggles because
            // it's a sibling affordance: master toggle = persistent off; this
            // = momentary off for A/B.
            HoldToBypassButton(
                effectiveChainEnabled = audioEnhancementEnabled && !eqDisabledForPodcast,
                onPress = { eq.setEnabled(false) },
                // Restore to TRUE because the button is only enabled when the
                // chain is currently effectively on (see effectiveChainEnabled
                // gate). If master or per-episode disable were on, the button
                // would have been inert in the first place — race-free.
                onRelease = { eq.setEnabled(true) },
            )
            Spacer(Modifier.height(20.dp))

            // ---- Per-podcast controls. Both rows are inert when no episode
            // is loaded (= no podcast in scope). The override toggle re-tints
            // the EQ controls below in the override color so the user has a
            // persistent visual reminder that slider movement is shaping a
            // per-podcast preset rather than the global one.
            //
            // Per-podcast (not per-episode) since v0.6.11 — the user's
            // expectation was always "tweaking EQ for an episode of a
            // podcast applies to that whole podcast." ----
            Text("For this podcast", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val podcastControlsEnabled = currentFeedUrl != null
            val perPodcastSwitchColors = SwitchDefaults.colors(
                checkedThumbColor = overrideColor,
                checkedTrackColor = overrideColor.copy(alpha = 0.5f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = eqDisabledForPodcast,
                    enabled = podcastControlsEnabled,
                    onCheckedChange = { v ->
                        val feedUrl = currentFeedUrl ?: return@Switch
                        val guid = currentEpisodeGuid ?: return@Switch
                        composeScope.launch {
                            withContext(Dispatchers.IO) {
                                val dao = app.db.podcastStateDao()
                                dao.ensureRow(feedUrl)
                                dao.setEqDisabled(feedUrl, v)
                            }
                            controller.applyEqOverrideFor(guid)
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Disable EQ for this podcast", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Useful when a podcast's mix already sounds right and the global EQ would change it. Forces full passthrough for every episode of this podcast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = podcastOverrideOn,
                    enabled = podcastControlsEnabled,
                    colors = perPodcastSwitchColors,
                    onCheckedChange = { v ->
                        val feedUrl = currentFeedUrl ?: return@Switch
                        val guid = currentEpisodeGuid ?: return@Switch
                        val seedCsv = if (v) bands.joinToString(",") { it.gainDb.toString() }
                                      else null
                        composeScope.launch {
                            withContext(Dispatchers.IO) {
                                val dao = app.db.podcastStateDao()
                                dao.ensureRow(feedUrl)
                                dao.setEqBandsCsvOverride(feedUrl, seedCsv)
                            }
                            // Re-evaluate the live processor: when seeding,
                            // it'll pick up the new override; when clearing,
                            // it'll fall back to global Settings bands.
                            controller.applyEqOverrideFor(guid)
                            // After clearing, sync the visible bands to
                            // whatever the global value is so the screen
                            // doesn't keep showing (now-stale) override values.
                            if (!v) bands = eq.currentBands()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Use a custom EQ for this podcast", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Slider changes save to this podcast only. The override color marks the EQ controls while it's on. Applies to every episode in this feed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                onValueChangeFinished = {
                    // Persist on release rather than every drag tick — DataStore
                    // writes coalesce in practice, but no reason to thrash.
                    // Note: master gain stays GLOBAL even when the per-episode
                    // override is on. Only the band gains are part of the
                    // per-episode profile; treating the boost as global keeps
                    // perceived loudness consistent across an
                    // override-vs-default A/B without surprising the user.
                    composeScope.launch {
                        withContext(Dispatchers.IO) { settings.setGainDb(gainDb) }
                    }
                },
                colors = sliderColors,
                valueRange = 0f..12f,
                steps = 23
            )
            Text(
                "Soft-clipped — pushing past +6 dB stays musical instead of harsh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ---- Live levels ----
            // Three small bar meters fed by AudioChainTelemetry's @Volatile
            // peak/GR fields. Updated on the 250 ms tick. When the chain is
            // in passthrough (audio enhancement off, or FLAT + 0 dB + DC
            // blocker off) the audio thread doesn't update these, so the
            // bars sit at whatever the chain last saw — typically zero after
            // the half-life decay. Harmless.
            Text("Levels", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "IN: post-EQ + gain. OUT: chain output. GR: limiter gain reduction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                LevelMeter(
                    label = "IN",
                    db = peakToDb(meterSnap.first),
                    kind = MeterKind.PEAK,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                LevelMeter(
                    label = "OUT",
                    db = peakToDb(meterSnap.second),
                    kind = MeterKind.PEAK,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                LevelMeter(
                    label = "GR",
                    db = meterSnap.third,
                    kind = MeterKind.GR,
                    modifier = Modifier.weight(1f)
                )
            }
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
                BandRow(band, sliderColors) { newGain ->
                    val newBands = bands.toMutableList()
                    newBands[idx] = band.copy(gainDb = newGain)
                    bands = newBands
                    eq.setBands(newBands)
                    persistBands(newBands)
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
private fun BandRow(band: EqBand, sliderColors: SliderColors, onChange: (Float) -> Unit) {
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
            colors = sliderColors,
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

/**
 * Press-and-hold A/B compare button. While held, the audio chain runs in
 * passthrough so the user can hear raw source vs. shaped output without
 * losing their settings. Release returns the chain to its prior state.
 *
 * **Why momentary instead of a toggle.** A toggle persists across releases
 * and the user can forget which way is which after a few flips. The
 * audiophile A/B workflow is "hold, listen, release, listen" — a momentary
 * affordance maps to it directly. Settings stay untouched (no DataStore
 * writes), so accidentally lifting a finger never strands the chain in an
 * unintended state.
 *
 * **Why detectTapGestures + awaitRelease and not Modifier.combinedClickable.**
 * `combinedClickable.onLongClick` requires holding for ~500 ms before firing
 * — wrong for our use case (we want INSTANT bypass on press-down).
 * `detectTapGestures.onPress` runs synchronously on press; awaitRelease()
 * suspends until release, and try/finally guarantees [onRelease] runs even
 * if the gesture is cancelled (drag-off, screen rotation, parent recompose).
 *
 * **Disabled state.** When the chain is already off (master toggle off, or
 * per-episode "Disable EQ" override on), pressing this button would be a
 * no-op (passthrough → passthrough). We gray it out + dim the label rather
 * than hide it, so the affordance stays discoverable on revisit.
 */
@Composable
private fun HoldToBypassButton(
    effectiveChainEnabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    val bg = when {
        !effectiveChainEnabled -> colors.surfaceVariant.copy(alpha = 0.4f)
        pressed -> colors.primary
        else -> colors.surfaceVariant
    }
    val borderAlpha = if (effectiveChainEnabled) 0.6f else 0.2f
    val labelColor = when {
        !effectiveChainEnabled -> colors.onSurface.copy(alpha = 0.4f)
        pressed -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    val labelText = when {
        !effectiveChainEnabled -> "Hold to A/B (chain already off)"
        pressed -> "BYPASSED"
        else -> "Hold to A/B"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, colors.outline.copy(alpha = borderAlpha), RoundedCornerShape(8.dp))
            // pointerInput's key list controls when the gesture handler
            // restarts. Re-key on effectiveChainEnabled so toggling the
            // master mid-press reattaches a fresh handler with the new
            // gate (in practice the user can't toggle the master while
            // holding this, but the re-key is cheap and guards the edge).
            .pointerInput(effectiveChainEnabled) {
                if (!effectiveChainEnabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        AudioChainTelemetry.logEvent("ab_bypass", "press")
                        try {
                            awaitRelease()
                        } finally {
                            // Runs on both clean release AND gesture cancel
                            // (drag-off, parent recompose). Without this, a
                            // dragged-off gesture would leave the chain in
                            // bypass forever.
                            onRelease()
                            AudioChainTelemetry.logEvent("ab_bypass", "release")
                            pressed = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            labelText,
            color = labelColor,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private enum class MeterKind { PEAK, GR }

/**
 * Single labeled bar meter — used three times in the Levels row (IN/OUT/GR).
 * Layout per meter: small label on top, bar in the middle (10 dp tall), dB
 * value on the bottom. The bar fills left→right as a fraction of the relevant
 * dB range. Color depends on [kind]: PEAK uses the primary accent; GR uses
 * the error color so active limiting visually alarms the user.
 *
 * Mapping:
 *  - PEAK: [-60, 0] dBFS → [0, 1] fill. Below -60 dB shows "-inf".
 *  - GR:   [0, -20] dB   → [0, 1] fill. 0 dB GR = empty bar (limiter idle);
 *                                       -20 dB GR = full bar (heavy attenuation).
 */
@Composable
private fun LevelMeter(
    label: String,
    db: Double,
    kind: MeterKind,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (fraction, valueText, fillColor) = when (kind) {
        MeterKind.PEAK -> {
            val frac = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            val text = if (db <= -60.0) "-inf" else "%+.1f dB".format(db)
            Triple(frac, text, colors.primary)
        }
        MeterKind.GR -> {
            val frac = ((-db) / 20.0).coerceIn(0.0, 1.0).toFloat()
            val text = "%.1f dB".format(db)
            Triple(frac, text, colors.error)
        }
    }
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(fillColor)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** Linear-amplitude peak (0..1) → dBFS, with a -60 dB floor for log10(0). */
private fun peakToDb(peak: Double): Double =
    if (peak < 1e-6) -60.0 else 20.0 * log10(peak)

/**
 * Reverse-derive which preset (if any) the EQ is currently set to by comparing
 * gain values. Preset gains are whole-integer dB (-12..+12), each exactly
 * representable as Float, so == on the gain list is safe.
 *
 * Returns (null, 0) for FLAT, (null, 0) for any custom hand-tuned curve that
 * doesn't match a known preset level. Returns (preset, levelIndex+1) otherwise.
 */
private fun derivePresetFromBands(bands: List<EqBand>): Pair<PresetId?, Int> {
    val cur = bands.map { it.gainDb }
    if (cur == EqPresets.FLAT.map { it.gainDb }) return null to 0
    for (preset in PresetId.values()) {
        for ((i, levelBands) in preset.levels.withIndex()) {
            if (cur == levelBands.map { it.gainDb }) return preset to (i + 1)
        }
    }
    return null to 0
}
