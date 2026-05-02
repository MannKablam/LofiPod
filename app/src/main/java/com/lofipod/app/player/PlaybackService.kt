package com.lofipod.app.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.EqAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background media service. Hosts the ExoPlayer instance + MediaSession.
 *
 * The EQ processor is held as a singleton on this service so the UI can mutate
 * its parameters in real time via the binder.
 *
 * Persists playback position to Room: ticks every 10 seconds while playing,
 * and saves immediately on pause / stop / task removal / destroy.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveTickerJob: Job? = null

    companion object {
        // Shared EQ instance — UI can grab it via app-level holder
        val sharedEq = EqAudioProcessor()
        private const val SAVE_INTERVAL_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        // Cache-aware media source factory: downloaded episodes play locally,
        // streamed episodes still hit HTTP (with opportunistic range caching).
        val cacheFactory = (application as LofiPodApp).downloads.cacheDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(cacheFactory)

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, sharedEq))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startSaveTicker(player)
                } else {
                    stopSaveTicker()
                    saveCurrent(player, listenDelta = 0L)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun startSaveTicker(player: Player) {
        stopSaveTicker()
        saveTickerJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                // The user has just listened for one tick interval — add it to the
                // cumulative total along with the position update.
                saveCurrent(player, listenDelta = SAVE_INTERVAL_MS)
            }
        }
    }

    private fun stopSaveTicker() {
        saveTickerJob?.cancel()
        saveTickerJob = null
    }

    /**
     * Snapshot the current player position on the main thread, then write to Room on IO.
     * Only updates rows that already exist — PlayerController creates the row on first play.
     * [listenDelta] is added to cumulativeListenMs (use SAVE_INTERVAL_MS for the periodic
     * tick, 0 for save-on-pause / save-on-destroy where no real time has elapsed).
     */
    private fun saveCurrent(player: Player, listenDelta: Long) {
        val id = player.currentMediaItem?.mediaId ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val dao = (application as LofiPodApp).db.episodeStateDao()
            if (dao.get(id) != null) {
                dao.updatePosition(id, pos, dur, now, listenDelta)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            saveCurrent(player, listenDelta = 0L)
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        mediaSession?.player?.let { saveCurrent(it, listenDelta = 0L) }
        stopSaveTicker()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }
}
