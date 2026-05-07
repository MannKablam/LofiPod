package com.lofipod.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download

/**
 * Shared download-status button used by EpisodesScreen, PlayerScreen, and
 * anywhere else that surfaces per-episode download state. Five visual
 * states matching Media3's [Download] state machine:
 *
 *   - null              -> Download icon (tap = start)
 *   - QUEUED/DOWNLOADING/RESTARTING -> spinner + percent (tap = cancel)
 *   - COMPLETED         -> DownloadDone icon (tap = delete)
 *   - FAILED            -> Refresh icon, error tint (tap = retry)
 *   - else              -> Download icon (defensive)
 *
 * The caller decides what tap means in context — [onClick] is fired for
 * every state. See [downloadButtonAction] for the standard start-vs-remove
 * dispatch logic.
 */
@Composable
fun DownloadButton(download: Download?, onClick: () -> Unit) {
    when (download?.state) {
        null -> {
            IconButton(onClick = onClick) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> {
            // Show the percentage *next to* the spinner so progress reads even
            // when the download finishes in a couple of seconds. Without the
            // number, fast downloads look like the button just teleported to
            // the checkmark state.
            val pct = download.percentDownloaded
            val pctLabel = when {
                !pct.isFinite() -> "…"
                pct < 0f -> "…"
                else -> "${pct.toInt()}%"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClick) {
                    Box(contentAlignment = Alignment.Center) {
                        if (pct.isFinite() && pct >= 0f) {
                            CircularProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Cancel download",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    pctLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        Download.STATE_COMPLETED -> {
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Download.STATE_FAILED -> {
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> {
            IconButton(onClick = onClick) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
    }
}
