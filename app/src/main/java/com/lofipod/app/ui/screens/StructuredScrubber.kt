package com.lofipod.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.PauseTapProcessor
import com.lofipod.app.bible.ScriptureTagger
import com.lofipod.app.bible.SpokenScriptureExtractor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/** Marker positioned by transcript-proportional fraction (approximate). */
data class ScrubberMarker(val frac: Float, val label: String)

/**
 * The structured scrubber (v0.11) — replaces the Player's plain Slider
 * with a taller track carrying three information layers:
 *
 *  1. REPLAY HEATMAP — per-bucket listen counts as a color overlay
 *     (sqrt-normalized so a single hotspot doesn't flatten everything
 *     else to invisible).
 *  2. PAUSE TICKS — paragraph boundaries recorded live by the PauseTap
 *     processor; ticks appear progressively behind the playhead as the
 *     decoder visits regions this session.
 *  3. SCRIPTURE MARKERS — chapter:verse landmarks from the transcript,
 *     placed by paragraph-proportional POSITION ESTIMATE (transcripts
 *     carry no timestamps; markers are landmarks, not chapter dividers —
 *     rendered small on purpose).
 *
 * Gestures: drag anywhere = scrub (seek fires once on release — per-move
 * seeks storm Media3's pipeline); tap = seek; tap within 16dp of a marker
 * = seek to the marker + show its label bubble.
 */
@Composable
fun StructuredScrubber(
    positionMs: Long,
    durationMs: Long,
    heatBuckets: IntArray?,
    pauses: List<PauseTapProcessor.PauseSpan>,
    markers: List<ScrubberMarker>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0f) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var bubbleMarker by remember { mutableStateOf<ScrubberMarker?>(null) }

    // Precomputed render inputs — recomputed only when the data changes,
    // never inside the draw lambda.
    val heatAlphas: FloatArray? = remember(heatBuckets) {
        val b = heatBuckets ?: return@remember null
        val max = b.maxOrNull() ?: 0
        if (max <= 0) return@remember null
        FloatArray(b.size) { i ->
            val c = b[i]
            if (c == 0) 0f else 0.10f + 0.45f * sqrt(c.toFloat() / max)
        }
    }
    val pauseFracs: FloatArray = remember(pauses, durationMs) {
        if (durationMs <= 0) FloatArray(0)
        else FloatArray(pauses.size) { i ->
            (pauses[i].endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
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
    val heatColor = MaterialTheme.colorScheme.primary
    val playedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
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
                .pointerInput(durationMs, markers) {
                    if (durationMs <= 0) return@pointerInput
                    awaitEachGesture {
                        // The gesture scope's own size — no dependence on
                        // the draw pass having populated widthPx yet.
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val down = awaitFirstDown()
                        val downX = down.position.x
                        val nearMarker = markers.minByOrNull { abs(it.frac * w - downX) }
                            ?.takeIf { abs(it.frac * w - downX) <= markerHitPx }
                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                            isDragging = true
                            dragFrac = (change.position.x / w).coerceIn(0f, 1f)
                        }
                        if (isDragging && slopChange != null) {
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
            // 2. Heatmap overlay.
            if (heatAlphas != null) {
                val bw = w / heatAlphas.size
                for (i in heatAlphas.indices) {
                    val a = heatAlphas[i]
                    if (a <= 0f) continue
                    drawRect(
                        color = heatColor.copy(alpha = a),
                        topLeft = Offset(i * bw, bandTop + 1f),
                        size = Size(bw + 0.5f, bandH - 2f),
                    )
                }
            }
            // 3. Played-region tint.
            val frac = dragFrac
                ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRect(
                color = playedColor,
                topLeft = Offset(0f, bandTop),
                size = Size(frac * w, bandH),
            )
            // 4. Pause ticks — lower 40% of the band.
            val tickW = 1.5.dp.toPx()
            for (i in pauseFracs.indices) {
                val x = pauseFracs[i] * w
                drawLine(
                    color = tickColor,
                    start = Offset(x, bandTop + bandH),
                    end = Offset(x, bandTop + bandH * 0.6f),
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
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(bubbleX.roundToInt(), -6) }
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
