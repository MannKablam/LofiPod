package com.lofipod.app.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Citation header for a note: `2026-05-01 14:23 UTC · 00:14:23`. UTC for the wall-clock
 * instant the entry was logged, then the playback position at that moment.
 */
internal fun citationOf(createdAtMs: Long, playbackPosMs: Long): String {
    val utc = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(createdAtMs))
    val pos = formatPlaybackPos(playbackPosMs)
    return "$utc · $pos"
}

internal fun formatPlaybackPos(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
