package com.lofipod.app.ui.screens

import androidx.compose.ui.res.painterResource
import com.lofipod.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.lofipod.app.data.LofiDownload

/**
 * Shared download-status button used by EpisodesScreen, PlayerScreen, and
 * anywhere else that surfaces per-episode download state. Four visual
 * states matching [LofiDownload.State]:
 *
 *   - null              -> Download icon (tap = start)
 *   - QUEUED/DOWNLOADING -> spinner + percent (tap = cancel)
 *   - COMPLETED         -> DownloadDone icon (tap = delete)
 *   - FAILED            -> Refresh icon, error tint (tap = retry)
 *
 * The caller decides what tap means in context — [onClick] is fired for
 * every state.
 */
@Composable
fun DownloadButton(download: LofiDownload?, onClick: () -> Unit) {
    when (download?.state) {
        null -> {
            IconButton(onClick = onClick) {
                Icon(painterResource(R.drawable.download_24), contentDescription = "Download")
            }
        }
        LofiDownload.State.QUEUED, LofiDownload.State.DOWNLOADING -> {
            // Show the percentage *next to* the spinner so progress reads even
            // when the download finishes in a couple of seconds. Without the
            // number, fast downloads look like the button just teleported to
            // the checkmark state.
            val pct = download.percent
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
                            painterResource(R.drawable.close_24),
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
        LofiDownload.State.COMPLETED -> {
            IconButton(onClick = onClick) {
                Icon(
                    painterResource(R.drawable.download_done_24),
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        LofiDownload.State.FAILED -> {
            IconButton(onClick = onClick) {
                Icon(
                    painterResource(R.drawable.refresh_24),
                    contentDescription = "Retry download",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
