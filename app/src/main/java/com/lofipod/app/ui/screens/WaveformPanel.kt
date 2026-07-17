package com.lofipod.app.ui.screens

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lofipod.app.R
import com.lofipod.app.audio.EpisodeAnalysis
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Position source for the panel's playhead. A fun interface rather than a
 * plain `() -> Long` because the playhead samples this once per rendered
 * frame — a generic Function0 would box every returned Long, and sixty
 * tiny allocations a second is exactly the kind of steady-state garbage
 * the draw path is built to avoid.
 */
fun interface PlayheadPositionSource {
    fun positionMs(): Long
}

/**
 * The expanded waveform view (v0.11) — opened by holding the playback
 * bar, it trades the artwork square for a magnified look at the offline
 * analyzer's amplitude envelope ([EpisodeAnalysis.envelope]). Where the
 * scrubber compresses the whole episode into one band, this panel shows
 * a fixed-magnification window (one envelope bucket per [BucketStride]
 * of screen) that the listener can slide along the file — silences read
 * as gaps down to the midline, shouts as tall bars, so "the third quiet
 * break after this point" becomes something you can see and grab.
 *
 * Two gestures live inside the waveform, split by where the finger
 * lands:
 *
 *  - PAN — a drag that starts away from the playhead slides the window
 *    along the episode; a quick release flings it, decaying to a stop
 *    (dead stop at either end — no bounce to misread as audio).
 *    Playback is untouched; this is a viewport move, not a seek.
 *    Panning also switches off auto-follow, because a window that keeps
 *    re-centering itself while the user is trying to inspect somewhere
 *    else would fight the pan it just granted.
 *
 * ORIENTATION lives along the bottom edge: a minimap strip showing the
 * whole episode with the visible window highlighted and a playhead dot,
 * and the window's start/end timestamps in the corners — so a deep pan
 * into a two-hour sermon never loses "where am I".
 *  - PLAYHEAD DRAG — a drag that starts within a thumb's width of the
 *    playhead line picks the line up and moves it. The seek fires once
 *    on release, mirroring the scrubber's contract (per-move seeks
 *    storm Media3's pipeline; see StructuredScrubber's gesture notes).
 *    While the finger is down the window freezes so the wave doesn't
 *    slide beneath the very line being placed on it.
 *
 * AUTO-FOLLOW keeps the playhead centered while playback runs, until
 * the first pan takes the viewport away; from then on the window stays
 * where it was put and a recenter button appears in the header to snap
 * back and resume following. Close is the header's X — and the system
 * back gesture, which the host screen wires to collapse the panel
 * instead of leaving the player.
 *
 * The playhead is deliberately cheap to animate: a frame-clock loop
 * samples [positionSource] into a [mutableLongStateOf] that only the
 * Canvas draw lambda reads, so per-frame motion invalidates the draw
 * phase alone — never composition, never the rest of the player screen.
 * Everything the draw loop touches is either a primitive, a value class
 * (Offset/Size/Color), or the envelope FloatArray indexed in place;
 * no allocation happens per frame.
 *
 * A null [analysis] (scan still running, or failed — the repository
 * serves both as null) keeps the panel open with a plain "Analyzing
 * audio..." line, so the long-press always visibly lands even when the
 * envelope isn't ready yet.
 */
@Composable
fun WaveformPanel(
    analysis: EpisodeAnalysis?,
    positionSource: PlayheadPositionSource,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live playhead, sampled on the frame clock. MutableLongState writes
    // compare by value, so a paused player (position frozen) produces no
    // invalidations at all; a playing one invalidates only the draw pass
    // that reads it. The loop parks whenever the frame clock does (screen
    // off, app backgrounded), so it can't spin battery in the dark. The
    // effect is keyed on Unit and reads the source through
    // rememberUpdatedState — a caller handing over a fresh (unmemoized)
    // lambda each recomposition must not restart the loop, it should just
    // be read through.
    val currentSource by rememberUpdatedState(positionSource)
    val playheadMs = remember { mutableLongStateOf(positionSource.positionMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { playheadMs.longValue = currentSource.positionMs() }
        }
    }

    // Viewport and drag state, keyed on the analysis: a track transition
    // while the panel is open (autoplay advancing, say) lands a different
    // envelope with a different duration, and a pan offset measured in the
    // old episode's buckets would drop the new one on a meaningless spot.
    // followPlayhead gates the recenter affordance (composition) and the
    // window math (draw); panStartBucket is the manual window origin in
    // envelope buckets; dragPlayheadMs >= 0 means a playhead drag is in
    // flight (or just released) and carries the finger's position.
    var followPlayhead by remember(analysis) { mutableStateOf(true) }
    val panStartBucket = remember(analysis) { mutableFloatStateOf(0f) }
    val dragPlayheadMs = remember(analysis) { mutableLongStateOf(-1L) }
    // In-flight pan fling. Exactly one at a time: any new touch or a
    // recenter kills it. Not keyed on analysis — an orphaned decay writes
    // into the OLD episode's (discarded) pan state, which is harmless,
    // while cancelling on every touch is what actually matters for feel.
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Waveform",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!followPlayhead) {
                IconButton(onClick = {
                    // A decaying fling writing the pan while auto-follow
                    // retakes the window would fight the recenter.
                    flingJob?.cancel()
                    followPlayhead = true
                }) {
                    Icon(
                        painterResource(R.drawable.filter_center_focus_24),
                        contentDescription = "Recenter on playhead",
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.close_24),
                    contentDescription = "Close waveform",
                )
            }
        }

        val envelope = analysis?.envelope
        val durationMs = analysis?.durationMs ?: 0L
        if (envelope == null || envelope.isEmpty() || durationMs <= 0L) {
            // Scan pending or failed — the gesture still lands somewhere
            // visible, and the envelope simply appears here once the
            // analyzer's row arrives through the same analysis parameter.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Analyzing audio...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Colors resolved at composition, captured by the draw lambda —
            // theme reads never happen inside DrawScope. The played half of
            // the wave takes the primary tint at the same weight family the
            // scrubber uses; the playhead gets full primary so it reads
            // over both halves.
            val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            val midlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
            val playedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            val playheadColor = MaterialTheme.colorScheme.primary
            // Edge-timestamp machinery. The measurer's internal cache plus
            // the per-second label cache keep the draw loop's steady state
            // allocation-free: labels re-derive only when a window edge
            // crosses a second boundary, and identical (text, style) pairs
            // re-use the measurer's cached layout.
            val textMeasurer = rememberTextMeasurer()
            val timeTextStyle = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
            val timeLabels = remember { TimeLabelCache() }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(analysis) {
                        val stride = BucketStride.toPx()
                        val handleSlopPx = 24.dp.toPx()
                        val msPerBucket = durationMs.toFloat() / envelope.size
                        // Gesture-local: which of the two drags this one is,
                        // decided once at finger-down, and a float accumulator
                        // for the playhead so sub-bucket motion isn't lost to
                        // Long truncation move by move. The velocity tracker
                        // feeds the pan fling; it resets per gesture.
                        var draggingPlayhead = false
                        var dragMsF = 0f
                        val velocityTracker = VelocityTracker()
                        detectDragGestures(
                            onDragStart = { down ->
                                flingJob?.cancel()
                                velocityTracker.resetTracking()
                                val w = size.width.toFloat()
                                val windowBuckets = w / stride
                                val heldMs = dragPlayheadMs.longValue
                                val shownMs = if (heldMs >= 0) heldMs else playheadMs.longValue
                                // Clamp into the envelope's bucket range: the
                                // player's duration can exceed the analyzer's
                                // measured one (a VBR header that overstates
                                // length), which would otherwise push the
                                // playhead's handle past the last bucket and
                                // make it ungrabbable in the file's tail.
                                val playheadBucket =
                                    (shownMs / msPerBucket).coerceIn(0f, envelope.size.toFloat())
                                val start = windowStartBuckets(
                                    totalBuckets = envelope.size,
                                    windowBuckets = windowBuckets,
                                    playheadBucket = playheadBucket,
                                    centered = followPlayhead && heldMs < 0,
                                    panStart = panStartBucket.floatValue,
                                )
                                // Freeze the window where it stands for the
                                // whole gesture. For a pan this is the origin
                                // the drag offsets; for a playhead drag it
                                // stops auto-follow from sliding the wave
                                // under the finger placing the line.
                                panStartBucket.floatValue = start
                                val playheadX = (playheadBucket - start) * stride
                                // A playhead scrolled out of view can't offer
                                // its handle — the hit test fails naturally
                                // because playheadX is then off the canvas.
                                draggingPlayhead = abs(down.x - playheadX) <= handleSlopPx
                                if (draggingPlayhead) {
                                    dragMsF = shownMs.toFloat()
                                    dragPlayheadMs.longValue = shownMs
                                } else {
                                    followPlayhead = false
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                if (draggingPlayhead) {
                                    dragMsF = (dragMsF + amount.x / stride * msPerBucket)
                                        .coerceIn(0f, durationMs.toFloat())
                                    dragPlayheadMs.longValue = dragMsF.toLong()
                                } else {
                                    // Content follows the finger: dragging left
                                    // moves the window right, hence minus.
                                    val maxStart =
                                        (envelope.size - size.width.toFloat() / stride)
                                            .coerceAtLeast(0f)
                                    panStartBucket.floatValue =
                                        (panStartBucket.floatValue - amount.x / stride)
                                            .coerceIn(0f, maxStart)
                                }
                            },
                            onDragEnd = {
                                if (draggingPlayhead) {
                                    // One seek per gesture, on release — the
                                    // scrubber's contract. The controller masks
                                    // its reported position to the seek target
                                    // immediately, so clearing the drag state
                                    // right away hands the line back to the
                                    // live playhead without a visible snap.
                                    onSeek(dragPlayheadMs.longValue)
                                    dragPlayheadMs.longValue = -1L
                                    draggingPlayhead = false
                                } else {
                                    // Pan fling: carry the window with the
                                    // finger's exit velocity, decaying, clamped
                                    // to the episode's ends (hitting an edge
                                    // stops dead — no bounce to misread as
                                    // audio). Velocity is finger px/s; the pan
                                    // moves opposite the finger and in bucket
                                    // units, hence the negated stride division.
                                    val velocityX = velocityTracker.calculateVelocity().x
                                    val maxStart =
                                        (envelope.size - size.width.toFloat() / stride)
                                            .coerceAtLeast(0f)
                                    if (abs(velocityX) > MIN_FLING_VELOCITY_PX_S) {
                                        flingJob = flingScope.launch {
                                            AnimationState(
                                                initialValue = panStartBucket.floatValue,
                                                initialVelocity = -velocityX / stride,
                                            ).animateDecay(exponentialDecay(frictionMultiplier = 1.1f)) {
                                                val clamped = value.coerceIn(0f, maxStart)
                                                panStartBucket.floatValue = clamped
                                                if (value != clamped) cancelAnimation()
                                            }
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                if (draggingPlayhead) {
                                    dragPlayheadMs.longValue = -1L
                                    draggingPlayhead = false
                                }
                            },
                        )
                    }
            ) {
                val w = size.width
                val h = size.height
                val stride = BucketStride.toPx()
                val total = envelope.size
                val msPerBucket = durationMs.toFloat() / total
                val windowBuckets = w / stride
                val liveMs = playheadMs.longValue
                val heldMs = dragPlayheadMs.longValue
                val shownMs = if (heldMs >= 0) heldMs else liveMs
                // Clamp to the envelope range so the playhead pins at the end
                // instead of scrolling off the right edge and vanishing when
                // the player's duration overstates the analyzer's measured one.
                val playheadBucket = (shownMs / msPerBucket).coerceIn(0f, total.toFloat())
                val start = windowStartBuckets(
                    totalBuckets = total,
                    windowBuckets = windowBuckets,
                    playheadBucket = playheadBucket,
                    centered = followPlayhead && heldMs < 0,
                    panStart = panStartBucket.floatValue,
                )

                // Backdrop + zero line. The midline doubles as the wave's
                // silence string: zero-amplitude buckets draw nothing, so
                // real gaps in the audio read as bare midline.
                val corner = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                drawRoundRect(
                    color = backgroundColor,
                    size = Size(w, h),
                    cornerRadius = corner,
                )
                val midY = h / 2f
                drawLine(
                    color = midlineColor,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.dp.toPx(),
                )

                // Bars, mirrored around the midline, only the visible slice
                // of the envelope. The played/remaining color split follows
                // the LIVE position even mid-drag, so lifting the line
                // doesn't repaint history that hasn't been seeked yet.
                val liveBucket = liveMs / msPerBucket
                val barWidth = stride * 0.65f
                val maxHalf = h * 0.45f
                var i = max(0, floor(start).toInt())
                val lastIdx = min(total - 1, ceil(start + windowBuckets).toInt())
                while (i <= lastIdx) {
                    val half = envelope[i] * maxHalf
                    if (half > 0f) {
                        val x = (i + 0.5f - start) * stride
                        drawLine(
                            color = if (i < liveBucket) playedColor else remainingColor,
                            start = Offset(x, midY - half),
                            end = Offset(x, midY + half),
                            strokeWidth = barWidth,
                        )
                    }
                    i++
                }

                // Minimap: the whole episode as a hairline strip along the
                // bottom edge — the bright segment is the visible window,
                // the dot the live playhead. "Where am I in the file" at a
                // glance, and the pan/fling feedback that makes flying past
                // unseen territory legible. Skipped when the window already
                // shows everything (the map would just restate the canvas).
                val mapInset = 10.dp.toPx()
                val mapW = w - 2 * mapInset
                val mapY = h - 5.dp.toPx()
                if (windowBuckets < total.toFloat() && mapW > 0f) {
                    drawLine(
                        color = midlineColor,
                        start = Offset(mapInset, mapY),
                        end = Offset(mapInset + mapW, mapY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    val winX0 = mapInset + (start / total) * mapW
                    val winX1 = mapInset +
                        ((start + windowBuckets).coerceAtMost(total.toFloat()) / total) * mapW
                    drawLine(
                        color = playheadColor.copy(alpha = 0.45f),
                        start = Offset(winX0, mapY),
                        end = Offset(winX1, mapY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = playheadColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(
                            mapInset + (liveBucket / total).coerceIn(0f, 1f) * mapW,
                            mapY,
                        ),
                    )
                }

                // Edge timestamps: the window's start and end as media time,
                // tucked into the bottom corners above the minimap. Rounded
                // to whole seconds so the strings (and their cached layouts)
                // hold still between second boundaries even while
                // auto-follow slides the window every frame.
                val startSec = (start * msPerBucket / 1000f).toLong()
                val endSec = (((start + windowBuckets) * msPerBucket)
                    .coerceAtMost(durationMs.toFloat()) / 1000f).toLong()
                val startLayout = textMeasurer.measure(
                    timeLabels.labelFor(0, startSec), timeTextStyle
                )
                val endLayout = textMeasurer.measure(
                    timeLabels.labelFor(1, endSec), timeTextStyle
                )
                val labelY = mapY - 4.dp.toPx() - startLayout.size.height
                drawText(startLayout, topLeft = Offset(mapInset, labelY))
                drawText(
                    endLayout,
                    topLeft = Offset(mapInset + mapW - endLayout.size.width, labelY),
                )

                // Playhead: full-height line plus a grab tab at the top so
                // it advertises its draggability. Skipped entirely when the
                // viewport has been panned somewhere else.
                val px = (playheadBucket - start) * stride
                if (px >= -stride && px <= w + stride) {
                    drawLine(
                        color = playheadColor,
                        start = Offset(px, 0f),
                        end = Offset(px, h),
                        strokeWidth = 2.dp.toPx(),
                    )
                    val grabW = 12.dp.toPx()
                    val grabH = 16.dp.toPx()
                    drawRoundRect(
                        color = playheadColor,
                        topLeft = Offset(px - grabW / 2f, 0f),
                        size = Size(grabW, grabH),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                }
            }
        }
    }
}

/**
 * Horizontal screen distance per envelope bucket — the panel's fixed
 * magnification. Three dp keeps individual buckets resolvable (the whole
 * point of expanding) while a typical phone width still shows a window of
 * about a hundred buckets, ~5% of the episode; the envelope's own 2000-
 * bucket resolution is the zoom ceiling, so there's nothing sharper a
 * pinch could reveal and no zoom gesture is offered.
 */
private val BucketStride: Dp = 3.dp

/**
 * The one place viewport math lives — draw and gesture code both call
 * this, so a hit test can never disagree with where the frame actually
 * painted the playhead. Centered mode pins the playhead to the window's
 * middle (auto-follow); otherwise the manual pan origin rules. Both are
 * clamped so the window never runs off either end of the episode, which
 * also collapses gracefully to "show everything" when the file is
 * shorter than one window.
 */
private fun windowStartBuckets(
    totalBuckets: Int,
    windowBuckets: Float,
    playheadBucket: Float,
    centered: Boolean,
    panStart: Float,
): Float {
    val raw = if (centered) playheadBucket - windowBuckets / 2f else panStart
    return raw.coerceIn(0f, (totalBuckets - windowBuckets).coerceAtLeast(0f))
}

/** Pan-fling ignition threshold. Below this the finger was placing the
 *  window, not throwing it — matching the platform's own feel, where a
 *  slow release parks exactly where it lifted. */
private const val MIN_FLING_VELOCITY_PX_S = 200f

/**
 * Per-slot label cache for the edge timestamps. The window edges' labels
 * change once per second at most, but the draw loop runs every frame —
 * re-deriving the strings each frame would be sixty allocations a second
 * for identical text. Slot 0 = window start, slot 1 = window end.
 */
private class TimeLabelCache {
    private val secs = longArrayOf(-1L, -1L)
    private val texts = arrayOf("", "")
    fun labelFor(slot: Int, sec: Long): String {
        if (secs[slot] != sec) {
            secs[slot] = sec
            texts[slot] = formatPanelTime(sec)
        }
        return texts[slot]
    }
}

private fun formatPanelTime(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
