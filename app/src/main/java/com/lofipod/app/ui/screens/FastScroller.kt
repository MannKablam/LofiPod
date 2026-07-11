package com.lofipod.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Overlay fast-scroller ("quick scroll") for a LazyColumn.
 *
 * Emit inside the same Box that holds the LazyColumn. A slim thumb hugs the
 * right edge, fading in while the list scrolls and back out after a beat of
 * idleness. Dragging the thumb jumps proportionally through the whole list;
 * while dragging, a bubble to the thumb's left shows [bubbleText] for the
 * item under the thumb (the episodes list uses the episode's mm/yy publish
 * date) so long archives can be navigated by date at a glance.
 *
 * Hidden entirely below [minItemsToShow] items — short lists scroll fine by
 * hand and the extra chrome would just be noise.
 */
@Composable
fun BoxScope.FastScroller(
    listState: LazyListState,
    itemCount: Int,
    bubbleText: (Int) -> String?,
    modifier: Modifier = Modifier,
    minItemsToShow: Int = 25,
) {
    if (itemCount < minItemsToShow) return

    val density = LocalDensity.current
    val thumbHeight = 48.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }

    var trackHeightPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    // Thumb position while idle follows the list; while dragging, the finger
    // owns it (following the list during a drag would fight the very scroll
    // the drag is issuing). Both directions map over the same denominator —
    // the count of items that can scroll PAST the top (total - visible) —
    // so track position and content agree and the thumb doesn't snap when
    // the finger lifts.
    val hiddenCount by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
        }
    }
    val listFraction by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex.toFloat() / hiddenCount).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbOffsetPx = (fraction * travelPx).roundToInt()

    val show = dragging || listState.isScrollInProgress
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = if (show) tween(120) else tween(400, delayMillis = 1200),
        label = "fastScrollerAlpha"
    )

    val dragIndex = (dragFraction * hiddenCount).roundToInt()
        .coerceIn(0, itemCount - 1)
    if (dragging) {
        LaunchedEffect(dragIndex) { listState.scrollToItem(dragIndex) }
    }

    // Touch strip: wider than the visible thumb so it's grabbable. Drag
    // handling only attaches while the thumb is visible — an invisible
    // scroller must never steal edge swipes from the list.
    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(32.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .then(
                if (alpha > 0.05f) Modifier.pointerInput(itemCount, travelPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = if (travelPx > 0f)
                                ((offset.y - thumbHeightPx / 2) / travelPx).coerceIn(0f, 1f)
                            else 0f
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            dragFraction = if (travelPx > 0f)
                                ((change.position.y - thumbHeightPx / 2) / travelPx).coerceIn(0f, 1f)
                            else 0f
                        }
                    )
                } else Modifier
            )
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .offset { IntOffset(0, thumbOffsetPx) }
                .width(6.dp)
                .height(thumbHeight)
                .alpha(alpha)
                .background(
                    color = if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(3.dp)
                )
        )
    }

    if (dragging) {
        bubbleText(dragIndex)?.let { label ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 44.dp)
                    .offset {
                        // Center the bubble (~32dp tall) on the thumb.
                        val bubbleHalf = with(density) { 16.dp.roundToPx() }
                        IntOffset(0, thumbOffsetPx + (thumbHeightPx / 2).roundToInt() - bubbleHalf)
                    }
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
