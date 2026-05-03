package com.lofipod.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lofipod.app.LofiPodApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic backup writer. Always writes to the SAME filename
 * `lofipod-backup-latest.json` inside the user-picked tree URI, so each run
 * overwrites the previous one — single retained file by design (the user
 * already has the manual Export in Metrics if they want a dated copy).
 *
 * Failure modes that lead to Result.retry():
 *  - The picked tree URI is no longer accessible (e.g. removable storage
 *    detached). WorkManager will retry with backoff, and the user sees the
 *    "Last backed up" timestamp staying stale.
 *
 * Failure modes that lead to Result.failure():
 *  - No backup folder configured. The worker shouldn't have been scheduled
 *    in this state, but we tolerate it defensively.
 *
 * Schedule frequency comes from Settings.backupIntervalHours; the scheduler
 * helper translates that into a PeriodicWorkRequest.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as LofiPodApp
        val settings = Settings(applicationContext)
        val treeUriString = settings.backupTreeUri.first()
            ?: return@withContext Result.failure()
        val treeUri = Uri.parse(treeUriString)

        try {
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: return@withContext Result.retry()

            val episodes = app.db.episodeStateDao().getAll()
            val notes = app.db.episodeNoteEntryDao().getAll()
            val checkpoints = app.db.playbackCheckpointDao().getAll()
            val podcastStates = app.db.podcastStateDao().getAll()
            val pkg = applicationContext.packageManager.getPackageInfo(
                applicationContext.packageName, 0
            )
            val json = Backup.export(
                episodes, notes, checkpoints, podcastStates, pkg.versionName ?: "?"
            )

            // Single retained file: delete the existing one, then create fresh.
            // DocumentFile doesn't expose an in-place truncate, so this two-step
            // is the cleanest "overwrite" we can do via SAF.
            root.findFile(BACKUP_FILENAME)?.delete()
            val out = root.createFile("application/json", BACKUP_FILENAME)
                ?: return@withContext Result.retry()

            applicationContext.contentResolver.openOutputStream(out.uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.retry()

            settings.setBackupLastSuccessAt(System.currentTimeMillis())
            Result.success()
        } catch (e: SecurityException) {
            // Tree permission was revoked or expired (rare but possible after
            // device-wide Storage permission changes). User has to re-pick the
            // folder. Failing surfaces this in the WorkManager status.
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "lofipod-auto-backup"
        const val BACKUP_FILENAME = "lofipod-backup-latest.json"

        /**
         * (Re)schedule the periodic job from [intervalHours]. 0 cancels.
         * Replaces the existing schedule (KEEP would let an old interval
         * keep running after the user changed it).
         */
        fun schedule(context: Context, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            // WorkManager periodic minimum is 15 minutes; hour-based intervals
            // are well above that. Battery-not-low constraint avoids us
            // running while the user is in a power-conservation moment.
            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
