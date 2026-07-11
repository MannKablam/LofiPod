package com.lofipod.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
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

    /**
     * Held while the player is actively playing. Acquiring a PARTIAL_WAKE_LOCK
     * here is belt-and-braces alongside the mediaPlayback foreground-service
     * type: the foreground service keeps the process alive across Doze, but
     * the kernel can still downclock CPU aggressively when the screen is off,
     * which starves the DSP path on a 2× playback budget. The wake lock asks
     * the kernel to keep the CPU clocked enough to make scheduling deadlines.
     *
     * Released as soon as playback pauses so we don't keep the CPU hot
     * during idle-pause-mid-listen windows. Released again in onDestroy as a
     * safety net.
     *
     * Permission `android.permission.WAKE_LOCK` is already declared in the
     * manifest (was unused prior to this change — see BUILD_LOG audit notes).
     */
    private var playbackWakeLock: PowerManager.WakeLock? = null

    /**
     * Ring buffer of wake-lock acquire timestamps. Used to detect rapid
     * oscillation — when the player flips between isPlaying=true and false
     * faster than once every 5 seconds, the wake lock is acquired/released
     * on the same cadence. That cadence is a strong signal for DSP-side
     * stalls (BUFFERING↔READY oscillation under thermal / clock pressure)
     * because the user's intent (playWhenReady) hasn't changed but the
     * actual isPlaying flag is bouncing. Surfaced as a single
     * AppDiagnostics event per minute so we have a back-end-readable
     * record of the oscillation pattern alongside the stall watchdog's
     * arm-C trigger.
     */
    private val recentAcquireTimestamps = ArrayDeque<Long>()
    private var lastOscillationLogAtMs: Long = 0L

    /**
     * Throttled AudioTrack-underrun tracking. ExoPlayer's
     * AnalyticsListener.onAudioUnderrun fires once per underrun event from
     * the audio sink's POV; in a chronic-underrun storm (the user-reported
     * 3-5s loop) that can be many per second. Aggregate into 30-second
     * windows and log a single AppDiagnostics entry per window with
     * (count, worst-buffer-drained-by-ms) so the diagnostics ring isn't
     * flooded.
     */
    private var underrunWindowStartMs: Long = 0L
    private var underrunWindowCount: Int = 0
    private var underrunWindowWorstElapsedMs: Long = 0L

    companion object {
        // Shared EQ instance — UI can grab it via app-level holder
        val sharedEq = EqAudioProcessor()
        // Shared silence-skipping processor — runtime-tunable level via
        // setLevel(0..3), defaults off. Lives next to sharedEq so the EQ
        // screen can mutate both via PlaybackService.<X>.
        val sharedSkipSilence = SilenceSkippingProcessor()
        // Shared pause tap — records media positions of audible pauses for
        // the "skip back to previous pause" control. Sensitivity is set from
        // the player UI (long-press on the pause-skip button) and persisted
        // via Settings.pauseSkipSensitivity.
        val sharedPauseTap = com.lofipod.app.audio.PauseTapProcessor()
        private const val SAVE_INTERVAL_MS = 10_000L

        /**
         * Volatile snapshot of whether the playback wake lock is currently
         * held. Read by the in-Player diagnostics tab so the user can see at
         * a glance "yes, the kernel is being asked to stay clocked." Set by
         * acquirePlaybackWakeLock / releasePlaybackWakeLock right after the
         * actual acquire/release; read-only from outside.
         *
         * Lives on the companion (not the instance) because diagnostics
         * code reads it without needing a service reference — the audio
         * thread + UI consumers can hit this directly the same way they
         * hit [sharedEq].
         */
        @Volatile var wakeLockHeld: Boolean = false
            internal set

        /** Intent action used by the media-session tap target to ask MainActivity
         *  to navigate straight to the Player screen instead of resuming on Catalog. */
        const val ACTION_OPEN_PLAYER = "com.lofipod.app.OPEN_PLAYER"

        /** Window over which we count wake-lock acquires for oscillation
         *  detection. 30 s aligns with the sticky stall-watchdog arm so
         *  the two signals corroborate cleanly in the diagnostics log. */
        private const val WAKE_LOCK_OSCILLATION_WINDOW_MS = 30_000L

        /** Acquire-count within the window that's considered "thrashing."
         *  5 acquires/30 s = an isPlaying flip every ~6 s, which is right
         *  in the band of the user-reported 3-5 s playback loops. Normal
         *  playback has 1 acquire per actual user play action — well
         *  under threshold even across short pause-and-resume sessions. */
        private const val WAKE_LOCK_OSCILLATION_THRESHOLD = 5

        /** Minimum gap between oscillation log entries. Prevents a
         *  sustained underrun from flooding the diagnostics ring with one
         *  entry per acquire. Same magnitude as the stall-snackbar
         *  throttle in PlayerController. */
        private const val WAKE_LOCK_OSCILLATION_COOLDOWN_MS = 60_000L

        /** Aggregation window for AudioTrack underrun events. Within a
         *  30s window we keep a count + worst-elapsed-feed-gap and emit
         *  ONE diagnostics entry summarising the window. Matches the
         *  arm-C sticky-stall window and the wake-lock oscillation
         *  window — same time base across all three signals so a
         *  reader can correlate by looking at "ago" timestamps. */
        private const val AUDIO_UNDERRUN_WINDOW_MS = 30_000L
    }

    override fun onCreate() {
        val tOnCreate = System.nanoTime()
        super.onCreate()
        // Prepare the playback wake lock once at service creation. Tag uses
        // the documented "app:purpose" convention so it shows up identifiably
        // in `adb shell dumpsys power`. Reference-counted=false because we
        // only acquire/release at the isPlaying-true/false transitions —
        // there's no nested-acquire pattern that would need ref counting.
        playbackWakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LofiPod:playback")
            ?.apply { setReferenceCounted(false) }
        // Scheme-aware DataSource factory: file:// URIs (downloaded episodes)
        // route through Media3's FileDataSource, http(s):// URIs go to
        // OkHttpDataSource. v0.6.9 reset dropped the streaming-cache
        // (SimpleCache + CacheDataSource) along with the rest of Media3's
        // offline framework — re-streaming on scrub is a tolerable trade
        // for a downloader we can actually trust.
        val dataSourceFactory = (application as LofiPodApp).downloads.dataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        // Audio buffer sizes. The previous v0.6.x→v0.8.0 config (min=180s,
        // max=600s, prioritize-time=true) was sized to survive ~5 min of
        // wall-clock at 2× without rebuffering, but on local file:// sources
        // the Loader read continuously until the time threshold, pulling up
        // to ~600 s media-time of decoded audio into SampleQueue allocations
        // — at 256 kbps stereo that's ~106 MB of decoded PCM, dangerously
        // close to OOM on 1–2 GB devices and a real GC-pause source on the
        // audio thread. See _LOFIPOD_V1_BRIEF.md §E2.
        //
        // 30/60 s with prioritize-time disabled re-engages the byte ceiling
        // (8 MB ≈ 4 min @256 kbps stereo PCM) and is plenty for podcasts at
        // 64–256 kbps even at 2× playback. bufferForPlayback stays at
        // 2 s / 5 s — same fast-start feel as the prior config without the
        // pathological local-file fill.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .build()

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, sharedEq, sharedSkipSilence, sharedPauseTap))
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
                    acquirePlaybackWakeLock()
                    startSaveTicker(player)
                } else {
                    stopSaveTicker()
                    saveCurrent(player, listenDelta = 0L)
                    releasePlaybackWakeLock()
                }
            }

            // --- PauseTap anchoring -------------------------------------
            // The pause tap reconstructs media positions from PCM frame
            // counts and needs an authoritative position for the first
            // frame after every pipeline flush. Every event below reports
            // the position that the post-flush (or post-restart) stream
            // begins at; the tap adopts the freshest post at its next
            // buffer, so posting generously is safe.
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                // New item ⇒ old pauses are meaningless.
                sharedPauseTap.clearPauses()
                sharedPauseTap.postAnchor(player.currentPosition)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                sharedPauseTap.postAnchor(newPosition.positionMs)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Covers flushes with no discontinuity (route changes etc.):
                // after the pipeline refills, position hasn't advanced, so
                // it's a valid anchor for the restarted stream.
                if (playbackState == Player.STATE_READY) {
                    sharedPauseTap.postAnchor(player.currentPosition)
                }
            }

            override fun onPlaybackParametersChanged(
                playbackParameters: androidx.media3.common.PlaybackParameters,
            ) {
                // Speed changes can rebuild the sink pipeline without a
                // position discontinuity or READY transition; re-anchor so
                // the pause tap isn't left waiting until the next
                // incidental event.
                sharedPauseTap.postAnchor(player.currentPosition)
            }
        })

        // ExoPlayer AnalyticsListener — surfaces low-level audio events
        // the regular Player.Listener doesn't expose: AudioTrack
        // underruns (the kernel-layer "audio HAL ran dry" event that
        // showed up as "AudioTrack: getTimestamp_l device stall time
        // corrected" in field logs (LofiPod log 7d4b926806be.txt:607,
        // 664, 687, ...)) and audio-format-change / decoder-error
        // breadcrumbs. Throttled in the recordAudioUnderrun helper so
        // a chronic-underrun storm doesn't fill the diagnostics ring.
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                recordAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }

            override fun onAudioSinkError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                    "audio_sink_error",
                    "${audioSinkError.javaClass.simpleName}: ${audioSinkError.message ?: "(no message)"}",
                )
            }

            override fun onAudioCodecError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioCodecError: Exception,
            ) {
                com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                    "audio_codec_error",
                    "${audioCodecError.javaClass.simpleName}: ${audioCodecError.message ?: "(no message)"}",
                )
            }

            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                    "audio_decoder_init",
                    "decoder=$decoderName init_ms=$initializationDurationMs",
                )
            }
        })

        // Rehydrate persisted audio prefs into the shared processors.
        // One-shot reads on service creation — the EQ screen writes through
        // to the same Settings entries (and to the live processors) when
        // the user changes anything, so we don't need ongoing collectors.
        scope.launch {
            val settings = com.lofipod.app.data.Settings(this@PlaybackService)
            sharedSkipSilence.setLevel(settings.skipSilenceLevel.first())
            sharedPauseTap.setSensitivity(settings.pauseSkipSensitivity.first())

            // EQ bands deliberately NOT rehydrated here. Since the v0.6.12
            // reshape there is no global EQ — bands live per-podcast
            // (podcast_state.eqBandsCsvOverride) with optional per-episode
            // branches, and PlayerController.applyEqOverrideFor installs the
            // right curve on every MediaItem transition (including the
            // cold-start restore's setMediaItem). The old code here read the
            // LEGACY global `eq_bands` DataStore key — which nothing writes
            // anymore — so a stale pre-v0.6.12 curve could audibly flash
            // between service start and the first transition. FLAT until the
            // first applyEqOverrideFor is the correct boot state.

            sharedEq.setGainDb(settings.gainDb.first())

            // Tone filters (low-cut / high-cut / tilt) — global corrective
            // stage, same persistence model as the DC blocker below.
            sharedEq.setLowCutHz(settings.toneLowCutHz.first())
            sharedEq.setHighCutHz(settings.toneHighCutHz.first())
            sharedEq.setTiltDb(settings.toneTiltDb.first())

            // Master "Audio enhancement" enable. PlayerController.applyEqOverrideFor
            // re-evaluates this on every track transition and ANDs it with the
            // per-podcast eqDisabled flag, so this initial value matters only
            // for the (rare) window before the first item transition fires.
            sharedEq.setEnabled(settings.audioEnhancementEnabled.first())

            // DC blocker is independent of the master enable — it's a
            // pre-EQ source-conditioning stage that runs even when the EQ
            // chain is otherwise passthrough. Off by default; users with
            // DC-offset-y feeds can flip it on.
            sharedEq.setDcBlockerEnabled(settings.dcBlockerEnabled.first())

            // EQ phase mode (v0.9.3+ enum). The new key reads the explicit
            // `phase_mode` string and falls back to the legacy
            // `phase_mode_linear` Boolean if the new key is absent (first-
            // run-after-upgrade case). v0.9.0–v0.9.2 force-suppressed the
            // saved value to always boot PURE_IIR while the linear chip was
            // hidden; v0.9.3 lifts that suppression now that the 3-chip
            // lineup is back and FIR modes are powered by the rebuilt
            // UPC convolver (FirEq).
            val savedMode = com.lofipod.app.audio.PhaseMode.fromStorageKey(
                settings.phaseMode.first()
            )
            sharedEq.setPhaseMode(savedMode)
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
            .setCallback(AutoplayConfirmCallback)
            .build()

        com.lofipod.app.diagnostics.StartupTimings.record(
            "playback_service_oncreate",
            tOnCreate,
        )
    }

    /**
     * Intercepts `Player.COMMAND_PLAY_PAUSE` arriving from REMOTE controllers
     * (BT headphones, vehicle transport, system media notification) while
     * the autoplay-confirmation timer is active. The activity-side
     * [PlayerController] writes the timer state, [AutoplayConfirmBridge]
     * exposes "is timer active" + the confirm callback to this service.
     *
     * If the bridge confirms a hit, return [SessionResult.RESULT_INFO_SKIPPED]
     * — the player ignores the play/pause and audio keeps rolling. The
     * activity-side controller has already been told to clear the timer in
     * the same call, so the next play/pause press goes through normally.
     *
     * **Controller-source filtering (v0.10.13 fix).** Pause/play commands
     * originating from OUR OWN process must NEVER be intercepted. The earlier
     * "togglePlay short-circuits to confirmAutoplayContinuation" comment
     * accounted for the public PlayerController.togglePlay path, but missed
     * [com.lofipod.app.audio.BeepPlayer.duckedBeep] which also calls
     * `player.pause()` directly to silence the podcast under the beep tone.
     * That pause arrived here, the timer was active, the bridge interpreted
     * it as a remote BT press, fired confirmAutoplayContinuation (cancelling
     * the timer + the in-flight beep coroutine), and Media3 then tried to
     * build a SessionResult Bundle from RESULT_INFO_SKIPPED — which fails
     * an internal assertion at SessionResult.java:227, producing the
     * "Ignoring malformed Bundle for SessionResult" warning observed in
     * field logs (LofiPod log 7d4b926806be.txt:619-658).
     *
     * Net effect of the old code: every autoplay-induced episode was
     * silently uninterruptible after T=60s (first beep). Beep #2 / #3 and
     * the T=190s auto-pause never fired because the timer had been
     * inadvertently confirmed by our own pause-for-beep.
     *
     * Fix: gate the intercept on `controller.uid != Process.myUid()`.
     * Same-uid pauses (BeepPlayer, togglePlay, the timer's auto-pause,
     * future code paths we haven't thought of yet) always pass through to
     * default handling. Only true remote controllers hit the intercept.
     */
    private object AutoplayConfirmCallback : MediaSession.Callback {
        // Media3 1.5 deprecated this MediaSession.Callback.onPlayerCommandRequest
        // override in favor of a slightly different signature. The functional
        // behavior of intercepting COMMAND_PLAY_PAUSE while the autoplay-
        // confirmation timer is active still works correctly — Media3 calls
        // both the new and the deprecated signature internally for back-compat.
        // When we revisit MediaSession integration in a future tag we can
        // migrate to the non-deprecated overload; for now @Suppress keeps the
        // build clean.
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (playerCommand != Player.COMMAND_PLAY_PAUSE) {
                return SessionResult.RESULT_SUCCESS
            }
            // Same-uid = our own MediaController (or anything else in our
            // process). Let it through unconditionally — the public
            // PlayerController.togglePlay / pause paths already handle the
            // autoplay-timer confirmation themselves; internal pauses from
            // BeepPlayer must not be intercepted because the resulting
            // confirm + timer-cancel would self-defeat the beep window.
            val ownUid = android.os.Process.myUid()
            if (controller.uid == ownUid) {
                return SessionResult.RESULT_SUCCESS
            }
            // Remote controller — apply the bridge intercept.
            if (AutoplayConfirmBridge.handleMediaButtonPlayPause()) {
                com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                    "autoplay_confirm_remote",
                    "remote controller pause/play intercepted as autoplay confirm " +
                        "(uid=${controller.uid} pkg=${controller.packageName})",
                )
                return SessionResult.RESULT_INFO_SKIPPED
            }
            return SessionResult.RESULT_SUCCESS
        }
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
        releasePlaybackWakeLock()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Acquire the playback wake lock. Idempotent — already-held is a no-op
     * (the `isHeld` guard avoids the `WakeLock under-locked` warning we'd
     * otherwise see on a stray double-acquire). Wrapped in try/catch because
     * a hostile SELinux denial on some OEM builds can throw at acquire time;
     * a wake-lock failure must never crash playback.
     */
    private fun acquirePlaybackWakeLock() {
        val wl = playbackWakeLock ?: return
        try {
            if (!wl.isHeld) wl.acquire()
            wakeLockHeld = wl.isHeld
            recordAcquireForOscillationDetection()
        } catch (t: Throwable) {
            Log.w("LofiPodPlayback", "WakeLock acquire failed: ${t.message}")
            wakeLockHeld = false
        }
    }

    /**
     * Throttled aggregator for [AnalyticsListener.onAudioUnderrun]
     * events. The user-reported 3-5s playback loop appears at the audio-
     * HAL layer as repeated AudioTrack underruns; logging each one
     * individually would flood the diagnostics ring. Aggregate in a
     * 30s window and emit one entry per window with the count + worst
     * "elapsed since last feed" measurement so we can see severity at a
     * glance without losing the signal.
     */
    private fun recordAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        val now = System.currentTimeMillis()
        if (underrunWindowStartMs == 0L || now - underrunWindowStartMs > AUDIO_UNDERRUN_WINDOW_MS) {
            // Flush the previous window if it had any events, then start fresh.
            if (underrunWindowCount > 0) {
                com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                    "audio_underrun_window",
                    "$underrunWindowCount underruns in " +
                        "${AUDIO_UNDERRUN_WINDOW_MS / 1000}s — worst gap " +
                        "${underrunWindowWorstElapsedMs}ms (buffer sized ${bufferSizeMs}ms)",
                )
            }
            underrunWindowStartMs = now
            underrunWindowCount = 0
            underrunWindowWorstElapsedMs = 0L
        }
        underrunWindowCount++
        if (elapsedSinceLastFeedMs > underrunWindowWorstElapsedMs) {
            underrunWindowWorstElapsedMs = elapsedSinceLastFeedMs
        }
    }

    /**
     * Push the current wall-clock onto [recentAcquireTimestamps], trim
     * anything outside the oscillation window, and if the resulting
     * acquire-count breaches the threshold AND we haven't logged within
     * the last cooldown, drop a single diagnostics breadcrumb. Throttled
     * so a chronic stall doesn't paper the diagnostics ring with one
     * entry per second.
     */
    private fun recordAcquireForOscillationDetection() {
        val now = System.currentTimeMillis()
        recentAcquireTimestamps.addLast(now)
        while (recentAcquireTimestamps.isNotEmpty() &&
            now - recentAcquireTimestamps.first() > WAKE_LOCK_OSCILLATION_WINDOW_MS
        ) {
            recentAcquireTimestamps.removeFirst()
        }
        if (recentAcquireTimestamps.size >= WAKE_LOCK_OSCILLATION_THRESHOLD &&
            now - lastOscillationLogAtMs >= WAKE_LOCK_OSCILLATION_COOLDOWN_MS
        ) {
            lastOscillationLogAtMs = now
            com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                "wake_lock_oscillation",
                "${recentAcquireTimestamps.size} acquires in " +
                    "${WAKE_LOCK_OSCILLATION_WINDOW_MS / 1000}s window — " +
                    "isPlaying is flip-flopping, likely DSP underrun / network rebuffer cycle",
            )
        }
    }

    /** Symmetric release; tolerant of unheld-state via the [isHeld] guard. */
    private fun releasePlaybackWakeLock() {
        val wl = playbackWakeLock ?: return
        try {
            if (wl.isHeld) wl.release()
        } catch (t: Throwable) {
            Log.w("LofiPodPlayback", "WakeLock release failed: ${t.message}")
        } finally {
            wakeLockHeld = wl.isHeld
        }
    }
}
