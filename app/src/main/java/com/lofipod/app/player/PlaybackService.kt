package com.lofipod.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lofipod.app.ui.MainActivity
import com.lofipod.app.LofiPodApp
import com.lofipod.app.audio.EqAudioProcessor
import com.lofipod.app.audio.SilenceSkippingProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        // Shared silence-skipping processor — runtime-tunable level via
        // setLevel(0..3), defaults off. Lives next to sharedEq so the EQ
        // screen can mutate both via PlaybackService.<X>.
        val sharedSkipSilence = SilenceSkippingProcessor()
        private const val SAVE_INTERVAL_MS = 10_000L

        /** Intent action used by the media-session tap target to ask MainActivity
         *  to navigate straight to the Player screen instead of resuming on Catalog. */
        const val ACTION_OPEN_PLAYER = "com.lofipod.app.OPEN_PLAYER"
    }

    override fun onCreate() {
        super.onCreate()
        // Cache-aware media source factory: downloaded episodes play locally,
        // streamed episodes still hit HTTP (with opportunistic range caching).
        val cacheFactory = (application as LofiPodApp).downloads.cacheDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(cacheFactory)

        // Audio-friendly buffer sizes. Defaults are tuned for video; podcasts can
        // afford larger buffers (one episode is ~50–200 MB at 128 kbps for 1 hour
        // of audio), and a longer max buffer means fewer rebuffers on flaky
        // connections. setPrioritizeTimeOverSizeWhileLoading favors buffering more
        // duration over hitting a byte cap, which is what we want for audio.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, sharedEq, sharedSkipSilence))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // CONTENT_TYPE_MUSIC (not SPEECH) so Android keeps full A2DP
                    // music routing/codec selection on Bluetooth — SPEECH can trip
                    // voice-oriented post-processing on some HALs and produce subtle
                    // BT-only choppiness even though podcasts are nominally voice.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
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

        // Rehydrate persisted audio prefs into the shared processors.
        // One-shot reads on service creation — the EQ screen writes through
        // to the same Settings entries (and to the live processors) when
        // the user changes anything, so we don't need ongoing collectors.
        scope.launch {
            val settings = com.lofipod.app.data.Settings(this@PlaybackService)
            sharedSkipSilence.setLevel(settings.skipSilenceLevel.first())

            // EQ bands — CSV of 10 gain values, one per ISO band. If the
            // CSV is missing or malformed, leave the processor at FLAT
            // defaults rather than half-loading a bad config.
            val csv = settings.eqBandsCsv.first()
            if (csv != null) {
                val gains = csv.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (gains.size == com.lofipod.app.audio.EqPresets.DEFAULT_BANDS.size) {
                    val rehydrated = com.lofipod.app.audio.EqPresets.DEFAULT_BANDS
                        .mapIndexed { i, b -> b.copy(gainDb = gains[i]) }
                    sharedEq.setBands(rehydrated)
                }
            }

            sharedEq.setGainDb(settings.gainDb.first())
        }

        // Notification tap target: route through MainActivity with a custom
        // action so the UI can route the user to the Player screen instead of
        // dropping them on the Catalog. Without this, tapping the system media
        // notification did literally nothing.
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
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
