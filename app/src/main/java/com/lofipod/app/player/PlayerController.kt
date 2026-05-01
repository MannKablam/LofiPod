package com.lofipod.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeStateDao
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.model.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single point of access to the [MediaController].
 * Lifetimes: created when MainActivity starts, released when it stops.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao: EpisodeStateDao
        get() = (context.applicationContext as LofiPodApp).db.episodeStateDao()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun connect(onReady: () -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(listener)
                pushState()
            }
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushState()
        override fun onPlaybackStateChanged(playbackState: Int) = pushState()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = pushState()
    }

    private fun pushState() {
        val c = controller ?: return
        _state.value = PlayerState(
            isPlaying = c.isPlaying,
            isReady = c.playbackState == Player.STATE_READY,
            currentTitle = c.mediaMetadata.title?.toString(),
            currentArtist = c.mediaMetadata.artist?.toString(),
            currentArtworkUri = c.mediaMetadata.artworkUri?.toString(),
            currentEpisodeGuid = c.currentMediaItem?.mediaId,
            speed = c.playbackParameters.speed
        )
    }

    /**
     * Play an episode, resuming from its saved position if any.
     * Also persists the outgoing episode's position before switching.
     */
    fun playEpisode(ep: Episode, podcastTitle: String, podcastArt: String?) {
        val c = controller ?: return
        scope.launch {
            // 1. Save outgoing item's position so we can resume it later.
            val outgoingId = c.currentMediaItem?.mediaId
            if (outgoingId != null && outgoingId != ep.guid) {
                val pos = c.currentPosition
                val dur = c.duration.takeIf { it > 0 } ?: 0L
                withContext(Dispatchers.IO) {
                    if (dao.get(outgoingId) != null) {
                        dao.updatePosition(outgoingId, pos, dur, System.currentTimeMillis())
                    }
                }
            }

            // 2. Ensure a row exists for this episode + read its saved position.
            val savedPos = withContext(Dispatchers.IO) {
                val existing = dao.get(ep.guid)
                if (existing == null) {
                    dao.upsert(
                        EpisodeStateEntity(
                            guid = ep.guid,
                            feedUrl = ep.feedUrl,
                            title = ep.title,
                            audioUrl = ep.audioUrl,
                            artworkUrl = ep.episodeArtworkUrl ?: podcastArt
                        )
                    )
                    0L
                } else {
                    // If the episode is essentially finished, restart from 0.
                    val dur = existing.durationMs
                    if (dur > 0 && existing.positionMs >= dur - 5_000) 0L
                    else existing.positionMs
                }
            }

            // 3. Build & queue the item with the resume position.
            val art = ep.episodeArtworkUrl ?: podcastArt
            val item = MediaItem.Builder()
                .setMediaId(ep.guid)
                .setUri(ep.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(podcastTitle)
                        .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
            c.setMediaItem(item, savedPos)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentArtworkUri: String? = null,
    val currentEpisodeGuid: String? = null,
    val speed: Float = 1f
)
