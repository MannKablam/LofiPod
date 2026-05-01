package com.lofipod.app.data

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.lofipod.app.data.model.Episode
import com.lofipod.app.player.LofiPodDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Media3's [DownloadManager] that exposes a [StateFlow] of
 * downloads keyed by episode GUID. UI can collect this to render per-episode state.
 */
class Downloads(
    private val context: Context,
    private val manager: DownloadManager
) {

    private val _byId = MutableStateFlow<Map<String, Download>>(emptyMap())
    val byId: StateFlow<Map<String, Download>> = _byId.asStateFlow()

    init {
        // Seed with whatever Media3 already knows about (e.g. after process restart).
        refreshAll()
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                _byId.value = _byId.value.toMutableMap().apply {
                    put(download.request.id, download)
                }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _byId.value = _byId.value.toMutableMap().apply {
                    remove(download.request.id)
                }
            }

            override fun onInitialized(downloadManager: DownloadManager) {
                refreshAll()
            }
        })
    }

    private fun refreshAll() {
        val cursor = manager.downloadIndex.getDownloads()
        val map = mutableMapOf<String, Download>()
        cursor.use {
            while (it.moveToNext()) {
                val d = it.download
                map[d.request.id] = d
            }
        }
        _byId.value = map
    }

    fun start(ep: Episode) {
        val request = DownloadRequest.Builder(ep.guid, Uri.parse(ep.audioUrl))
            .setMimeType(ep.audioMimeType ?: "audio/mpeg")
            .build()
        DownloadService.sendAddDownload(
            context,
            LofiPodDownloadService::class.java,
            request,
            /* foreground = */ false
        )
    }

    fun remove(episodeGuid: String) {
        DownloadService.sendRemoveDownload(
            context,
            LofiPodDownloadService::class.java,
            episodeGuid,
            /* foreground = */ false
        )
    }
}
