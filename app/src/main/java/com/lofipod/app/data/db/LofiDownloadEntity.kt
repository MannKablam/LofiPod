package com.lofipod.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistence for the new OkHttp-based downloader. Replaces Media3's
 * StandaloneDatabaseProvider + DownloadIndex SQLite db with a single Room
 * row per episode-download. The file on disk lives at [filePath], which is
 * an absolute path under the app's private files dir.
 *
 * State integers map 1:1 to [com.lofipod.app.data.LofiDownload.State]:
 *   - 0 QUEUED
 *   - 1 DOWNLOADING
 *   - 2 COMPLETED
 *   - 3 FAILED
 *
 * Wide rows on purpose — we want the persisted state to be self-contained
 * enough to rebuild the UI without re-querying anything else after process
 * restart. [audioUrl] sticks around because cold-start resume needs to know
 * where to fetch from.
 */
@Entity(tableName = "lofi_download")
data class LofiDownloadEntity(
    @PrimaryKey val guid: String,
    val audioUrl: String,
    val mimeType: String?,
    val filePath: String,
    val state: Int,
    val contentLength: Long,
    val bytesDownloaded: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
