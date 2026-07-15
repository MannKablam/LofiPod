package com.lofipod.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.EpisodeAnalysis
import com.lofipod.app.audio.PauseTapProcessor
import com.lofipod.app.bible.ScriptureTagger
import com.lofipod.app.bible.SpokenScriptureExtractor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Marker positioned by transcript-proportional fraction (approximate). */
data class ScrubberMarker(val frac: Float, val label: String)

/**
 * The structured scrubber (v0.11) — replaces the Player's plain Slider
 * with a taller track carrying three information layers:
 *
 *  1. REPLAY HEATMAP — where the listener spent time BEYOND one pass,
 *     as a classic cold-to-hot temperature ramp (deep blue -> green ->
 *     yellow -> orange -> red) painted as one continuous gradient along
 *     the band. Bucket counts are smoothed and measured against the
 *     single-pass baseline before painting, so a plain front-to-back
 *     listen leaves the track bare — only re-listened ranges heat up,
 *     and the episode's hottest range is always full red (per-episode
 *     normalization). See [buildHeatStops] for the statistics.
 *  2. PAUSE CUT MARKS — audible-silence boundaries as thin neutral
 *     ticks that overshoot the band top and bottom, like cut points on
 *     an editing timeline. Sourced from the offline analyzer's scan
 *     when one exists (whole file, present the moment the screen
 *     opens); otherwise from the live PauseTap processor, which only
 *     knows regions the decoder visited this session.
 *  3. SCRIPTURE MARKERS — chapter:verse landmarks from the transcript,
 *     placed by paragraph-proportional POSITION ESTIMATE (transcripts
 *     carry no timestamps; markers are landmarks, not chapter dividers —
 *     rendered small on purpose).
 *
 * Gestures: drag anywhere = scrub (seek fires once on release — per-move
 * seeks storm Media3's pipeline); tap = seek; tap within 16dp of a marker
 * = seek to the marker + show its label bubble; press-and-hold (when the
 * host provides [onExpandWaveform]) = open the expanded waveform panel,
 * with the rest of that gesture swallowed so the hold neither seeks nor
 * disturbs playback.
 */
@Composable
fun StructuredScrubber(
    positionMs: Long,
    durationMs: Long,
    heatBuckets: IntArray?,
    pauses: List<PauseTapProcessor.PauseSpan>,
    analysis: EpisodeAnalysis?,
    markers: List<ScrubberMarker>,
    onSeek: (Long) -> Unit,
    onExpandWaveform: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0f) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var bubbleMarker by remember { mutableStateOf<ScrubberMarker?>(null) }

    // Precomputed render inputs — recomputed only when the data changes,
    // never inside the draw lambda.
    //
    // Heat is ONE horizontal-gradient brush with a color stop per bucket:
    // Skia interpolates between neighbouring stops, so the ramp reads as
    // a continuous temperature field rather than 200 abutting rectangles.
    val heatBrush: Brush? = remember(heatBuckets) {
        heatBuckets
            ?.let { buildHeatStops(it) }
            ?.let { Brush.horizontalGradient(*it) }
    }
    // Cut-mark positions. The offline analyzer, once it has scanned this
    // episode, supplies the spans — it saw the WHOLE file, not just the
    // regions the decoder visited this session (the live tap fallback).
    // But every span is mapped against the PLAYER's duration, the same
    // denominator the thumb (positionMs / durationMs) and the seek math
    // use: a pause's endMs and ExoPlayer's currentPosition are both
    // decoded media time, so dividing both by the player's duration is
    // what keeps a tick sitting exactly under the playhead when its
    // silence is audible. Dividing the scanned spans by the analyzer's
    // own measured duration instead would drift the ticks off the
    // playhead by the two durations' ratio whenever they disagree (a VBR
    // file whose header the player trusts, say).
    val pauseFracs: FloatArray = remember(analysis, pauses, durationMs) {
        val spans = analysis?.pauses?.takeIf { analysis.durationMs > 0 } ?: pauses
        if (durationMs > 0) FloatArray(spans.size) { i ->
            (spans[i].endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else FloatArray(0)
    }

    // Hold the drag fraction through one position-poll cycle after release
    // so the thumb doesn't snap back while Media3 completes the seek.
    // Gated on !isDragging: playback keeps running during a drag (seek only
    // fires on release), so positionMs keeps updating — clearing here
    // mid-drag would snap the thumb to the playhead twice a second.
    LaunchedEffect(positionMs) {
        if (!isDragging && dragFrac != null) dragFrac = null
    }
    LaunchedEffect(bubbleMarker) {
        if (bubbleMarker != null) {
            delay(1800)
            bubbleMarker = null
        }
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val playedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    // Cut marks stay neutral (plain onSurface, no hue) so they read as
    // structure against any temperature the heat ramp paints beneath them.
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val markerColor = MaterialTheme.colorScheme.tertiary
    val thumbColor = MaterialTheme.colorScheme.primary

    val markerHitPx = with(density) { 16.dp.toPx() }

    Box(
        modifier = modifier.height(48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                // Keyed on the waveform callback's PRESENCE (not identity):
                // the handler branches on null-vs-provided, and a host that
                // stops offering the panel must drop the long-press race —
                // but recomposition minting a fresh lambda instance is no
                // reason to tear down an in-flight gesture.
                .pointerInput(durationMs, markers, onExpandWaveform == null) {
                    if (durationMs <= 0) return@pointerInput
                    // A key change — durationMs on a track transition, markers
                    // when the transcript scan lands moments after the screen
                    // opens — restarts this block and cancels any gesture in
                    // flight at its suspension point, skipping every per-gesture
                    // reset path below. If that lands mid-drag, isDragging /
                    // dragFrac (unkeyed remember) stay stuck: the thumb freezes
                    // at the abandoned fraction (the position poll won't clear
                    // it while isDragging is true) and the next tap is swallowed
                    // by the cancelled-gesture branch. Clear the shared drag
                    // state up front so a restarted handler always starts clean.
                    if (isDragging) {
                        isDragging = false
                        dragFrac = null
                    }
                    awaitEachGesture {
                        // The gesture scope's own size — no dependence on
                        // the draw pass having populated widthPx yet.
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val down = awaitFirstDown()
                        val downX = down.position.x
                        val nearMarker = markers.minByOrNull { abs(it.frac * w - downX) }
                            ?.takeIf { abs(it.frac * w - downX) <= markerHitPx }
                        val onSlop = { change: PointerInputChange, _: Offset ->
                            change.consume()
                            isDragging = true
                            dragFrac = (change.position.x / w).coerceIn(0f, 1f)
                        }
                        // When the host offers an expanded waveform, slop
                        // detection races the long-press clock. Three ways
                        // out: the finger travels (drag-to-scrub, below),
                        // lifts inside the window (tap-to-seek — slopChange
                        // comes back null WITHOUT the timeout firing), or
                        // holds still past the timeout — the hold. Without a
                        // waveform to open there's no race; a slow-starting
                        // drag keeps scrubbing exactly as before.
                        var heldStill = false
                        val slopChange = if (onExpandWaveform == null) {
                            awaitTouchSlopOrCancellation(down.id, onSlop)
                        } else try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                awaitTouchSlopOrCancellation(down.id, onSlop)
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            heldStill = true
                            null
                        }
                        if (heldStill) {
                            // The hold: expand the panel, then swallow the
                            // remainder of this gesture — a hold must never
                            // read as a seek, and playback keeps running.
                            onExpandWaveform?.invoke()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        } else if (isDragging && slopChange != null) {
                            drag(slopChange.id) { change ->
                                change.consume()
                                dragFrac = (change.position.x / w).coerceIn(0f, 1f)
                            }
                            dragFrac?.let { onSeek((it * durationMs).toLong()) }
                            isDragging = false
                        } else if (!isDragging) {
                            // Tap. Marker wins within its hit radius.
                            if (nearMarker != null) {
                                bubbleMarker = nearMarker
                                onSeek((nearMarker.frac * durationMs).toLong())
                            } else {
                                onSeek(((downX / w).coerceIn(0f, 1f) * durationMs).toLong())
                            }
                        } else {
                            // Slop consumed but gesture cancelled — release
                            // the drag state so the thumb re-follows playback.
                            isDragging = false
                            dragFrac = null
                        }
                    }
                }
        ) {
            widthPx = size.width
            val w = size.width
            val bandH = 30.dp.toPx()
            val bandTop = (size.height - bandH) / 2f
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            // 1. Track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, bandTop),
                size = Size(w, bandH),
                cornerRadius = corner,
            )
            // 2. Replay heat — a single gradient fill over the track;
            // stops carry alpha 0 where nothing was re-listened, so the
            // bare track shows through untouched there.
            if (heatBrush != null) {
                drawRoundRect(
                    brush = heatBrush,
                    topLeft = Offset(0f, bandTop),
                    size = Size(w, bandH),
                    cornerRadius = corner,
                )
            }
            // 3. Played-region tint.
            val frac = dragFrac
                ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRect(
                color = playedColor,
                topLeft = Offset(0f, bandTop),
                size = Size(frac * w, bandH),
            )
            // 4. Pause cut marks — thin full-height ticks overshooting
            // the band a little on both sides, the way cut points sit on
            // a video-editing timeline. The overshoot is what separates
            // them from the in-band layers at a glance.
            val tickW = 1.5.dp.toPx()
            val tickOver = 3.dp.toPx()
            for (i in pauseFracs.indices) {
                val x = pauseFracs[i] * w
                drawLine(
                    color = tickColor,
                    start = Offset(x, bandTop - tickOver),
                    end = Offset(x, bandTop + bandH + tickOver),
                    strokeWidth = tickW,
                )
            }
            // 5. Scripture markers — hairline + small dot on the top edge.
            for (m in markers) {
                val x = m.frac * w
                drawLine(
                    color = markerColor.copy(alpha = 0.6f),
                    start = Offset(x, bandTop),
                    end = Offset(x, bandTop + bandH),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(
                    color = markerColor,
                    radius = 3.dp.toPx(),
                    center = Offset(x, bandTop),
                )
            }
            // 6. Thumb.
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset((frac * w - 2.dp.toPx()).coerceAtLeast(0f), bandTop - 3.dp.toPx()),
                size = Size(4.dp.toPx(), bandH + 6.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }

        bubbleMarker?.let { m ->
            val bubbleX = with(density) {
                (m.frac * widthPx - 24.dp.toPx()).coerceIn(0f, (widthPx - 56.dp.toPx()).coerceAtLeast(0f))
            }
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 3.dp,
                // bubbleX is a physical, left-origin canvas coordinate (the
                // scrubber draws and hit-tests LTR unconditionally), so it is
                // pinned with the direction-agnostic AbsoluteAlignment.TopLeft
                // + absoluteOffset — plain TopStart / offset would mirror the
                // bubble to the wrong end of the bar under an RTL layout
                // direction. The vertical lift goes through dp so it stays a
                // real ~6dp instead of shrinking to a fraction of itself at
                // high density.
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset(bubbleX.roundToInt(), -6.dp.roundToPx()) }
            ) {
                Text(
                    m.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Build scripture markers for [guid] from its CACHED transcript — never
 * fetches (transcripts arrive when the user opens the Transcript tab; the
 * scrubber lights up from then on). Written citations come from
 * [ScriptureTagger.findAllRefs]; spoken "chapter N verse M" phrasing from
 * [SpokenScriptureExtractor], which only runs when the episode's book is
 * known (kabod metadata or the scripture tagger's stored row).
 *
 * Positions are paragraph-proportional ESTIMATES — the transcript has no
 * timestamps. Markers are landmarks, not chapter dividers.
 */
suspend fun buildScriptureMarkers(app: LofiPodApp, guid: String): List<ScrubberMarker> = try {
    buildScriptureMarkersUnsafe(app, guid)
} catch (_: Exception) {
    // Markers are decoration; a pathological transcript (regex blowup,
    // malformed JSON, DB hiccup) must degrade to "no markers", never
    // crash the player.
    emptyList()
}

private suspend fun buildScriptureMarkersUnsafe(app: LofiPodApp, guid: String): List<ScrubberMarker> {
    val row = app.db.episodeTranscriptDao().get(guid) ?: return emptyList()
    val paragraphs = try {
        val arr = org.json.JSONArray(row.paragraphsJson)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        return emptyList()
    }
    if (paragraphs.isEmpty()) return emptyList()

    val kabod = app.db.episodeKabodDao().get(guid)
    val bookName = kabod?.scriptureBook ?: app.db.episodeScriptureDao().get(guid)?.book

    data class Raw(val pIdx: Int, val book: String?, val ch: Int, val v: Int?)

    val raw = mutableListOf<Raw>()
    paragraphs.forEachIndexed { i, p ->
        for (ref in ScriptureTagger.findAllRefs(p)) {
            val ch = ref.startCh ?: continue
            raw.add(Raw(i, ref.book, ch, ref.startV))
        }
    }
    if (bookName != null) {
        for (s in SpokenScriptureExtractor.extract(paragraphs, kabod?.scriptureStartCh)) {
            raw.add(Raw(s.paragraphIndex, bookName, s.chapter, s.verse))
        }
    }
    // When the episode's book is known, keep only its own refs — cross-
    // references ("compare Hebrews 9") are exposition, not navigation.
    val scoped = if (bookName != null) raw.filter { it.book == bookName } else raw
    if (scoped.isEmpty()) return emptyList()

    val sorted = scoped.sortedWith(compareBy({ it.pIdx }, { it.ch }, { it.v ?: 0 }))
    val deduped = mutableListOf<Raw>()
    for (r in sorted) {
        val last = deduped.lastOrNull()
        if (last == null || last.ch != r.ch || last.v != r.v) deduped.add(r)
    }
    // Cap for legibility: verse-by-verse expositions can name 100+ refs.
    val capped = if (deduped.size <= 40) deduped
    else List(40) { i -> deduped[i * deduped.size / 40] }

    return capped.map { r ->
        val label = if (bookName != null) {
            if (r.v != null) "${r.ch}:${r.v}" else "${r.ch}"
        } else {
            val b = (r.book ?: "").take(3)
            (if (r.v != null) "$b ${r.ch}:${r.v}" else "$b ${r.ch}").trim()
        }
        ScrubberMarker(
            frac = ((r.pIdx + 0.5f) / paragraphs.size).coerceIn(0f, 1f),
            label = label,
        )
    }
}

/**
 * Heat-ramp anchors, coldest to hottest. Fixed colors rather than theme
 * roles on purpose: blue-means-cool through red-means-hot is a convention
 * the theme must not restyle, and these mid-weight tones hold up against
 * both the light and dark surfaceVariant track.
 */
private val HeatBlue = Color(0xFF0D47A1)
private val HeatGreen = Color(0xFF2E7D32)
private val HeatYellow = Color(0xFFF9A825)
private val HeatOrange = Color(0xFFEF6C00)
private val HeatRed = Color(0xFFD32F2F)

/** Ceiling opacity of the heat overlay — hot regions should dominate the
 *  band without completely hiding the played-region tint above them. */
private const val HEAT_MAX_ALPHA = 0.85f

/**
 * Map a normalized replay intensity (0 = baseline, 1 = the episode's
 * hottest bucket) onto the cold-to-hot ramp. Alpha fades in from zero
 * across the coolest stretch so a region barely past the baseline tints
 * the track rather than painting over it — the gradient then carries
 * that fade spatially, giving every hotspot soft edges.
 */
private fun heatRampColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    val hue = when {
        x < 0.30f -> lerp(HeatBlue, HeatGreen, x / 0.30f)
        x < 0.55f -> lerp(HeatGreen, HeatYellow, (x - 0.30f) / 0.25f)
        x < 0.78f -> lerp(HeatYellow, HeatOrange, (x - 0.55f) / 0.23f)
        else -> lerp(HeatOrange, HeatRed, (x - 0.78f) / 0.22f)
    }
    return hue.copy(alpha = (x / 0.18f).coerceAtMost(1f) * HEAT_MAX_ALPHA)
}

/**
 * Turn raw listen-tick buckets into gradient color stops, or null when
 * the episode carries no replay signal worth painting.
 *
 * The buckets are noisy BY CONSTRUCTION: heat accrues one count per 10s
 * wall-clock save tick (PlaybackService's cadence), but a bucket spans
 * the episode's length over 200 — so even a single front-to-back listen deposits
 * alternating 1s and 2s (or 1s and 0s at higher speeds and on short
 * episodes) as the tick interval beats against the bucket width.
 * Rendered directly, that beat looks like stripes. Three steps turn it
 * into a temperature field:
 *
 *  1. SMOOTH — a small triangular kernel (radius 2, ~1% of the bar)
 *     averages the beat away without blurring genuine replay bumps,
 *     which span tens of buckets.
 *  2. SUBTRACT THE SINGLE PASS — the ramp answers "where did I spend
 *     time BEYOND one listen", so one full pass must render as zero.
 *     The single-pass tick rate is the MEDIAN of the visited buckets:
 *     for any normal listen the majority of visited buckets were visited
 *     exactly once, so their middle value IS the one-pass rate — and it
 *     self-calibrates to whatever speed was used. Heat accrues one tick
 *     per 10s of WALL clock, so a bucket collects half as many ticks at
 *     2x and nearly twice as many at 0.5x; the median tracks all of it,
 *     where a fixed theoretical-1x estimate would mislabel a slow single
 *     pass as a replay and paint the whole bar hot. A quarter-baseline
 *     dead band on top swallows whatever ripple the kernel couldn't.
 *  3. NORMALIZE PER EPISODE — the hottest surviving bucket becomes full
 *     red; everything else ramps proportionally.
 *
 * The raw-max-below-2 guard rejects episodes that were merely skimmed:
 * scattered single ticks from tapping around are visits, not replays,
 * while a genuine replay always lands two-plus ticks somewhere in its
 * range at any playback speed.
 */
private fun buildHeatStops(buckets: IntArray): Array<Pair<Float, Color>>? {
    val n = buckets.size
    if (n < 2) return null
    if ((buckets.maxOrNull() ?: 0) < 2) return null

    val radius = 2
    val smoothed = FloatArray(n)
    for (i in 0 until n) {
        var acc = 0f
        var weight = 0f
        for (d in -radius..radius) {
            val j = i + d
            if (j < 0 || j >= n) continue
            val wgt = (radius + 1 - abs(d)).toFloat()
            acc += buckets[j] * wgt
            weight += wgt
        }
        smoothed[i] = acc / weight
    }

    val visited = smoothed.filter { it > 0f }.sorted()
    if (visited.isEmpty()) return null
    // Median of the visited buckets is the single-pass rate at whatever
    // speed the listener used (see the doc above). No fixed 1x cap: that
    // only ever equals the median at >=1x and wrongly undercuts it below
    // 1x, which is exactly what painted a slow single pass as replay.
    val baseline = visited[visited.size / 2]

    val deadBand = baseline * 0.25f
    val excess = FloatArray(n) { (smoothed[it] - baseline - deadBand).coerceAtLeast(0f) }
    val maxExcess = excess.maxOrNull() ?: 0f
    if (maxExcess <= 0f) return null

    return Array(n) { i -> (i + 0.5f) / n to heatRampColor(excess[i] / maxExcess) }
}
