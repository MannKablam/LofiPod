package com.lofipod.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lofipod.app.data.Settings
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Periodic update check. Runs every 24 hours; the [schedule] helper aligns
 * the first run to next 23:59 local. On a hit (newer release), surfaces a
 * notification — tapping it launches the system installer for the
 * already-downloaded APK. The actual install dialog is the user's call.
 *
 * Failure modes route to Result.retry() so WorkManager backs off and tries
 * again. We don't return Result.failure() except on shape mismatches
 * (manifest schema changed beyond what this build understands), which is
 * very rare in practice.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val settings = Settings(applicationContext)
        // Bail early if the user has turned auto-check off — they can still
        // manually run from Settings, but we shouldn't spend their battery.
        if (!settings.updateAutoCheckEnabled.first()) {
            return androidx.work.ListenableWorker.Result.success()
        }
        val checker = UpdateChecker(applicationContext)
        val outcome = checker.checkAndDownload()
        return when (outcome) {
            is UpdateChecker.Result.UpToDate -> androidx.work.ListenableWorker.Result.success()
            is UpdateChecker.Result.Failed -> androidx.work.ListenableWorker.Result.retry()
            is UpdateChecker.Result.Updated -> {
                postNotification(applicationContext, outcome)
                androidx.work.ListenableWorker.Result.success()
            }
        }
    }

    companion object {
        const val WORK_NAME = "lofipod-update-check"
        const val NOTIF_CHANNEL_ID = "lofipod-updates"
        const val NOTIF_ID = 4711

        /**
         * (Re)schedule the daily 23:59 local-time check. Initial delay is
         * computed from "now" so the first run lands at next 23:59;
         * subsequent runs are 24h apart.
         *
         * Pass `enabled = false` to cancel — used when the user toggles
         * auto-check off in Settings.
         */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val now = System.currentTimeMillis()
            val target = nextTwentyThreeFiftyNine(now)
            val initialDelay = (target - now).coerceAtLeast(0L)

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            // UPDATE policy: re-aligning the schedule (e.g. on app launch)
            // resets the periodic clock. Deliberate — without this the
            // first-launch initial-delay never gets a chance to recompute
            // against the user's current local time.
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Epoch ms of the next 23:59:00 local-time tick after [nowMs]. If
         * we're already past 23:59 today, returns 23:59 *tomorrow*.
         */
        internal fun nextTwentyThreeFiftyNine(nowMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        /**
         * Post (or update) the "update ready" notification. Tap action
         * fires the system installer through a FileProvider URI. Channel
         * is created on demand — first call only does the work.
         */
        private fun postNotification(
            context: Context,
            updated: UpdateChecker.Result.Updated,
        ) {
            ensureChannel(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                updated.apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                installIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LofiPod update ready")
                .setContentText("Tap to install ${updated.versionName}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Version ${updated.versionName} (build ${updated.versionCode}) is ready to install. " +
                        "You'll see the system installer dialog — tap Install to apply."
                ))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notif)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tap when a new LofiPod version is ready to install."
            }
            nm.createNotificationChannel(channel)
        }
    }
}
