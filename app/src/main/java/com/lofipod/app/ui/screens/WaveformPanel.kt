package com.lofipod.app.ui.screens

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lofipod.app.R
import com.lofipod.app.audio.EpisodeAnalysis
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
 *    along the episode. Playback is untouched; this is a viewport move,
 *    not a seek. Panning also switches off auto-follow, because a
 *    window that keeps re-centering itself while the user is trying to
 *    inspect somewhere else would fight the pan it just granted.
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
                IconButton(onClick = { followPlayhead = true }) {
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
                        // Long truncation move by move.
                        var draggingPlayhead = false
                        var dragMsF = 0f
                        detectDragGestures(
                            onDragStart = { down ->
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
