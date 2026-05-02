package com.lofipod.app.player

import android.app.Notification
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.lofipod.app.LofiPodApp
import com.lofipod.app.R

/**
 * Foreground service that runs Media3 downloads. Uses a single notification channel
 * shared by progress + completion notifications.
 */
class LofiPodDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0
) {

    companion object {
        const val CHANNEL_ID = "lofipod_downloads"
        const val FOREGROUND_NOTIFICATION_ID = 0x10F1
    }

    override fun getDownloadManager(): DownloadManager =
        (application as LofiPodApp).downloads.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        val helper = DownloadNotificationHelper(this, CHANNEL_ID)
        return helper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            /* contentIntent = */ null,
            /* message = */ null,
            downloads,
            notMetRequirements
        )
    }
}
