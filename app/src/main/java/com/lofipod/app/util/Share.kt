package com.lofipod.app.util

import android.content.Context
import android.content.Intent

/**
 * Send the raw audio enclosure URL out via the OS share sheet.
 * Recipients can paste this into any other podcast app or download it directly.
 */
fun Context.shareEnclosure(audioUrl: String, episodeTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, episodeTitle)
        putExtra(Intent.EXTRA_TEXT, audioUrl)
    }
    startActivity(Intent.createChooser(send, "Share episode link").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
