package com.lofipod.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Use real podcast artwork when the URL is non-null/non-blank. Fall back to a
 * direction-flavored placeholder only when no artwork exists. This is the
 * "artwork over placeholder" rule from the theme work — every direction has its
 * own placeholder graphic, but real art always wins.
 */
@Composable
fun ThemedArtwork(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp,
) {
    if (!artworkUrl.isNullOrBlank()) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        DirectionPlaceholder(modifier = modifier.size(size))
    }
}

@Composable
private fun DirectionPlaceholder(modifier: Modifier = Modifier) {
    val spec = lofiTheme
    when (spec.kind) {
        LofiThemeSpec.Kind.Cassette -> CassettePlaceholder(modifier, spec)
        LofiThemeSpec.Kind.Reel     -> ReelPlaceholder(modifier, spec)
        LofiThemeSpec.Kind.Ticker   -> TickerPlaceholder(modifier, spec)
    }
}

// Cassette: filled square with a pair of tape-spool circles, amber-on-navy.
@Composable
private fun CassettePlaceholder(modifier: Modifier, spec: LofiThemeSpec) {
    Box(
        modifier = modifier.background(spec.artworkPlaceholderFill),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val r = size.minDimension * 0.18f
            val y = size.height / 2f
            drawCircle(
                color = spec.artworkPlaceholderInk,
                radius = r,
                center = Offset(size.width * 0.30f, y),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = spec.artworkPlaceholderInk,
                radius = r,
                center = Offset(size.width * 0.70f, y),
                style = Stroke(width = 3f)
            )
        }
    }
}

// Reel-to-Reel: cream square with a spoke-3 reel circle.
@Composable
private fun ReelPlaceholder(modifier: Modifier, spec: LofiThemeSpec) {
    Box(
        modifier = modifier
            .background(spec.artworkPlaceholderFill)
            .border(2.dp, spec.artworkPlaceholderInk),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.42f
            drawCircle(
                color = spec.artworkPlaceholderInk,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = spec.artworkPlaceholderInk,
                radius = r * 0.18f,
                center = Offset(cx, cy)
            )
            // Three spokes at 0/120/240
            val spokes = listOf(0.0, 2 * Math.PI / 3, 4 * Math.PI / 3)
            for (a in spokes) {
                val ex = cx + (r * 0.85f * kotlin.math.cos(a)).toFloat()
                val ey = cy + (r * 0.85f * kotlin.math.sin(a)).toFloat()
                drawLine(
                    color = spec.artworkPlaceholderInk,
                    start = Offset(cx, cy),
                    end = Offset(ex, ey),
                    strokeWidth = 3f
                )
            }
        }
    }
}

// Ticker: paper square with monospace `[ EP ]` label, like a print stamp.
@Composable
private fun TickerPlaceholder(modifier: Modifier, spec: LofiThemeSpec) {
    Box(
        modifier = modifier
            .background(spec.artworkPlaceholderFill)
            .border(1.dp, spec.artworkPlaceholderInk),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "[ EP ]",
            color = spec.artworkPlaceholderInk,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}
