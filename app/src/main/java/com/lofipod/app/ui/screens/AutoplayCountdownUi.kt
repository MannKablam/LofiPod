package com.lofipod.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lofipod.app.player.AutoplayTimerState
import com.lofipod.app.player.PlayerController
import kotlinx.coroutines.delay

/**
 * Read of an active autoplay-confirmation window, decimated to the visible
 * 2:10 countdown (T=60s onward). Null when no timer is active or we're still
 * in the pre-first-beep silent window — in both cases the play button keeps
 * its normal Play / Pause icon.
 */
data class AutoplayCountdownInfo(
    val remainingMs: Long,
    /** 1.0 at first beep, 0.0 at auto-pause. Drives the drainable progress ring. */
    val progress: Float,
)

/**
 * Drive a [AutoplayCountdownInfo] off [timer], recomposing every
 * [TICK_INTERVAL_MS] while the timer is in its visible window. Returns null
 * outside that window so callers can render the regular play button.
 *
 * Ticks via [SystemClock.elapsedRealtime] (monotonic, no wall-clock skew),
 * matching what `PlayerController.maybeStartAutoplayTimer` schedules against.
 */
@Composable
fun rememberAutoplayCountdown(timer: AutoplayTimerState?): AutoplayCountdownInfo? {
    if (timer == null) return null
    var now by remember(timer.startedAtElapsedMs) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(timer.startedAtElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(TICK_INTERVAL_MS)
        }
    }
    val firstBeepAt = timer.startedAtElapsedMs + PlayerController.AUTOPLAY_CONFIRM_FIRST_BEEP_MS
    if (now < firstBeepAt) return null
    val totalEnds = timer.startedAtElapsedMs + timer.totalDurationMs
    val remaining = (totalEnds - now).coerceAtLeast(0L)
    val visibleWindow = timer.totalDurationMs - PlayerController.AUTOPLAY_CONFIRM_FIRST_BEEP_MS
    val progress = (remaining.toFloat() / visibleWindow.toFloat()).coerceIn(0f, 1f)
    return AutoplayCountdownInfo(remainingMs = remaining, progress = progress)
}

/**
 * Drainable progress ring + digital `M:SS` text. Sized by [ringSize]; the
 * caller is responsible for wrapping in a clickable container that calls
 * [PlayerController.confirmAutoplayContinuation] (or, equivalently,
 * [PlayerController.togglePlay] which short-circuits to confirm during the
 * timer window).
 */
@Composable
fun AutoplayCountdownContent(
    info: AutoplayCountdownInfo,
    ringSize: Dp,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { info.progress },
            modifier = Modifier.size(ringSize),
            strokeWidth = 3.dp,
        )
        Text(text = formatCountdown(info.remainingMs), style = textStyle)
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * 250ms is fast enough that the digital `M:SS` doesn't visibly skip seconds
 * (the change is sub-frame-perceivable) and slow enough to keep recomposition
 * cost negligible — the countdown only renders for ~130s total per autoplay
 * window, gated by [rememberAutoplayCountdown] returning null outside that.
 */
private const val TICK_INTERVAL_MS = 250L
