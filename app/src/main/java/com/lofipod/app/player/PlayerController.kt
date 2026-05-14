package com.lofipod.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.lofipod.app.LofiPodApp
import com.lofipod.app.data.db.EpisodeNoteEntryEntity
import com.lofipod.app.data.db.EpisodeStateDao
import com.lofipod.app.data.db.EpisodeStateEntity
import com.lofipod.app.data.db.FeedVisitEntity
import com.lofipod.app.data.db.PlaybackCheckpointDao
import com.lofipod.app.data.db.PlaybackCheckpointEntity
import com.lofipod.app.data.db.QueueEntryDao
import com.lofipod.app.data.db.QueueEntryEntity
import com.lofipod.app.data.model.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val checkpointDao: PlaybackCheckpointDao
        get() = (context.applicationContext as LofiPodApp).db.playbackCheckpointDao()
    private val autoDownloadDao: com.lofipod.app.data.db.AutoDownloadDao
        get() = (context.applicationContext as LofiPodApp).db.autoDownloadDao()
    private val queueDao: QueueEntryDao
        get() = (context.applicationContext as LofiPodApp).db.queueEntryDao()
    private val podcastStateDao: com.lofipod.app.data.db.PodcastStateDao
        get() = (context.applicationContext as LofiPodApp).db.podcastStateDao()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * Set briefly after a jump (note or history) so the PlayerScreen can offer a
     * "Return to <position>" chip. Cleared on next jump or [release].
     */
    private val _pendingReturn = MutableStateFlow<PendingReturn?>(null)
    val pendingReturn: StateFlow<PendingReturn?> = _pendingReturn.asStateFlow()

    /**
     * Captured args of a [playEpisode] call that arrived while [controller]
     * was still null (the MediaController is built async; until the future
     * fires we have nothing to forward commands to). Drained in [connect]'s
     * listener once the controller is live. Last-wins: a second tap before
     * connect overwrites the first, which is the right UX (whatever the user
     * most recently asked for is what plays).
     *
     * Without this queue, a fast-tap-on-fresh-install lost the race against
     * the cold service bind and the play was silently dropped — which is
     * what produced the "tap Play, nothing happens, then toggling skip
     * silence makes it work" symptom. Skip silence wasn't the fix; the
     * 2–5 seconds of detour just gave the controller time to connect.
     */
    @Volatile private var pendingPlay: PendingPlay? = null

    /**
     * Flips to true inside [advanceToNextInQueue] right before each
     * [playEpisode] call so the receiving [playEpisode] knows the play was
     * autoplay-induced (vs. a manual user tap). Consumed + cleared at the
     * top of every [playEpisode] invocation, so a stale flag from a long-
     * since-bypassed autoplay can't latch onto a later manual play.
     */
    @Volatile private var lastPlayWasAutoplay = false

    /**
     * State of the autoplay-confirmation window started inside [playEpisode]
     * when a play is autoplay-induced (see [lastPlayWasAutoplay] +
     * [maybeStartAutoplayTimer]). Non-null while the timer is counting; the
     * UI (PlayerScreen / MiniPlayer) reads this to morph the play button into
     * a countdown after the first beep. Cleared on confirmation, expiry,
     * episode change, or release.
     */
    private val _autoplayTimer = MutableStateFlow<AutoplayTimerState?>(null)
    val autoplayTimer: StateFlow<AutoplayTimerState?> = _autoplayTimer.asStateFlow()
    private var autoplayTimerJob: kotlinx.coroutines.Job? = null

    /**
     * One-shot transient messages from the player layer that the UI
     * surfaces as snackbars. Used so the play button never feels like a
     * silent no-op: when the user taps play and the player is
     * mid-buffering / not yet bound / has no media item, we emit a short
     * message so they get explicit confirmation that their tap registered.
     *
     * Buffer capacity 4 so a quick burst of taps doesn't drop messages,
     * but we don't replay old messages to fresh subscribers.
     */
    private val _transientMessages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val transientMessages: SharedFlow<String> = _transientMessages.asSharedFlow()

    /**
     * Stall watchdog. While the player wants to play
     * (`playWhenReady`), poll [Player.currentPosition] every
     * [STALL_POLL_INTERVAL_MS] and flag a stall under either condition:
     *   1. The player reports `isPlaying=true` (state=READY) but the
     *      running max position hasn't advanced for [STALL_THRESHOLD_MS]
     *      — the cycling-position underrun loop where the renderer
     *      replays its last decoded ~5 s of buffer indefinitely.
     *   2. The player has been stuck in `STATE_BUFFERING` (isPlaying=false
     *      but playWhenReady=true) for [BUFFERING_STALL_THRESHOLD_MS] —
     *      "trying to play but the source/decoder/renderer can't deliver".
     *      For local files this should be near-instant; multi-second
     *      buffering on disk content means the chain is wedged.
     *
     * On stall, force a recovery via `seekTo(currentPosition - 100 ms)`.
     * The negative offset is deliberate: a same-position seek can be
     * deduplicated by Media3 / the audio sink and produce no flush, while
     * a 100 ms backward seek always triggers a full audio-sink + decoder
     * flush AND gives the user a brief replay of the audio they likely
     * missed during the stall.
     *
     * The cycling-position bug at high playback speeds (user-reported in
     * v0.6.x: 2x → position cycled `43:31..43:36` for ~7 min until a
     * manual flush) is the renderer underrunning. The DSP chain (linear-
     * phase EQ in particular) can't feed Sonic fast enough at 2x source
     * consumption rate, the audio sink underruns, and ExoPlayer recovers
     * by replaying the last decoded ~5s of buffer indefinitely. The
     * user's natural recovery — wait for a manual flush — sometimes
     * never came. This watchdog forces it.
     *
     * Also logs each stall to [AppDiagnostics] so we can see how often
     * this fires across builds. If it fires regularly, the underlying
     * DSP perf is the real issue and the watchdog is a band-aid.
     */
    private var stallWatchdogJob: kotlinx.coroutines.Job? = null
    private var lastForwardProgressPosMs: Long = 0L
    private var lastForwardProgressAtMs: Long = 0L
    /** Wall-clock when the player most recently entered STATE_BUFFERING with
     *  playWhenReady=true. Used by the watchdog to detect chronic buffering
     *  stalls (the renderer is wedged trying to deliver audio). 0 when not
     *  currently in a buffering window. */
    private var bufferingStartedAtMs: Long = 0L
    /** Wall-clock of the most recent stall snackbar. Used to throttle so a
     *  cycling-stall doesn't spam the user with a snackbar every recovery. */
    private var lastStallMessageAtMs: Long = 0L

    /**
     * Arm C — sticky oscillation detector. Ring buffer of
     * (wallclockMs, currentPositionMs) sampled every [STALL_POLL_INTERVAL_MS]
     * while `playWhenReady=true`, REGARDLESS of `isPlaying` or playback
     * state. Both arms A and B reset their baselines on isPlaying / state
     * transitions, so a player that oscillates between BUFFERING and READY
     * every 3-5 seconds (the screen-off-autoplay DSP-underrun pattern
     * reported across multiple versions) trips neither arm even though the
     * user hears 3-5 seconds of repeating audio for minutes on end.
     *
     * Arm C survives those transitions because the ring buffer keeps
     * accumulating samples — only [stopStallWatchdog] / [pause] clear it.
     * On each tick, after at least [STALL_STICKY_MIN_SAMPLES] seconds of
     * samples, compare actual position advance over the window to the
     * speed-adjusted wall-clock advance. If the actual is less than
     * [STALL_STICKY_MIN_ADVANCE_RATIO] of expected, the player is
     * effectively stuck and we trigger the same recovery as arms A and B.
     */
    private val playbackSamples = ArrayDeque<Pair<Long, Long>>()
    /** Wall-clock of the most recent sticky-stall trigger. Used to throttle
     *  so we don't re-fire on every poll while the ring buffer is still
     *  catching up post-recovery. */
    private var lastStickyStallAtMs: Long = 0L

    /** Guard against re-triggering the same handoff if the user transitions
     *  through the swap state (e.g., a brief race between byId emission and
     *  the new MediaItem propagating). Cleared when the current episode
     *  changes. */
    private var handoffTriggeredForGuid: String? = null

    fun connect(onReady: () -> Unit) {
        // Pin this controller as the autoplay-confirm target for any
        // service-side media-button intercept (BT, vehicle, system-
        // notification play/pause). Unpinned in [release].
        AutoplayConfirmBridge.bind(this)
        val tBuildController = System.nanoTime()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            com.lofipod.app.diagnostics.StartupTimings.record(
                "media_controller_connect", tBuildController,
            )
            controller = future.get().also { c ->
                c.addListener(listener)
                pushState()
                // Cold launch with audio already playing: addListener doesn't
                // replay state-change callbacks, so onPlayWhenReadyChanged
                // won't fire for the existing playWhenReady=true. Start the
                // watchdog manually if the session is already in play state.
                if (c.playWhenReady) startStallWatchdog()
            }
            onReady()
            // Drain any play attempt the user fired while we were still
            // binding to the service. Posted via scope.launch so the replay
            // lands on Main.immediate (same as everything else here), not on
            // whatever thread the future completed on. Short settle delay
            // first: hammering setMediaItem/prepare/play onto a controller
            // the same beat its future resolved was producing
            // ERROR_CODE_FAILED_RUNTIME_CHECK from inside ExoPlayer on
            // fresh installs (the session-side player needs a moment for
            // its initial state sync before it can accept commands cleanly).
            scope.launch {
                val p = pendingPlay ?: return@launch
                kotlinx.coroutines.delay(150)
                if (controller == null) return@launch
                pendingPlay = null
                // Re-assert the captured autoplay status BEFORE the replay so
                // an autoplay-induced bail still re-arms the confirmation
                // timer on the replayed play. The replayed playEpisode will
                // consume the flag again at its entry.
                if (p.wasAutoplay) lastPlayWasAutoplay = true
                playEpisode(p.ep, p.podcastTitle, p.podcastArt, p.forcedStartMs)
            }
            // Belt-and-suspenders: when the activity is recreated against a
            // still-running service (cold launch with audio playing), the
            // MediaController syncs its state from the session over a few
            // beats AFTER connect returns. The Player.Listener picks up most
            // events but there's a window where we'd be stuck on an empty
            // PlayerState — title null, artwork null, currentEpisodeGuid null —
            // which is what was killing the now-playing icon and the Player
            // header. Re-pushing state at 100/300/800 ms picks up whatever the
            // events miss.
            scope.launch {
                for (delayMs in longArrayOf(100, 300, 800)) {
                    kotlinx.coroutines.delay(delayMs)
                    if (controller == null) return@launch
                    pushState()
                }
            }
            // Mid-playback download-handoff observer. If the currently-
            // playing episode is being streamed over HTTP and its
            // download completes, swap the MediaItem to the local file
            // so the rest of playback runs from disk. See
            // [observeDownloadCompletion] for the swap mechanics + the
            // audible cue + the snackbar.
            scope.launch { observeDownloadCompletion() }
            // Cold-start restore: if the session has nothing loaded after the
            // sync settles, surface the last-played episode (paused) so the
            // mini-player + Player screen show "where I left off" without
            // making the user re-navigate from the catalog. Delay slightly
            // past the sync window above so we don't fire when the service
            // is still hydrating its own state.
            scope.launch {
                kotlinx.coroutines.delay(900)
                if (controller == null) return@launch
                if (pendingPlay != null) return@launch  // user already wants something specific
                restoreLastEpisodeIfNeeded()
            }
            // Reap auto-downloads whose owning episode finished playing
            // more than the TTL ago. Cheap (one indexed query + at most a
            // handful of deletes) and runs in the background of the
            // connect cycle.
            sweepExpiredAutoDownloads()
            // Pick up any deferred auto-downloads from a prior session
            // where the app was closed before the user transitioned away.
            // Fires the manager add for each so the offline copy gets
            // created in the background while the user uses the app.
            fireDeferredAutoDownloadOrphans()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Cold-start restore. If the MediaController has no current item AND
     * episode_state has a most-recently-played row, load that episode at
     * its saved position WITHOUT auto-playing. Result: the mini-player and
     * Player screen both show the last episode in paused state, ready to
     * resume on tap. No-op if the session already has an item (warm
     * reconnect after activity recreate) or if no episode has ever been
     * played on this install.
     *
     * Side-effect-light vs [playEpisode]: skips the autoplay timer, the
     * auto-download trigger, and the feed-visit upsert — those are signals
     * of an active "user started playing this" event, not a passive
     * restore.
     */
    private fun restoreLastEpisodeIfNeeded() {
        val c = controller ?: return
        if (c.currentMediaItem != null) return
        val app = context.applicationContext as LofiPodApp
        scope.launch {
            val state = withContext(Dispatchers.IO) { dao.mostRecentlyPlayed() }
                ?: return@launch
            if (controller == null) return@launch
            // Reuse the episode_state row to reconstruct the Episode (same
            // pattern as [episodeFromState]). Description / pubDate / mime
            // / duration aren't persisted there but Player only needs the
            // identity + uri + display metadata to render correctly.
            val ep = Episode(
                guid = state.guid,
                feedUrl = state.feedUrl,
                title = state.title,
                description = null,
                pubDateMillis = null,
                audioUrl = state.audioUrl,
                audioMimeType = null,
                durationSeconds = null,
                episodeArtworkUrl = state.artworkUrl,
            )
            // Treat positions within the last 5s of duration as "played" —
            // start from 0 if the user finished it, otherwise resume at
            // saved position. Mirrors the same logic in [playEpisode].
            val startPos = if (state.durationMs > 0 && state.positionMs >= state.durationMs - 5_000) {
                0L
            } else {
                state.positionMs
            }
            // Artwork fallback for the cold-start resume. Cache-stored
            // episode_state.artworkUrl was set at first play time and may
            // already be ep.episodeArtworkUrl ?: podcastArt — but if that
            // first play happened before the podcast had a channel image,
            // or if the row is from a feed whose art has since been
            // refreshed, the stored value can be stale or null. Refresh
            // against the live podcast cache (cheap — in-memory lookup,
            // already hydrated by repo.hydrateFromDisk on startup).
            val livePod = app.repo.cached(state.feedUrl)
            val livePodArt = livePod?.artworkUrl
            val liveEpArt = livePod?.episodes?.find { it.guid == state.guid }?.episodeArtworkUrl
            val art = liveEpArt ?: livePodArt ?: state.artworkUrl
            val item = MediaItem.Builder()
                .setMediaId(ep.guid)
                .setUri(ep.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(livePod?.title ?: "")
                        .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                        // Carry feedUrl through MediaMetadata extras so
                        // pushState can populate PlayerState.currentFeedUrl
                        // without a DB lookup on every recomposition.
                        .setExtras(android.os.Bundle().apply {
                            putString(EXTRA_FEED_URL, ep.feedUrl)
                        })
                        .build()
                )
                .build()
            // Same dual-form setMediaItem pattern as [playEpisode] —
            // bundling a non-zero seek into setMediaItem races against
            // prepare on a fresh service start. With startPos 0 we use the
            // single-arg form.
            if (startPos > 0L) {
                c.setMediaItem(item, startPos)
            } else {
                c.setMediaItem(item)
            }
            c.prepare()
            // Explicit: do NOT call play(). The user wants the episode
            // surfaced, not auto-resumed.
        }
    }

    fun release() {
        AutoplayConfirmBridge.unbind(this)
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _pendingReturn.value = null
        pendingPlay = null
        autoplayTimerJob?.cancel()
        autoplayTimerJob = null
        _autoplayTimer.value = null
        scope.cancel()
    }

    /** Sticky error from the last [Player.Listener.onPlayerError] call.
     *  Cleared by [pushState] when the player transitions back to a healthy
     *  state (BUFFERING or READY) — error chips disappear automatically once
     *  the user retries successfully. */
    @Volatile private var lastError: String? = null

    /**
     * Full unclipped error string for the diagnostics panel: code name +
     * message + cause class + cause message, no length cap. Mirrors what we
     * log to logcat under tag "LofiPodPlayer". The chip on PlayerScreen still
     * uses [lastError] (clipped for one-line display); this is what the
     * Settings → Audio diagnostics panel surfaces so the user can copy the
     * full failure when reporting bugs. Reset alongside [lastError]. */
    @Volatile private var lastErrorVerbose: String? = null

    /** Snapshot of [lastErrorVerbose] for UI consumption. Read-only. */
    val lastErrorDetails: String? get() = lastErrorVerbose

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushState()
            // Refresh the forward-progress baseline whenever audio actually
            // resumes — a buffering window before this transition wouldn't
            // have advanced position, so without this refresh the stall
            // detector would see "no forward progress for N seconds" the
            // moment playback resumes and could false-positive. The
            // watchdog's lifecycle is owned by [onPlayWhenReadyChanged] so
            // it can keep running ACROSS buffering windows (arm B
            // explicitly handles "playWhenReady=true but state=BUFFERING").
            if (isPlaying) {
                lastForwardProgressPosMs = controller?.currentPosition ?: 0L
                lastForwardProgressAtMs = System.currentTimeMillis()
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushState()
            // Watchdog lifecycle tracks user intent (= playWhenReady), not
            // moment-to-moment isPlaying. Otherwise a transient
            // BUFFERING-during-play (which flips isPlaying to false) would
            // kill the watchdog right as arm B should be running.
            if (playWhenReady) startStallWatchdog() else stopStallWatchdog()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pushState()
            // Apply per-episode EQ override on transitions. Looked up from the
            // EpisodeStateEntity row each time so a toggle flips immediately on
            // the next item, and re-asserts every time we come back to it.
            mediaItem?.mediaId?.let { applyEqOverrideFor(it) }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Healthy transition clears the sticky error so the chip goes
            // away once the player recovers.
            if (playbackState == Player.STATE_BUFFERING ||
                playbackState == Player.STATE_READY) {
                lastError = null
                lastErrorVerbose = null
            }
            pushState()
            if (playbackState == Player.STATE_ENDED) {
                // Remove the just-finished episode from the queue and auto-advance.
                // If canon-order autoplay is enabled and this episode has a
                // detected scripture ref, prefer the next sermon in canon
                // order over the standard queue/feed-next chain.
                val finishedGuid = controller?.currentMediaItem?.mediaId
                scope.launch {
                    if (finishedGuid != null && tryAdvanceToNextInCanon(finishedGuid)) {
                        // Canon mode handled it; skip the standard advance.
                        return@launch
                    }
                    advanceToNextInQueue(finishedGuid)
                }
                // Fire any deferred auto-download for the just-ended
                // episode. The cache has been populated by streaming;
                // DownloadManager will see the cached spans and complete
                // near-instantly without re-fetching.
                if (finishedGuid != null) {
                    scope.launch { fireDeferredAutoDownload(finishedGuid) }
                }
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Always log the full exception to logcat so the user can grab
            // diagnostics with `adb logcat -s LofiPodPlayer:* *:E` when the
            // chip alone isn't enough to figure out what broke.
            android.util.Log.w(
                "LofiPodPlayer",
                "Player error: code=${error.errorCodeName} msg=${error.message} " +
                    "cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}",
                error
            )
            // Surface a short, human-readable message. ExoPlayer's
            // PlaybackException.errorCodeName is stable across versions and
            // prints things like "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" —
            // that's actionable for the user (network) without dragging in
            // a full stack trace. Append the cause's class name when present
            // so opaque codes like FAILED_RUNTIME_CHECK still hint at the
            // actual underlying exception type, and a clipped cause message
            // when present — IllegalArgumentException's message often names
            // the bad input (e.g. "Invalid Uri scheme:" / "Range start is
            // beyond …"), which the class name alone hides.
            val codeName = error.errorCodeName.removePrefix("ERROR_CODE_")
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
            val causeName = error.cause?.javaClass?.simpleName
            val causeMsg = error.cause?.message?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > 80) it.substring(0, 77) + "…" else it }
            val tail = when {
                causeName != null && causeMsg != null -> " ($causeName: $causeMsg)"
                causeName != null -> " ($causeName)"
                else -> ""
            }
            lastError = "$codeName$tail"
            // Verbose form for the Settings → Audio diagnostics panel — full
            // cause message (no 80-char clip) so a user reporting a bug can
            // copy the same string we logged under "LofiPodPlayer".
            val fullCauseMsg = error.cause?.message?.takeIf { it.isNotBlank() }
            val verboseTail = when {
                causeName != null && fullCauseMsg != null -> " ($causeName: $fullCauseMsg)"
                causeName != null -> " ($causeName)"
                else -> ""
            }
            lastErrorVerbose = "${error.errorCodeName}: $codeName$verboseTail"
            pushState()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Mirror the live speed into AudioChainTelemetry so the PerfHint
            // bridge gets a speed-adjusted wall-clock target (audioNs / speed).
            // Without this, at 2× the OS gets a deadline twice as generous
            // as reality and downclocks the CPU. Volatile write; audio thread
            // sees it within one buffer.
            com.lofipod.app.audio.AudioChainTelemetry.playbackSpeed =
                playbackParameters.speed
            pushState()
        }
        // The next two fire as the MediaController syncs its state from a
        // running session — without them, reconnecting to a session that's
        // already mid-playback (cold launch case) leaves PlayerState empty
        // because no transition / state-change happens to wake the listener.
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = pushState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = pushState()
    }

    /**
     * Re-evaluate the live EQ for the episode at [guid]. There's no global
     * EQ — each podcast owns its own tuning. An episode normally inherits
     * its podcast's EQ; the per-episode "use a one-off EQ" toggle lets a
     * single episode branch off (e.g. a guest-heavy episode where the
     * usual host tuning doesn't fit).
     *
     * Resolution order: `episode_state.eqBandsCsvOverride` →
     * `podcast_state.eqBandsCsvOverride` → FLAT. The first non-null wins.
     *
     * Enable state is purely the master "Audio enhancement" switch — there
     * are no separate disable flags. "Disabling EQ" is just the override
     * toggle on with all bands at 0 dB. Keeping disable as a separate
     * concept was redundant and confused two layers of "off."
     *
     * Called on item transitions, after the user toggles the per-episode
     * override on the EQ screen, and after the user flips the master
     * toggle — three writer sites, single source of truth here so the
     * processor's enabled flag and band state don't get clobbered by the
     * last-wins of competing writers.
     */
    fun applyEqOverrideFor(guid: String) {
        scope.launch {
            val state = withContext(Dispatchers.IO) { dao.get(guid) }
            val feedUrl = state?.feedUrl
            val episodeOverrideCsv = state?.eqBandsCsvOverride
            val podcastState = if (feedUrl != null) {
                withContext(Dispatchers.IO) { podcastStateDao.get(feedUrl) }
            } else null
            val podcastEqCsv = podcastState?.eqBandsCsvOverride

            val settings = com.lofipod.app.data.Settings(context)
            val masterEnabled = settings.audioEnhancementEnabled.first()
            // setEnabled is volatile-safe; no need to bounce back to main.
            PlaybackService.sharedEq.setEnabled(masterEnabled)

            // Episode override branches off the podcast's EQ. Without an
            // override, the podcast's EQ applies. Without a podcast EQ,
            // the chain runs flat — there's no global EQ to fall back on.
            val targetCsv: String? = episodeOverrideCsv ?: podcastEqCsv
            val targetBands = if (targetCsv != null) {
                parseEqBandsCsv(targetCsv) ?: com.lofipod.app.audio.EqPresets.FLAT
            } else {
                com.lofipod.app.audio.EqPresets.FLAT
            }
            PlaybackService.sharedEq.setBands(targetBands)
        }
    }

    /** Parse a CSV of dB band gains into a band list using the standard ISO
     *  centers + Q. Returns null if the CSV count doesn't match the default
     *  band layout (likely a corrupt or truncated string). */
    private fun parseEqBandsCsv(csv: String): List<com.lofipod.app.audio.EqBand>? {
        if (csv.isBlank()) return null
        val gains = csv.split(',').mapNotNull { it.trim().toFloatOrNull() }
        val template = com.lofipod.app.audio.EqPresets.DEFAULT_BANDS
        if (gains.size != template.size) return null
        return template.mapIndexed { i, band -> band.copy(gainDb = gains[i]) }
    }

    private fun pushState() {
        val c = controller ?: return
        // Preserve isStalled across pushState — it's owned by the watchdog
        // (set/cleared on its own timer), not by Media3's player state.
        // Without preservation a routine onIsPlayingChanged after a stall
        // recovery would silently flip isStalled back to its default.
        val item = c.currentMediaItem
        // Pull feedUrl from the MediaMetadata extras bundle written by
        // playEpisode + restoreLastEpisodeIfNeeded. Null when the MediaItem
        // predates the extras-write (legacy session restored against an
        // older binary) — caller handles the null gracefully.
        val feedUrl = item?.mediaMetadata?.extras?.getString(EXTRA_FEED_URL)
        val scheme = item?.localConfiguration?.uri?.scheme?.lowercase()
        _state.update { prev ->
            PlayerState(
                isPlaying = c.isPlaying,
                isReady = c.playbackState == Player.STATE_READY,
                // Buffering counts only when we WANT to play (playWhenReady). A
                // paused player may also pass through BUFFERING but we shouldn't
                // surface a "Buffering…" indicator to the user in that case.
                isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
                isStalled = prev.isStalled,
                errorMessage = lastError,
                currentTitle = c.mediaMetadata.title?.toString(),
                currentArtist = c.mediaMetadata.artist?.toString(),
                currentArtworkUri = c.mediaMetadata.artworkUri?.toString(),
                currentEpisodeGuid = item?.mediaId,
                currentFeedUrl = feedUrl,
                currentMediaScheme = scheme,
                speed = c.playbackParameters.speed
            )
        }
    }

    /**
     * Play an episode, resuming from its saved position if any. If [forcedStartMs] is
     * provided, that wins over the saved position (used for jump-to-position).
     *
     * Records two kinds of checkpoints:
     *   - When switching from a different outgoing episode: a session_end checkpoint
     *     for the outgoing position (skipped when [forcedStartMs] is set, since the
     *     caller — typically [jumpToPosition] — already recorded a jump_from).
     *   - When the new episode's existing state was last touched > [SESSION_GAP_MS]
     *     ago: a session_end checkpoint at that previous position.
     */
    fun playEpisode(
        ep: Episode,
        podcastTitle: String,
        podcastArt: String?,
        forcedStartMs: Long? = null
    ) {
        // Consume the autoplay-detection flag UNCONDITIONALLY at entry,
        // before the controller-null check. Otherwise a bailed-to-pendingPlay
        // call would leave the flag set for the next playEpisode invocation,
        // which would then arm the autoplay-confirmation timer for a play the
        // user perceives as manual (the "spurious autoplay-style beep on cold
        // playback start" symptom). The flag travels into PendingPlay so the
        // drain in connect() can restore it for the replayed call.
        val wasAutoplay = lastPlayWasAutoplay
        lastPlayWasAutoplay = false

        val c = controller
        if (c == null) {
            // Controller still building — queue and bail. The drain in
            // [connect]'s listener replays this call once we're live, with
            // the autoplay flag re-asserted from the captured snapshot.
            pendingPlay = PendingPlay(ep, podcastTitle, podcastArt, forcedStartMs, wasAutoplay)
            return
        }
        // Any previous timer is for a different episode and must be torn down:
        // the body uses guid identity to decide whether to pause, but the
        // StateFlow still drives a stale countdown UI until cleared explicitly.
        autoplayTimerJob?.cancel()
        autoplayTimerJob = null
        _autoplayTimer.value = null

        // Housekeeping: prune any auto-downloads from PRIOR episodes whose
        // playback finished and whose 1-hour TTL has elapsed. Runs on every
        // track change so old auto-downloads don't accumulate even in long
        // sessions where connect() doesn't re-fire.
        sweepExpiredAutoDownloads()

        // Diagnostics breadcrumb. Logged BEFORE the suspending scope below so
        // even if the IO work stalls (e.g., DB busy on a slow device) the
        // event is already in the ring buffer. Outgoing guid is captured
        // synchronously on the main thread for the same reason.
        val outgoingForLog = c.currentMediaItem?.mediaId
        com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
            "track_change",
            "from=${outgoingForLog ?: "(none)"} to=${ep.guid} " +
                "auto=$wasAutoplay feed=${ep.feedUrl}",
        )
        scope.launch {
            // 1. Outgoing item: persist position + (optionally) record session_end checkpoint.
            val outgoingId = c.currentMediaItem?.mediaId
            if (outgoingId != null && outgoingId != ep.guid) {
                val pos = c.currentPosition
                val dur = c.duration.takeIf { it > 0 } ?: 0L
                withContext(Dispatchers.IO) {
                    if (dao.get(outgoingId) != null) {
                        dao.updatePosition(outgoingId, pos, dur, System.currentTimeMillis(), 0L)
                    }
                    if (forcedStartMs == null && pos > 0) {
                        checkpointDao.insert(
                            PlaybackCheckpointEntity(
                                guid = outgoingId,
                                positionMs = pos,
                                recordedAt = System.currentTimeMillis(),
                                reason = REASON_SESSION_END
                            )
                        )
                        checkpointDao.pruneToCount(CHECKPOINT_CAP)
                    }
                }
                // The user has moved on from `outgoingId`. If it was
                // scheduled for auto-download, fire the actual addDownload
                // now — by this point the SimpleCache has been filling
                // from the streaming session, so DownloadManager's worker
                // sees the cached spans and completes near-instantly
                // without re-fetching from HTTP.
                fireDeferredAutoDownload(outgoingId)
            }

            // 2. New item: ensure row exists, possibly snapshot a stale session, return start pos.
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
                    val gap = System.currentTimeMillis() - existing.lastPlayedMillis
                    if (forcedStartMs == null && gap > SESSION_GAP_MS && existing.positionMs > 0) {
                        checkpointDao.insert(
                            PlaybackCheckpointEntity(
                                guid = ep.guid,
                                positionMs = existing.positionMs,
                                recordedAt = existing.lastPlayedMillis,
                                reason = REASON_SESSION_END
                            )
                        )
                        checkpointDao.pruneToCount(CHECKPOINT_CAP)
                    }
                    val dur = existing.durationMs
                    if (dur > 0 && existing.positionMs >= dur - 5_000) 0L else existing.positionMs
                }
            }

            val startPos = forcedStartMs ?: savedPos

            val art = ep.episodeArtworkUrl ?: podcastArt
            // Prefer a downloaded local file when one exists. The new
            // OkHttp-based downloader (v0.6.9) writes completed episodes
            // to filesDir/episode_audio/<sha256(guid)>.bin; pointing the
            // MediaItem at file://that path makes ExoPlayer use a
            // FileDataSource, skipping HTTP entirely for offline playback.
            val app = context.applicationContext as LofiPodApp
            val localFile = app.downloadsApi.completedFile(ep.guid)
            val playUri = if (localFile != null) {
                android.net.Uri.fromFile(localFile)
            } else {
                android.net.Uri.parse(ep.audioUrl)
            }
            val item = MediaItem.Builder()
                .setMediaId(ep.guid)
                .setUri(playUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(podcastTitle)
                        .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                        // Carry feedUrl + scheme through extras so pushState
                        // can populate PlayerState without a per-recomposition
                        // DB lookup. Used by MainActivity's feed-aware back-
                        // from-Player nav (v0.10.12+) and by the diagnostics
                        // surface to render "playing local vs remote."
                        .setExtras(android.os.Bundle().apply {
                            putString(EXTRA_FEED_URL, ep.feedUrl)
                        })
                        .build()
                )
                .build()
            // setMediaItem(item, startPos) bundles a seek into the load,
            // which on Media3 1.4.x can race with prepare on the very first
            // play after a cold service start (FAILED_RUNTIME_CHECK from
            // inside ExoPlayer). When startPos is 0 there's nothing to seek
            // to, so use the no-position overload — it skips the implicit
            // seek entirely. Resume-from-saved-position (startPos > 0) keeps
            // the bundled form, which is the path that's been stable.
            if (startPos > 0L) {
                c.setMediaItem(item, startPos)
            } else {
                c.setMediaItem(item)
            }
            c.prepare()
            c.play()

            // Per-podcast default speed override (if set) is applied AFTER
            // play() — between prepare() and play(), the suspending DB read
            // was creating a window where audio would briefly start then
            // stop ("Play → Pause → Resume" instantly). Doing the lookup
            // here, post-play, means the user hears at most a fraction of a
            // second of 1.0x audio before Sonic picks up the override at
            // the next buffer boundary. The skip-the-1.0x-fallback path
            // also avoids any unnecessary setPlaybackSpeed call (which
            // resets renderer state in some cases).
            val speedOverride = withContext(Dispatchers.IO) {
                podcastStateDao.get(ep.feedUrl)?.defaultSpeed
            }
            if (speedOverride != null && kotlin.math.abs(speedOverride - 1.0f) > 0.001f) {
                // Mirror speed into AudioChainTelemetry too — see setSpeed
                // for rationale (PerfHint target wall-clock budget scaling).
                com.lofipod.app.audio.AudioChainTelemetry.playbackSpeed = speedOverride
                c.setPlaybackSpeed(speedOverride)
            } else {
                // No override (or 1.0×): reset the mirror so a previous
                // episode's per-podcast speed doesn't leak into PerfHint
                // math for this one.
                com.lofipod.app.audio.AudioChainTelemetry.playbackSpeed = 1.0f
            }

            // Mark this feed as "seen" — kills the new-episodes badge in Catalog
            // for this feed. Playing is the strongest signal that the user is
            // current with the channel.
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as LofiPodApp
                app.db.feedVisitDao().upsert(
                    FeedVisitEntity(ep.feedUrl, System.currentTimeMillis())
                )
            }

            // Auto-download. Fires immediately so the currently-playing
            // episode lands offline regardless of whether the user ever
            // transitions to another track. Backed by the new OkHttp
            // downloader (v0.6.9 rewrite) — no Media3 download manager,
            // no shared SimpleCache, no listener that doesn't fire on
            // progress. Just a plain HTTP fetch into filesDir/episode_audio/.
            // A prior FAILED state is treated as "needs start" so a replay
            // naturally retries the offline copy.
            //
            // Race: the in-memory `byId` map is hydrated asynchronously at
            // app launch (LofiPodDownloader.init { cleanupScope.launch {
            // hydrate() } }). A fast-play on cold start can see
            // `byId.value[guid] == null` even though the DAO row exists and
            // the file is fully downloaded. Re-firing start() in that window
            // wastes work AND emits a COMPLETED transition on byId, which
            // [observeDownloadCompletion] interprets as "download just
            // finished, mid-playback handoff!" — firing the handoff cue
            // beeps and a setMediaItem swap right at the moment audio
            // finally starts. Use completedFile() (DAO-fallback aware) as
            // the authoritative check.
            val existingFile = app.downloadsApi.completedFile(ep.guid)
            val existingDl = app.downloadsApi.byId.value[ep.guid]
            val needsStart = existingFile == null && (
                existingDl == null ||
                    existingDl.state == com.lofipod.app.data.LofiDownload.State.FAILED
                )
            if (needsStart) {
                withContext(Dispatchers.IO) {
                    autoDownloadDao.upsert(
                        com.lofipod.app.data.db.AutoDownloadEntity(
                            guid = ep.guid,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                // Screen-off + autoplay: defer the actual download. Firing
                // start() inline opens a second HTTP socket to the same CDN
                // while the player's streaming socket is still ramping up,
                // which on a downclocked CPU (screen off → kernel reduces
                // clocks aggressively) combines with DSP under-budget to
                // produce the 3-5s BUFFERING↔READY oscillation users have
                // reported across multiple versions. The auto_download row
                // is still upserted above, so fireDeferredAutoDownload picks
                // it up at the next track change or natural end — by which
                // point the user is usually awake / the device is interactive
                // again. Manual user-tap plays (wasAutoplay=false) keep the
                // inline start regardless of screen state.
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val interactive = pm?.isInteractive != false
                if (wasAutoplay && !interactive) {
                    com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
                        "auto_download_deferred",
                        "guid=${ep.guid} reason=screen_off+autoplay (will fire on next track change / screen wake)",
                    )
                } else {
                    app.downloadsApi.start(ep)
                }
            } else {
                // Existing download: only renew the auto clock if it WAS
                // already auto. A manually-downloaded episode (no
                // auto_download row) stays manual; we don't silently
                // convert it to auto-with-expiry on replay.
                withContext(Dispatchers.IO) {
                    if (autoDownloadDao.get(ep.guid) != null) {
                        autoDownloadDao.upsert(
                            com.lofipod.app.data.db.AutoDownloadEntity(
                                guid = ep.guid,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }

            // Autoplay confirmation window: if this play was triggered by
            // advanceToNextInQueue (queue-next or feed-next), arm the 3:10
            // confirmation timer. The user must confirm continuation by
            // tapping the morphed play button or pressing play/pause on a
            // BT/vehicle transport — otherwise we auto-pause to avoid
            // unattended indefinite playback.
            if (wasAutoplay) {
                maybeStartAutoplayTimer(ep.guid)
            }
        }
    }

    /**
     * Start a download for [guid] using the audioUrl + feedUrl persisted in
     * the episode_state row. Intended for the live-mode download button on
     * PlayerScreen — the screen has the guid but not the full Episode,
     * since the live state is driven off the MediaController. No-op if no
     * row exists yet (shouldn't happen for an episode that's currently
     * playing — playEpisode upserts the row before setMediaItem).
     */
    fun startDownloadForCurrent(guid: String) {
        val app = context.applicationContext as LofiPodApp
        scope.launch {
            val ep = episodeFromState(guid) ?: return@launch
            app.downloadsApi.start(ep)
            // Manual trigger — clear any prior auto-download flag so the
            // 1-hour-after-finished sweep won't expire this download.
            withContext(Dispatchers.IO) {
                autoDownloadDao.delete(guid)
            }
        }
    }

    /**
     * Fire the deferred DownloadManager add for [guid] if and only if:
     *   - The episode has an `auto_download` row (= it was scheduled by
     *     a prior playEpisode call but the manager add was deferred).
     *   - There's no existing Download for the episode yet (= we
     *     haven't already promoted it).
     *
     * Called from playEpisode's outgoing-item block (track change) and
     * from the player listener's STATE_ENDED branch (natural end). By
     * then the SimpleCache has been populated by ExoPlayer's
     * CacheDataSource as it streamed, so DownloadManager's worker sees
     * the cached spans and completes near-instantly — no second HTTP
     * fetch competing with the stream.
     *
     * Suspending so it can be called from a coroutine; safe to invoke
     * even when the inputs are stale (no-ops gracefully).
     */
    private suspend fun fireDeferredAutoDownload(guid: String) {
        val auto = withContext(Dispatchers.IO) { autoDownloadDao.get(guid) } ?: return
        val app = context.applicationContext as LofiPodApp
        // With the v0.6.9 OkHttp downloader, every play fires start() inline.
        // The deferred helpers only fire if no acceptable state already
        // exists — i.e. the row is FAILED or absent. Don't re-trigger a
        // currently-running or completed download.
        val existing = app.downloadsApi.byId.value[guid]
        if (existing != null) {
            val s = existing.state
            if (s == com.lofipod.app.data.LofiDownload.State.QUEUED ||
                s == com.lofipod.app.data.LofiDownload.State.DOWNLOADING ||
                s == com.lofipod.app.data.LofiDownload.State.COMPLETED
            ) return
        }
        val ep = withContext(Dispatchers.IO) { episodeFromState(guid) } ?: return
        app.downloadsApi.start(ep)
        // Touch the auto_download row to keep the expiration clock fresh.
        withContext(Dispatchers.IO) {
            autoDownloadDao.upsert(
                com.lofipod.app.data.db.AutoDownloadEntity(
                    guid = guid,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Sweep `auto_download` rows that don't yet have a corresponding
     * Download object — these are deferred auto-downloads from prior
     * sessions where the user closed the app before transitioning
     * away from the episode. Fires the manager add for each so the
     * user's offline copy gets created in the background.
     *
     * Cheap: a single DAO query + a few `app.downloadsApi.byId.value`
     * lookups. Runs on connect() after the warmup completes.
     */
    private fun fireDeferredAutoDownloadOrphans() {
        scope.launch {
            val app = context.applicationContext as LofiPodApp
            val rows = withContext(Dispatchers.IO) {
                // Walk all auto_download rows. "Orphan" = no LofiDownload
                // entry, OR the entry is in FAILED state (e.g. revived
                // from a prior interrupted session — see hydrate()). The
                // finished + unfinished sweeps together cover every row
                // at MAX_VALUE cutoff (mutually exclusive predicates).
                val all = (
                    autoDownloadDao.expiringFinishedGuids(Long.MAX_VALUE) +
                        autoDownloadDao.expiringUnfinishedGuids(Long.MAX_VALUE)
                    ).distinct()
                all.filter { guid ->
                    val existing = app.downloadsApi.byId.value[guid]
                    existing == null ||
                        existing.state == com.lofipod.app.data.LofiDownload.State.FAILED
                }
            }
            for (guid in rows) {
                fireDeferredAutoDownload(guid)
            }
        }
    }

    /**
     * Sweep auto-downloaded episodes that should expire. Two rules:
     *
     *   1. **Finished + idle 1 h**: episode played to completion AND
     *      `lastPlayedMillis` older than 1 hour. The user is done with
     *      it; reclaim the disk.
     *   2. **Unfinished + idle 32 h**: episode wasn't played to
     *      completion AND `max(autoDownloadCreatedAt, lastPlayedMillis)`
     *      older than 32 hours. The user lost interest; reclaim the
     *      disk. Catches both never-started and started-but-abandoned
     *      cases via the max() clock.
     *
     * Called from [connect] (catches stragglers from prior sessions)
     * and from [playEpisode] (housekeeping on every track switch).
     *
     * Intentionally narrow: only auto-downloads (= rows in `auto_download`)
     * are eligible. User-triggered downloads (no row) are kept until the
     * user manually removes them.
     */
    private fun sweepExpiredAutoDownloads() {
        scope.launch {
            val now = System.currentTimeMillis()
            val finishedCutoff = now - AUTO_DOWNLOAD_FINISHED_TTL_MS
            val unfinishedCutoff = now - AUTO_DOWNLOAD_UNFINISHED_TTL_MS
            val guids = withContext(Dispatchers.IO) {
                val finished = autoDownloadDao.expiringFinishedGuids(finishedCutoff)
                val unfinished = autoDownloadDao.expiringUnfinishedGuids(unfinishedCutoff)
                // De-dupe in case both queries somehow returned the same
                // guid (shouldn't — finished vs not-finished are mutually
                // exclusive — but cheap insurance).
                (finished + unfinished).distinct()
            }
            if (guids.isEmpty()) return@launch
            val app = context.applicationContext as LofiPodApp
            val have = app.downloadsApi.byId.value.keys
            for (g in guids) {
                if (g in have) {
                    app.downloadsApi.remove(g)
                }
                withContext(Dispatchers.IO) {
                    autoDownloadDao.delete(g)
                }
            }
        }
    }

    /**
     * Retry the currently-loaded episode by re-running the full
     * setMediaItem/prepare/play cycle through [playEpisode], reconstructing
     * the Episode from episode_state. Stronger than togglePlay's plain
     * prepare()+play() — any in-memory player state that was confused by
     * the original failure gets reset along with a fresh MediaItem. Used by
     * the error chip's tap-to-retry on PlayerScreen.
     *
     * If the underlying issue is the source URL itself (the feed published
     * a bad URL), the retry will surface the same error — that's correct
     * behavior, the retry isn't a magic wand. The user gets one extra
     * attempt against a transient codec / sink / decoder hiccup.
     */
    fun retryCurrentEpisode() {
        val c = controller ?: return
        val guid = c.currentMediaItem?.mediaId ?: return
        scope.launch {
            val ep = episodeFromState(guid) ?: return@launch
            playEpisode(ep, podcastTitle = "", podcastArt = ep.episodeArtworkUrl)
        }
    }

    /**
     * Reconstruct an [Episode] from a persisted [EpisodeStateEntity]. Used
     * by the live-mode download button and the retry chip — both have the
     * guid but not the full Episode (the live MediaController state has the
     * mediaId but not the audioUrl/feedUrl). Returns null if no row yet.
     */
    private suspend fun episodeFromState(guid: String): Episode? {
        val state = withContext(Dispatchers.IO) { dao.get(guid) } ?: return null
        return Episode(
            guid = state.guid,
            feedUrl = state.feedUrl,
            title = state.title,
            description = null,
            pubDateMillis = null,
            audioUrl = state.audioUrl,
            audioMimeType = null,
            durationSeconds = null,
            episodeArtworkUrl = state.artworkUrl
        )
    }

    /**
     * Smart play/pause toggle. Plain `c.play()` is a no-op when the player
     * is in STATE_IDLE (no prepare yet) or STATE_ENDED (cursor parked at
     * end-of-stream) — that's the "tap Play, nothing happens" symptom.
     * Handle those explicitly:
     *   - IDLE   → re-prepare, then play (recovers from a failed initial
     *              prepare or a release-but-don't-clear cycle).
     *   - ENDED  → seek to 0, then play (replays the same episode from
     *              the start; matches what every other media player does
     *              when you tap Play on a finished item).
     *   - else   → standard pause/play toggle.
     *
     * **Snackbar feedback contract**: any path that doesn't actually
     * advance the player's visible state (controller not bound yet, no
     * MediaItem loaded, already buffering) emits a one-shot message via
     * [transientMessages]. The UI surfaces those as snackbars so the play
     * button never feels like a silent no-op. The buffering ring around
     * the play button is the primary "I see your tap" signal; the
     * snackbar is the second signal for cases where the ring isn't
     * obviously connected to the user's action.
     */
    fun togglePlay() {
        val c = controller ?: run {
            _transientMessages.tryEmit("Player isn't connected yet — try again in a moment.")
            return
        }
        // Autoplay-confirmation window: a tap while the timer is counting
        // and the player is playing means "confirm continuation" — cancel
        // the timer, do NOT toggle to pause. The play button is morphed
        // into a countdown specifically to invite this tap; pausing during
        // the timer takes a second tap (after the morph reverts), which is
        // the intended UX from the spec.
        if (_autoplayTimer.value != null && c.isPlaying) {
            confirmAutoplayContinuation()
            return
        }
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE -> {
                if (c.currentMediaItem == null) {
                    _transientMessages.tryEmit("No episode loaded — pick one from the catalog.")
                    return
                }
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekTo(0)
                c.play()
            }
            c.playbackState == Player.STATE_BUFFERING -> {
                // Player is already trying. Tapping play again won't
                // accelerate buffering; surface a message so the user
                // knows we heard them and what's happening.
                _transientMessages.tryEmit(
                    "Buffering — waiting on the network. Far seek + slow connection takes a bit."
                )
                c.play()  // keeps playWhenReady=true; harmless in BUFFERING.
            }
            else -> c.play()
        }
    }

    fun play() {
        val c = controller ?: return
        // Same recovery logic as togglePlay's play branch — a bare
        // `c.play()` is a no-op in IDLE / ENDED.
        when (c.playbackState) {
            Player.STATE_IDLE -> { c.prepare(); c.play() }
            Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            else -> c.play()
        }
    }
    fun pause() {
        // Explicit pause cancels any active autoplay-confirmation window —
        // the user is awake and pausing intentionally, so the auto-pause
        // would be redundant and the countdown indicator should disappear.
        autoplayTimerJob?.cancel()
        autoplayTimerJob = null
        _autoplayTimer.value = null
        controller?.pause()
    }

    /**
     * Begin polling [Player.currentPosition] to detect renderer stalls.
     * Started by [Player.Listener.onIsPlayingChanged] when isPlaying flips
     * to true; stopped on the inverse. See the field-level docstring on
     * [stallWatchdogJob] for the why.
     */
    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        // Reset baselines so a freshly-started playback isn't considered
        // stalled if the previous run left timestamps stale.
        lastForwardProgressPosMs = controller?.currentPosition ?: 0L
        lastForwardProgressAtMs = System.currentTimeMillis()
        bufferingStartedAtMs = 0L
        playbackSamples.clear()
        lastStickyStallAtMs = 0L
        stallWatchdogJob = scope.launch {
            while (isActive) {
                delay(STALL_POLL_INTERVAL_MS)
                val c = controller ?: continue
                if (!c.playWhenReady) {
                    // User paused. Reset both timers so a long pause doesn't
                    // pre-trip either watchdog arm on resume. Also drop the
                    // sticky ring buffer — a paused gap shouldn't count
                    // toward the oscillation window.
                    bufferingStartedAtMs = 0L
                    lastForwardProgressAtMs = System.currentTimeMillis()
                    playbackSamples.clear()
                    continue
                }
                val now = System.currentTimeMillis()

                // Arm C — sticky oscillation detector. Runs UNCONDITIONALLY
                // (regardless of isPlaying / playbackState) so a BUFFERING↔
                // READY flip-flop still accumulates samples. See the field-
                // level docstring on [playbackSamples] for the why.
                stickyOscillationCheck(c, now)

                // Arm B — chronic STATE_BUFFERING with playWhenReady=true.
                // Distinct from arm A: when the AudioTrack underruns badly
                // enough, ExoPlayer transitions out of STATE_READY into
                // STATE_BUFFERING with isPlaying=false, so arm A's
                // forward-progress check stops running. Arm B picks up that
                // slack — multi-second buffering on a local file means the
                // renderer chain is wedged, not "waiting for network."
                if (c.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingStartedAtMs == 0L) {
                        bufferingStartedAtMs = now
                    } else if (now - bufferingStartedAtMs >= BUFFERING_STALL_THRESHOLD_MS) {
                        triggerStallRecovery(c, now, "buffering_${(now - bufferingStartedAtMs) / 1000}s")
                        bufferingStartedAtMs = now
                        lastForwardProgressPosMs = c.currentPosition
                        lastForwardProgressAtMs = now
                    }
                    continue
                } else {
                    bufferingStartedAtMs = 0L
                }

                // Arm A — cycling-position underrun. State is READY/PLAYING
                // but currentPosition isn't advancing. Track a running max so
                // a position-cycle (43:31→43:36→43:31→...) trips this even
                // though the position is "moving" within a cycle.
                if (!c.isPlaying) continue
                val pos = c.currentPosition
                if (pos > lastForwardProgressPosMs) {
                    lastForwardProgressPosMs = pos
                    lastForwardProgressAtMs = now
                    continue
                }
                if (now - lastForwardProgressAtMs >= STALL_THRESHOLD_MS) {
                    val stalledForMs = now - lastForwardProgressAtMs
                    triggerStallRecovery(c, now, "no_progress_${stalledForMs / 1000}s")
                    // Reset baseline so we don't re-trigger immediately if
                    // the seek itself takes a moment to register a new
                    // currentPosition reading.
                    lastForwardProgressPosMs = c.currentPosition
                    lastForwardProgressAtMs = now
                }
            }
        }
    }

    /**
     * Sticky oscillation check (arm C). Maintains a ring buffer of
     * (wallclockMs, positionMs) samples over the last
     * [STALL_STICKY_WINDOW_MS] window. When the ring is full enough,
     * compares actual position advance to speed-adjusted wall-clock advance.
     * Trips when the ratio falls below [STALL_STICKY_MIN_ADVANCE_RATIO].
     *
     * Key invariant vs arms A/B: this method does NOT reset its state on
     * isPlaying / playbackState transitions, so a 30-second window of
     * BUFFERING↔READY oscillation that advances only 5 seconds of audio
     * still trips (5/30 × 1.0× = 16.7% advance, below the 30% threshold).
     */
    private fun stickyOscillationCheck(c: MediaController, now: Long) {
        val pos = c.currentPosition
        // Guard against the position-rewinding case (seek backwards) — drop
        // the buffer and re-arm so the next window evaluates the fresh
        // playback span instead of carrying a discontinuity.
        if (playbackSamples.isNotEmpty() && pos < playbackSamples.last().second - 1_000) {
            playbackSamples.clear()
        }
        playbackSamples.addLast(now to pos)
        // Trim old samples (older than window). Capped at a defensive
        // ceiling so the deque never balloons if the loop runs longer than
        // expected.
        while (playbackSamples.isNotEmpty() &&
            now - playbackSamples.first().first > STALL_STICKY_WINDOW_MS
        ) {
            playbackSamples.removeFirst()
        }
        // Need a full window of samples before evaluating — otherwise a
        // legitimate slow seek + buffer at the start of playback would
        // trip on a 3-sample window.
        if (playbackSamples.size < STALL_STICKY_MIN_SAMPLES) return
        // Throttle: don't re-fire if we already triggered within the last
        // window. Recovery seek itself takes a moment to settle.
        if (now - lastStickyStallAtMs < STALL_STICKY_WINDOW_MS) return

        val (firstAt, firstPos) = playbackSamples.first()
        val wallWindowMs = now - firstAt
        val posAdvanceMs = pos - firstPos
        val speed = c.playbackParameters.speed
        val expectedAdvanceMs = (wallWindowMs.toDouble() * speed).toLong()
        if (expectedAdvanceMs <= 0L) return
        val ratio = posAdvanceMs.toDouble() / expectedAdvanceMs
        if (ratio < STALL_STICKY_MIN_ADVANCE_RATIO) {
            lastStickyStallAtMs = now
            val pct = (ratio * 100.0).coerceAtLeast(0.0).toInt()
            triggerStallRecovery(
                c,
                now,
                "sticky_${pct}pct_over_${wallWindowMs / 1000}s",
            )
            // Drop the buffer so the next evaluation starts fresh against
            // post-recovery samples.
            playbackSamples.clear()
        }
    }

    /**
     * Force-flush the renderer chain after the watchdog detects either a
     * cycling-position stall (arm A) or chronic buffering (arm B). Steps:
     *   1. Mark [PlayerState.isStalled] so the UI shows the buffering ring
     *      around the play button. Auto-cleared after a short linger.
     *   2. Emit a throttled snackbar nudging the user toward a lower speed
     *      or disabling linear-phase EQ — the most common DSP-can't-keep-up
     *      causes for the underrun.
     *   3. Seek to currentPosition − 100 ms. The negative offset is the
     *      important part: a same-position seek can be deduped by Media3 /
     *      the audio sink and produce no flush at all (which is what made
     *      the previous `seekTo(currentPosition)` recovery sometimes silently
     *      fail). 100 ms back guarantees a real flush + a brief replay of
     *      the audio that was likely lost during the stall.
     *   4. Append a breadcrumb to AppDiagnostics so the diagnostics screen
     *      can show how often this fires across builds.
     */
    private fun triggerStallRecovery(c: MediaController, nowMs: Long, kind: String) {
        val recoveryPosMs = c.currentPosition
        val speed = c.playbackParameters.speed

        _state.update { it.copy(isStalled = true) }
        scope.launch {
            delay(STALL_INDICATOR_LINGER_MS)
            _state.update { it.copy(isStalled = false) }
        }

        if (nowMs - lastStallMessageAtMs >= STALL_MESSAGE_THROTTLE_MS) {
            _transientMessages.tryEmit(
                "Audio chain stalled at ${"%.2fx".format(speed)} — recovering. " +
                    "Try a lower speed or disabling linear-phase EQ if this keeps happening."
            )
            lastStallMessageAtMs = nowMs
        }

        // Seek-back-100ms forces a real flush. Same-position seek can be
        // deduped; this can't.
        val flushTarget = (recoveryPosMs - STALL_RECOVERY_REWIND_MS).coerceAtLeast(0L)
        c.seekTo(flushTarget)
        com.lofipod.app.diagnostics.AppDiagnostics.recordOther(
            identifier = "renderer_stall",
            detail = "Force-flushed at ${recoveryPosMs / 1000}s ($kind, speed=${"%.2fx".format(speed)}).",
        )
    }

    private fun stopStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
    }

    /**
     * Observe [LofiPodDownloader.byId] for the currently-playing episode.
     * When a download completes for the same guid the player is currently
     * streaming over HTTP, swap the MediaItem to the local `file://` URI
     * so the remainder of playback reads from disk — saves bandwidth +
     * eliminates the buffer-runs-empty-at-2x failure mode.
     *
     * Mechanics:
     *   1. Snapshot current position + playWhenReady.
     *   2. Build a new MediaItem with the file:// URI (same metadata).
     *   3. Fire a quick double-beep cue in parallel via [BeepPlayer.playHandoffCue]
     *      — the cue fills the brief silence from setMediaItem + prepare,
     *      partially disguising the swap so the user reads it as
     *      intentional rather than a glitch.
     *   4. setMediaItem(newItem, savedPos); prepare(); play() if was playing.
     *   5. Emit a snackbar via [transientMessages]: "Playback branched to
     *      downloaded file."
     *
     * Skipped when:
     *   - No episode currently playing
     *   - The current MediaItem is already a `file://` URI (already on disk)
     *   - We've already triggered handoff for this guid (race guard)
     */
    private suspend fun observeDownloadCompletion() {
        val app = context.applicationContext as LofiPodApp
        app.downloadsApi.byId.collect { map ->
            val c = controller ?: return@collect
            val currentGuid = c.currentMediaItem?.mediaId ?: return@collect
            val currentUri = c.currentMediaItem?.localConfiguration?.uri ?: return@collect
            val scheme = currentUri.scheme?.lowercase()
            val download = map[currentGuid]

            // Reset the per-guid handoff guard if the episode changed OR if
            // our guid's download is no longer in the map (= it was removed,
            // either via the manual delete-download tap or the auto-archive
            // sweep). The guid-change check stays so a stale guard from a
            // prior episode doesn't block a fresh handoff on the new one.
            if (handoffTriggeredForGuid != null &&
                (handoffTriggeredForGuid != currentGuid || download == null)
            ) {
                handoffTriggeredForGuid = null
            }

            // Reverse handoff: we're playing from a file:// URI for an
            // episode whose download just disappeared (state map no longer
            // has the entry, OR the entry transitioned out of COMPLETED).
            // Without this branch, the player keeps a MediaItem pointing
            // at a now-deleted file and the next read or seek explodes
            // with ERROR_CODE_IO_FILE_NOT_FOUND. Re-build the MediaItem
            // against the persisted HTTP audioUrl, preserve position +
            // playWhenReady, and let OkHttpDataSource take over.
            //
            // Conditions:
            //   - We're currently on a file:// MediaItem (= we previously
            //     handed off to disk, or the user tapped Play on an
            //     already-downloaded episode).
            //   - The download record is absent OR not COMPLETED — either
            //     means the on-disk file is no longer trustworthy.
            //   - We have a persisted episode_state row so we can
            //     reconstruct the HTTP URL.
            if (scheme == "file") {
                val gone = download == null ||
                    download.state != com.lofipod.app.data.LofiDownload.State.COMPLETED
                if (gone) {
                    val ep = withContext(Dispatchers.IO) { episodeFromState(currentGuid) }
                    if (ep != null && ep.audioUrl.startsWith("http", ignoreCase = true)) {
                        triggerReverseDownloadHandoff(c, currentGuid, ep.audioUrl)
                    }
                }
                return@collect
            }

            // Forward handoff (existing behavior): we're streaming over HTTP
            // and the download just completed. Swap to the local file so the
            // remainder of playback reads from disk.
            if (handoffTriggeredForGuid == currentGuid) return@collect
            if (download == null) return@collect
            if (download.state != com.lofipod.app.data.LofiDownload.State.COMPLETED) return@collect
            // Get the local file (race-safe via the suspend completedFile).
            val localFile = app.downloadsApi.completedFile(currentGuid) ?: return@collect

            handoffTriggeredForGuid = currentGuid
            triggerDownloadHandoff(c, currentGuid, localFile)
        }
    }

    /**
     * Symmetric to [triggerDownloadHandoff] — swaps the live MediaItem from
     * a `file://` URI back to the original `http(s)://` URL when the
     * underlying download disappears mid-playback (manual delete or auto-
     * archive sweep). Preserves position + playWhenReady so the user
     * doesn't notice anything beyond the snackbar telling them what
     * happened. Clears [handoffTriggeredForGuid] so a future re-download
     * of the same episode can hand off forward again.
     */
    private fun triggerReverseDownloadHandoff(
        c: MediaController,
        guid: String,
        httpUrl: String,
    ) {
        val savedPosMs = c.currentPosition
        val wasPlaying = c.playWhenReady
        // Reuse existing metadata (title / artist / artwork / extras) so
        // the swap doesn't blink the UI. Extras carry feedUrl so back-nav
        // stays correct across the swap.
        val metadata = c.mediaMetadata
        val newItem = MediaItem.Builder()
            .setMediaId(guid)
            .setUri(httpUrl)
            .setMediaMetadata(metadata)
            .build()
        _transientMessages.tryEmit("Download removed — resuming from stream.")
        com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
            "handoff_reverse",
            "guid=$guid at ${savedPosMs / 1000}s (file removed mid-playback)",
        )
        c.setMediaItem(newItem, savedPosMs)
        c.prepare()
        if (wasPlaying) c.play()
        // Clear the forward-handoff guard so if the user re-downloads the
        // same episode without changing tracks, the forward swap re-arms.
        handoffTriggeredForGuid = null
    }

    private suspend fun triggerDownloadHandoff(
        c: MediaController,
        guid: String,
        localFile: java.io.File,
    ) {
        val savedPosMs = c.currentPosition
        val wasPlaying = c.playWhenReady
        // Reuse the existing MediaMetadata so the title / artist / artwork
        // don't blink during the swap.
        val metadata = c.mediaMetadata
        val newItem = MediaItem.Builder()
            .setMediaId(guid)
            .setUri(android.net.Uri.fromFile(localFile))
            .setMediaMetadata(metadata)
            .build()

        // Fire the audible cue + snackbar in parallel with the swap. The
        // cue uses a fresh BeepPlayer (light — buffers + a transient
        // AudioTrack per beep) and is released in finally.
        val cuePlayer = com.lofipod.app.audio.BeepPlayer(c)
        scope.launch {
            try {
                cuePlayer.playHandoffCue()
            } finally {
                cuePlayer.release()
            }
        }
        _transientMessages.tryEmit("Playback branched to downloaded file.")
        com.lofipod.app.diagnostics.AppDiagnostics.recordPlayback(
            "handoff_forward",
            "guid=$guid at ${savedPosMs / 1000}s (download completed mid-stream)",
        )

        // Swap. setMediaItem(item, position) bundles a seek so the player
        // prepares the new source AT savedPosMs rather than 0; saves a
        // separate seekTo call and the latency that comes with it.
        c.setMediaItem(newItem, savedPosMs)
        c.prepare()
        // Restore play state. ExoPlayer respects playWhenReady across a
        // setMediaItem; explicit play() is belt-and-braces in case the
        // controller's flag was reset by the swap.
        if (wasPlaying) c.play()
    }

    /**
     * Arm the autoplay-confirmation timer for [guid]. Reads the
     * `autoplayConfirmEnabled` setting first; if disabled, the autoplay
     * episode plays through with no timer (legacy behavior pre-v0.4.5).
     *
     * Schedule, anchored to [SystemClock.elapsedRealtime] at start so the
     * marks don't drift even though each beep sequence takes hundreds of
     * milliseconds:
     *   - T=60s   (first beep)  — 1 ducked beep via [BeepPlayer].
     *   - T=120s  (second beep) — 2 ducked beeps, ~333ms apart.
     *   - T=180s  (third beep)  — 3 ducked beeps.
     *   - T=190s  (auto-pause)  — pause if still on the same episode and
     *                             still playing. Guid identity guards
     *                             against a manual play having swapped the
     *                             loaded episode mid-window; the
     *                             [Player.isPlaying] check is belt-and-
     *                             suspenders, since [pause] /
     *                             [confirmAutoplayContinuation] cancel the
     *                             job before reaching the pause line.
     *
     * Calling this with an existing timer job cancels the prior one — at
     * most one autoplay timer is active at a time.
     */
    private fun maybeStartAutoplayTimer(guid: String) {
        autoplayTimerJob?.cancel()
        autoplayTimerJob = scope.launch {
            val enabled = com.lofipod.app.data.Settings(context)
                .autoplayConfirmEnabled.first()
            if (!enabled) {
                _autoplayTimer.value = null
                return@launch
            }
            val started = android.os.SystemClock.elapsedRealtime()
            val mySnapshot = AutoplayTimerState(
                episodeGuid = guid,
                startedAtElapsedMs = started,
                totalDurationMs = AUTOPLAY_CONFIRM_TOTAL_MS,
            )
            _autoplayTimer.value = mySnapshot
            // Scheduled against absolute elapsed-realtime targets rather than
            // chained relative delays — keeps the strike marks pinned at
            // 60s / 120s / 180s / 190s from autoplay start, even though the
            // beep sequences themselves take ~0.2 / ~0.7 / ~1.3 seconds and
            // would otherwise drift the schedule later in the window.
            val player = controller
            val beepPlayer = player?.let { com.lofipod.app.audio.BeepPlayer(it) }
            try {
                delayUntilElapsed(started + AUTOPLAY_CONFIRM_FIRST_BEEP_MS)
                beepPlayer?.playBeeps(1)
                delayUntilElapsed(started + AUTOPLAY_CONFIRM_SECOND_BEEP_MS)
                beepPlayer?.playBeeps(2)
                delayUntilElapsed(started + AUTOPLAY_CONFIRM_THIRD_BEEP_MS)
                beepPlayer?.playBeeps(3)
                delayUntilElapsed(started + AUTOPLAY_CONFIRM_TOTAL_MS)
                val cc = controller
                if (cc != null && cc.currentMediaItem?.mediaId == guid && cc.isPlaying) {
                    // Pre-clear the timer state BEFORE issuing pause. Otherwise
                    // [PlaybackService]'s MediaSession-callback intercept would
                    // see the timer still active and treat our own auto-pause
                    // command as a remote BT press → confirm + skip → playback
                    // would not actually pause. CAS so we don't clobber a
                    // newer timer's snapshot in the unlikely re-arm race.
                    _autoplayTimer.compareAndSet(mySnapshot, null)
                    android.util.Log.i(
                        "LofiPodPlayer",
                        "Autoplay confirmation timed out for $guid — auto-pausing"
                    )
                    cc.pause()
                }
            } finally {
                beepPlayer?.release()
                // CAS-clear so we only wipe state we still own. A newer timer
                // that superseded us (cancel + relaunch from a fresh
                // [maybeStartAutoplayTimer] / [confirmAutoplayContinuation])
                // has already replaced [mySnapshot] with its own value, and
                // our finally must not clobber that. compareAndSet returns
                // false silently in that case.
                _autoplayTimer.compareAndSet(mySnapshot, null)
            }
        }
    }

    /**
     * Suspend until [SystemClock.elapsedRealtime] reaches [targetElapsedMs],
     * computing the remaining delay each call so the schedule stays anchored
     * to an absolute reference point even if intermediate work (beep playback,
     * volume ducking) takes non-negligible time. No-op if the target is
     * already in the past.
     */
    private suspend fun delayUntilElapsed(targetElapsedMs: Long) {
        val remaining = targetElapsedMs - android.os.SystemClock.elapsedRealtime()
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
    }

    /**
     * Confirm the user wants the autoplay-induced episode to keep playing.
     * Cancels the pending auto-pause and clears the countdown indicator.
     * Idempotent. Called from:
     *   - The play-button tap path during the timer ([togglePlay] above).
     *   - The MediaSession KEYCODE_MEDIA_PLAY_PAUSE intercept when a BT or
     *     vehicle transport press arrives while the timer is active
     *     (wired in PlaybackService — phase 4).
     */
    fun confirmAutoplayContinuation() {
        autoplayTimerJob?.cancel()
        autoplayTimerJob = null
        _autoplayTimer.value = null
    }

    /**
     * Jump to an arbitrary (episode, position). Records a jump_from checkpoint for the
     * current position before moving. Updates [pendingReturn] so the UI can offer a
     * one-tap return.
     */
    fun jumpToPosition(targetGuid: String, targetPositionMs: Long) {
        val c = controller ?: return
        val currentGuid = c.currentMediaItem?.mediaId
        val currentPos = if (currentGuid != null) c.currentPosition else 0L

        if (currentGuid != null && currentPos > 0) {
            scope.launch(Dispatchers.IO) {
                checkpointDao.insert(
                    PlaybackCheckpointEntity(
                        guid = currentGuid,
                        positionMs = currentPos,
                        recordedAt = System.currentTimeMillis(),
                        reason = REASON_JUMP_FROM
                    )
                )
                checkpointDao.pruneToCount(CHECKPOINT_CAP)
            }
            _pendingReturn.value = PendingReturn(
                guid = currentGuid,
                positionMs = currentPos,
                createdAt = System.currentTimeMillis()
            )
        }

        if (currentGuid == targetGuid) {
            c.seekTo(targetPositionMs)
            c.play()
        } else {
            scope.launch {
                val state = withContext(Dispatchers.IO) { dao.get(targetGuid) } ?: return@launch
                val ep = Episode(
                    guid = state.guid,
                    feedUrl = state.feedUrl,
                    title = state.title,
                    description = null,
                    pubDateMillis = null,
                    audioUrl = state.audioUrl,
                    audioMimeType = null,
                    durationSeconds = null,
                    episodeArtworkUrl = state.artworkUrl
                )
                playEpisode(
                    ep,
                    podcastTitle = "",
                    podcastArt = state.artworkUrl,
                    forcedStartMs = targetPositionMs
                )
            }
        }
    }

    /** Convenience for note-driven jumps. */
    fun jumpToNotePosition(noteEntry: EpisodeNoteEntryEntity) {
        jumpToPosition(noteEntry.guid, noteEntry.playbackPosMs)
    }

    /** Convenience for history-driven jumps. */
    fun jumpToCheckpoint(cp: PlaybackCheckpointEntity) {
        jumpToPosition(cp.guid, cp.positionMs)
    }

    /** Take the user back to where they were before the most recent jump. */
    fun consumePendingReturn() {
        val pr = _pendingReturn.value ?: return
        _pendingReturn.value = null
        jumpToPosition(pr.guid, pr.positionMs)
    }

    fun dismissPendingReturn() { _pendingReturn.value = null }

    fun seekRelative(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    /**
     * Seek back by the player's configured seekBackIncrementMs (set on the
     * ExoPlayer in [PlaybackService]). Used by both the on-screen back button
     * and — via MediaSession — Bluetooth headphones / vehicle media controls
     * (KEYCODE_MEDIA_REWIND). Single source of truth so a future "adjustable
     * skip increments" setting only has to flow into the ExoPlayer config.
     */
    fun seekBack() { controller?.seekBack() }

    /** Forward equivalent of [seekBack]; uses seekForwardIncrementMs. */
    fun seekForward() { controller?.seekForward() }

    fun seekTo(positionMs: Long) {
        // Compensate the requested UI position by the chain's algorithmic
        // delay so the AUDIBLE result lands at the position the user asked
        // for. Without compensation, a tap on "1:00" lands the user's ear
        // ~46 ms before "1:00" in linear-phase mode (~6 ms in min-phase) —
        // sub-perceptible alone, but compounds visibly when seeking
        // repeatedly to a chapter marker or to a saved note position.
        // See _LOFIPOD_V1_BRIEF.md §A4.
        val latUs = PlaybackService.sharedEq.getChainLatencyUs()
        val target = positionMs + latUs / 1000L
        controller?.seekTo(target)
    }

    /**
     * Force a Media3 audio-pipeline flush at the current position. Triggers
     * AudioProcessor.flush() on every processor in the chain (clears chain
     * state) AND drains the AudioTrack of any pre-flush PCM that was queued
     * ahead.
     *
     * Used by:
     *   - The EQ screen on phase-mode changes (v0.10.1+) — without this,
     *     the 1.5-3s of pre-switch PCM in the AudioTrack continues to play
     *     through the old mode's settings, and FIR modes can show audible
     *     contamination from the prior mode's chain state.
     *   - The optional flush-valve button in the player screen (v0.10.1+) — a
     *     manual user-triggered flush for cases where the chain seems off
     *     and a forced reset is the fastest fix.
     *
     * Mechanism: seek to currentPosition minus a small offset (default
     * 50 ms). Same-position seeks are sometimes deduped by Media3; the
     * small negative offset guarantees a real flush. Audible cost: ~50 ms
     * of audio replays. Acceptable for a manual / mode-switch action.
     */
    fun flushAudio(rewindMs: Long = 50L) {
        val c = controller ?: return
        val current = c.currentPosition
        val target = (current - rewindMs).coerceAtLeast(0L)
        c.seekTo(target)
        com.lofipod.app.diagnostics.AppDiagnostics.recordOther(
            identifier = "manual_flush",
            detail = "Force-flushed at ${current / 1000}s (rewind=${rewindMs}ms).",
        )
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        // Mirror speed into AudioChainTelemetry immediately so the PerfHint
        // bridge sees the new wall-clock budget on the very next buffer. The
        // onPlaybackParametersChanged listener also writes it (covers session-
        // side originators), but writing here too closes the window between
        // setPlaybackSpeed and the listener fire.
        com.lofipod.app.audio.AudioChainTelemetry.playbackSpeed = clamped
        controller?.setPlaybackSpeed(clamped)
    }

    /**
     * Latency-compensated position for UI consumers (scrubber, time
     * readout). Subtracts the audio chain's algorithmic delay from
     * `controller.currentPosition` so the reading matches what the user is
     * audibly hearing right now.
     *
     * **Do not use this for the stall watchdog, DB persistence, or
     * checkpoints.** Those should call `controller.currentPosition`
     * directly — the watchdog's forward-progress invariant is about raw
     * frames advancing through the audio sink, and DB persistence is
     * about resuming at the same raw frame on next play (so the chain's
     * delay applies symmetrically on both sides). See
     * _LOFIPOD_V1_BRIEF.md §A4.
     */
    fun currentPositionMs(): Long {
        val raw = controller?.currentPosition ?: 0L
        val latMs = PlaybackService.sharedEq.getChainLatencyUs() / 1000L
        return (raw - latMs).coerceAtLeast(0L)
    }
    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    // ---------- Queue ----------

    /**
     * Append [ep] to the end of the queue. If nothing is currently loaded in the
     * player, optionally start playback immediately.
     */
    fun enqueue(ep: Episode, podcastTitle: String, podcastArt: String?, playIfIdle: Boolean = false) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val maxPos = queueDao.maxPosition() ?: 0L
                queueDao.upsert(
                    QueueEntryEntity(
                        guid = ep.guid,
                        feedUrl = ep.feedUrl,
                        title = ep.title,
                        audioUrl = ep.audioUrl,
                        artworkUrl = ep.episodeArtworkUrl ?: podcastArt,
                        position = maxPos + STEP,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
            if (playIfIdle && controller?.currentMediaItem == null) {
                playEpisode(ep, podcastTitle, podcastArt)
            }
        }
    }

    /** Insert [ep] at the very front of the queue (next up). */
    fun enqueueNext(ep: Episode, podcastTitle: String, podcastArt: String?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val all = queueDao.getAll()
                val minPos = all.minOfOrNull { it.position } ?: 0L
                queueDao.upsert(
                    QueueEntryEntity(
                        guid = ep.guid,
                        feedUrl = ep.feedUrl,
                        title = ep.title,
                        audioUrl = ep.audioUrl,
                        artworkUrl = ep.episodeArtworkUrl ?: podcastArt,
                        position = minPos - STEP,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun removeFromQueue(guid: String) {
        scope.launch(Dispatchers.IO) { queueDao.remove(guid) }
    }

    fun clearQueue() {
        scope.launch(Dispatchers.IO) { queueDao.clear() }
    }

    /**
     * Reorder the queue to match [orderedGuids]. Positions are rewritten dense from
     * STEP, 2*STEP, … so subsequent enqueueNext / enqueue stay well-behaved.
     */
    fun reorderQueue(orderedGuids: List<String>) {
        scope.launch(Dispatchers.IO) {
            val existing = queueDao.getAll().associateBy { it.guid }
            val rewritten = orderedGuids.mapIndexedNotNull { i, guid ->
                existing[guid]?.copy(position = (i + 1) * STEP)
            }
            queueDao.upsertAll(rewritten)
        }
    }

    /**
     * Pull the next entry (excluding [excluding]) and start playing it.
     *
     * Lookup order:
     *  1. The user's queue, lowest position first.
     *  2. If queue is empty AND auto-play-next-in-feed is enabled in settings,
     *     the next published episode in the just-finished episode's feed —
     *     defined as the most-recent (highest pubDate) episode that isn't the
     *     one we just finished and hasn't already been played to completion.
     *
     * Falls through silently when neither lookup yields anything.
     */
    /**
     * If the user has canon-order autoplay enabled AND [finishedGuid]
     * has a detected scripture ref, find the next sermon in canon order
     * and play it. Returns true if a play was issued (caller skips the
     * normal queue advance), false otherwise.
     *
     * "Next in canon" = strictly after the finished episode's start
     * passage, regardless of feed or pubDate. Driven by
     * [com.lofipod.app.data.db.EpisodeScriptureDao.nextInCanon].
     *
     * Excluded feeds (per `Settings.canonBrowseExcludedFeeds`) are NOT
     * applied here — the user explicitly opted into canon-order play
     * for the current episode's book; respecting browse-time exclusions
     * here would silently skip sermons mid-series.
     */
    private suspend fun tryAdvanceToNextInCanon(finishedGuid: String): Boolean {
        val app = context.applicationContext as LofiPodApp
        val settings = com.lofipod.app.data.Settings(app)
        val enabled = withContext(Dispatchers.IO) {
            settings.canonAutoplayEnabled.first()
        }
        if (!enabled) return false
        val current = withContext(Dispatchers.IO) {
            app.db.episodeScriptureDao().get(finishedGuid)
        } ?: return false
        // Use the END passage as the "current position" so the next sermon
        // genuinely starts after this one. Fall back to start if endCh is
        // missing.
        val ch = current.endCh ?: current.startCh ?: return false
        val v = current.endV ?: current.startV ?: 0
        val next = withContext(Dispatchers.IO) {
            app.db.episodeScriptureDao().nextInCanon(
                book = current.book,
                ch = ch,
                v = v,
                excludeGuid = finishedGuid,
            )
        } ?: run {
            // End of book reached. Clear the flag so the user isn't
            // stuck in canon mode for the next session and surprised
            // by it on a different episode.
            withContext(Dispatchers.IO) { settings.setCanonAutoplayEnabled(false) }
            return false
        }
        val ep = episodeFromState(next.guid) ?: return false
        playEpisode(ep = ep, podcastTitle = "", podcastArt = ep.episodeArtworkUrl)
        return true
    }

    private suspend fun advanceToNextInQueue(excluding: String?) {
        val app = context.applicationContext as LofiPodApp

        val queueNext = withContext(Dispatchers.IO) {
            if (excluding != null) queueDao.remove(excluding)
            queueDao.nextAfter(excludingGuids = listOfNotNull(excluding))
        }

        if (queueNext != null) {
            val cached = app.repo.cached(queueNext.feedUrl)
            val ep = cached?.episodes?.find { it.guid == queueNext.guid } ?: Episode(
                guid = queueNext.guid,
                feedUrl = queueNext.feedUrl,
                title = queueNext.title,
                description = null,
                pubDateMillis = null,
                audioUrl = queueNext.audioUrl,
                audioMimeType = null,
                durationSeconds = null,
                episodeArtworkUrl = queueNext.artworkUrl
            )
            lastPlayWasAutoplay = true
            playEpisode(ep, cached?.title ?: "", cached?.artworkUrl ?: queueNext.artworkUrl)
            return
        }

        // Queue empty — fall back to the next episode in the same feed if the
        // user has the setting on.
        val settings = com.lofipod.app.data.Settings(app)
        val enabled = settings.autoPlayNextInFeed.first()
        if (!enabled || excluding == null) return

        val finishedFeedUrl = withContext(Dispatchers.IO) { dao.get(excluding)?.feedUrl }
            ?: return
        val pod = app.repo.cached(finishedFeedUrl) ?: return

        val playedGuids = withContext(Dispatchers.IO) {
            // Anything previously played to completion gets skipped — keeps the
            // auto-advance from looping the user back through episodes they
            // already finished.
            dao.getByGuids(pod.episodes.map { it.guid })
                .filter { it.durationMs > 0 && it.positionMs >= it.durationMs - 5_000 }
                .map { it.guid }
                .toSet()
        }

        // Direction-aware "next adjacent" walk. Episode list in the UI is
        // sorted newest-first by pubDate, so:
        //   directionUp = true  → look for the immediately-newer unplayed episode
        //                         (smallest pubDate strictly greater than the
        //                         finished one). If none, autoplay stops.
        //   directionUp = false → look for the immediately-older unplayed episode
        //                         (largest pubDate strictly less than the
        //                         finished one). If none, autoplay stops.
        // This deliberately avoids wrapping around the ends of the list — when
        // the listener reaches a boundary, we let them choose what to do next.
        val directionUp = settings.autoplayDirectionUp.first()
        val finished = pod.episodes.firstOrNull { it.guid == excluding }
        val finishedPub = finished?.pubDateMillis
        val unplayed = pod.episodes.filter { it.guid != excluding && it.guid !in playedGuids }
        // Wrapped in parens so `?: return` binds to the whole when-result;
        // without them, Kotlin parses the elvis as part of the last branch.
        val candidate = (when {
            finishedPub == null -> {
                // Source has no pubDate for the finished episode — fall back
                // to the pre-direction default (absolute newest unplayed) so
                // we don't just refuse to advance on broken metadata.
                unplayed.maxByOrNull { it.pubDateMillis ?: Long.MIN_VALUE }
            }
            directionUp -> unplayed
                .filter { (it.pubDateMillis ?: Long.MIN_VALUE) > finishedPub }
                .minByOrNull { it.pubDateMillis ?: Long.MAX_VALUE }
            else -> unplayed
                .filter { (it.pubDateMillis ?: Long.MAX_VALUE) < finishedPub }
                .maxByOrNull { it.pubDateMillis ?: Long.MIN_VALUE }
        }) ?: return

        lastPlayWasAutoplay = true
        playEpisode(candidate, pod.title, pod.artworkUrl)
    }

    /**
     * Record a "promoted to most-excellent" checkpoint for [guid]. Position is
     * the live player position when the promoted episode is the one currently
     * loaded; otherwise the saved position from episode_state (0 if no row
     * exists yet — a tap on the history row will then play from the start,
     * which is fine for promotions made before the episode is ever played).
     *
     * Fired by the heart-cycle UI whenever an episode transitions into tier 2
     * so the global history captures the moment each standout was anointed.
     */
    fun recordMostExcellentPromotion(guid: String) {
        val livePos = controller?.let { c ->
            if (c.currentMediaItem?.mediaId == guid) c.currentPosition else null
        }
        scope.launch(Dispatchers.IO) {
            val pos = livePos ?: (dao.get(guid)?.positionMs ?: 0L)
            checkpointDao.insert(
                PlaybackCheckpointEntity(
                    guid = guid,
                    positionMs = pos,
                    recordedAt = System.currentTimeMillis(),
                    reason = REASON_PROMOTED_TO_MOST_EXCELLENT
                )
            )
            checkpointDao.pruneToCount(CHECKPOINT_CAP)
        }
    }

    companion object {
        /** Key under which [playEpisode] / [restoreLastEpisodeIfNeeded] stash
         *  the current episode's feed URL inside the MediaMetadata extras
         *  bundle, so [pushState] can surface it on [PlayerState.currentFeedUrl]
         *  for feed-aware back-nav in MainActivity. Read-only from outside;
         *  the controller owns both the write and the read. */
        const val EXTRA_FEED_URL = "feed_url"

        const val SESSION_GAP_MS = 30L * 60 * 1000        // 30 min
        const val CHECKPOINT_CAP = 200
        /**
         * Time after an auto-downloaded episode finishes playing before
         * its download is eligible for removal. User spec — episodes the
         * user didn't manually download should free up disk shortly after
         * they're done with them, but not immediately (so a quick
         * re-listen doesn't have to re-fetch the file).
         */
        const val AUTO_DOWNLOAD_FINISHED_TTL_MS = 60L * 60 * 1000   // 1 hour

        /**
         * Time after an auto-downloaded episode was last touched (whichever
         * is later: the auto-download fire, or the most recent playback
         * tick) before an UNFINISHED episode's auto-download is eligible
         * for removal. Longer than the finished TTL because not finishing
         * is ambiguous — user might come back to it — but bounded so
         * orphaned auto-downloads don't accumulate forever on disk for
         * episodes the user lost interest in.
         */
        const val AUTO_DOWNLOAD_UNFINISHED_TTL_MS = 32L * 60 * 60 * 1000   // 32 hours
        const val REASON_JUMP_FROM = "jump_from"
        const val REASON_SESSION_END = "session_end"
        const val REASON_PROMOTED_TO_MOST_EXCELLENT = "promoted_to_most_excellent"

        /** Stall watchdog poll period. 1 s — twice as frequent as v0.6.16
         *  so the user sees the indicator + snackbar within a second of
         *  the watchdog deciding to act. CPU cost is negligible (one
         *  position read + a few comparisons per second). */
        private const val STALL_POLL_INTERVAL_MS = 1_000L

        /** No-forward-progress duration that counts as a stall. Tightened
         *  from 10 s (v0.6.16) → 6 s after a user report of "almost
         *  immediate" cycling on a downloaded episode at 2x. The cycling
         *  has a ~5 s period; 6 s catches the first complete cycle
         *  without false-positive on legitimate buffering. */
        private const val STALL_THRESHOLD_MS = 6_000L

        /** How long [PlayerState.isStalled] stays true after a recovery
         *  seek before it's auto-cleared. Long enough for the user to see
         *  the indicator + read the snackbar; short enough that the ring
         *  doesn't linger past the recovery itself if it succeeded. */
        private const val STALL_INDICATOR_LINGER_MS = 2_000L

        /** Minimum gap between stall snackbars. Stops a chronic-stall
         *  cycle from spamming the user every recovery — first occurrence
         *  fires the message, the next ~30 s of stalls just get the
         *  indicator + diagnostics log. */
        private const val STALL_MESSAGE_THROTTLE_MS = 30_000L

        /** How long the player can sit in `STATE_BUFFERING` with
         *  `playWhenReady=true` before arm B of the watchdog flags it as a
         *  stall. Longer than [STALL_THRESHOLD_MS] because legitimate
         *  buffering on a far scrub or first play of a remote stream can
         *  legitimately take several seconds — but on a downloaded local
         *  file 8 s of buffering means the renderer chain is wedged. */
        private const val BUFFERING_STALL_THRESHOLD_MS = 8_000L

        /** How far back to seek when force-flushing on a stall. A
         *  same-position seek can be deduped by Media3 / the audio sink
         *  and produce no flush at all (which is exactly what made the
         *  previous `seekTo(currentPosition)` recovery silently fail in
         *  some cases). 100 ms guarantees a real flush + a brief replay
         *  of the audio likely missed during the stall. */
        private const val STALL_RECOVERY_REWIND_MS = 100L

        /** Sticky oscillation window (arm C). 30 seconds is long enough
         *  that a couple of BUFFERING↔READY cycles aggregate into a clear
         *  signal but short enough that the user doesn't sit through more
         *  than half a minute of borked playback before recovery fires. */
        private const val STALL_STICKY_WINDOW_MS = 30_000L

        /** Minimum samples in the ring before arm C is allowed to evaluate.
         *  At one sample per [STALL_POLL_INTERVAL_MS] this is ~20 seconds
         *  of accumulated history — enough that initial-buffer / first-
         *  seek windows don't false-positive on slow start. */
        private const val STALL_STICKY_MIN_SAMPLES = 20

        /** Below this ratio of (actual advance / expected advance @ speed),
         *  arm C considers the player stuck. 0.30 = "advanced less than
         *  30% of what playback at the configured speed should have
         *  produced over the window." Picked so steady normal playback
         *  (ratio ~1.0) and even legitimate single-buffer hiccups (~0.85)
         *  stay clear, while the user-reported 3-5s repeat patterns
         *  (ratio typically 0.10-0.20) trip clearly. */
        private const val STALL_STICKY_MIN_ADVANCE_RATIO = 0.30

        // Queue position step. Big enough that enqueueNext (minPos - STEP) stays
        // sortable for many operations before we'd need to re-densify.
        private const val STEP = 1024L

        // ---- Autoplay confirmation window ----
        // Total window from autoplay-induced play to auto-pause. User spec:
        // beeps at 1:00 / 2:00 / 3:00, pause 10s after the last beep → 3:10.
        // Visible countdown on the morphed play button starts at the first beep
        // (T=60s) and runs the remaining 130s (2:10) down to zero.
        const val AUTOPLAY_CONFIRM_FIRST_BEEP_MS = 60_000L
        const val AUTOPLAY_CONFIRM_SECOND_BEEP_MS = 120_000L
        const val AUTOPLAY_CONFIRM_THIRD_BEEP_MS = 180_000L
        const val AUTOPLAY_CONFIRM_TOTAL_MS = 190_000L
    }
}

/**
 * Snapshot of an active autoplay-confirmation window. Emitted on
 * [PlayerController.autoplayTimer] when an autoplay-induced episode starts and
 * cleared on confirm/expire/episode-change. The UI computes the remaining
 * window itself by subtracting [SystemClock.elapsedRealtime] from
 * [startedAtElapsedMs] — pulling once per frame inside Compose is cheaper than
 * having the controller emit a tick per second.
 */
data class AutoplayTimerState(
    val episodeGuid: String,
    val startedAtElapsedMs: Long,
    val totalDurationMs: Long,
)

data class PlayerState(
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    /**
     * True when the player is in STATE_BUFFERING with playWhenReady=true.
     * Lets the UI show a "Buffering…" indicator so the user knows playback
     * is trying rather than silently broken.
     */
    val isBuffering: Boolean = false,
    /**
     * True when the stall watchdog has detected the renderer is stuck
     * (cycling-position underrun). Distinct from [isBuffering] because
     * Media3 keeps the player in STATE_READY during DSP-side stalls — the
     * audio data is loaded, the audio thread just can't keep up. Without
     * this flag the UI would show "playing" while nothing is audible.
     * Cleared automatically a couple of seconds after the watchdog's
     * recovery seek so the indicator doesn't linger longer than the
     * recovery itself takes.
     */
    val isStalled: Boolean = false,
    /**
     * Most recent ExoPlayer error message, if any. Cleared on the next
     * successful state transition (BUFFERING / READY) so a transient failure
     * doesn't stick around forever. Surfaced in the player UI as a chip
     * with a Retry button.
     */
    val errorMessage: String? = null,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentArtworkUri: String? = null,
    val currentEpisodeGuid: String? = null,
    /**
     * Feed URL of the currently-loaded episode, when known. Used by the
     * UI's back-from-Player handler to route the user to the matching
     * EpisodesScreen rather than wherever the back stack happens to point
     * (which can be stale across autoplay-across-feed transitions or
     * mini-player-entry-from-catalog sessions). Sourced from the
     * MediaMetadata `extras` bundle written by [playEpisode] /
     * [restoreLastEpisodeIfNeeded] under the key "feed_url"; null when no
     * episode is loaded or when the MediaItem predates this metadata-write
     * (legacy session restoring against an older binary).
     */
    val currentFeedUrl: String? = null,
    /**
     * URI scheme of the currently-loaded MediaItem ("file" for offline,
     * "http"/"https" for streaming, null when nothing is loaded). Lets the
     * diagnostics surface "are we playing local or remote?" at a glance
     * and lets the UI distinguish handoff-pending from handoff-complete
     * without having to pull the URI out of the controller every render.
     */
    val currentMediaScheme: String? = null,
    val speed: Float = 1f
)

data class PendingReturn(
    val guid: String,
    val positionMs: Long,
    val createdAt: Long
)

private data class PendingPlay(
    val ep: Episode,
    val podcastTitle: String,
    val podcastArt: String?,
    val forcedStartMs: Long?,
    /**
     * Snapshot of [PlayerController.lastPlayWasAutoplay] at the moment this
     * play attempt was queued. Without this field, the autoplay flag would
     * leak across a controller-null bail: advanceToNextInQueue sets the flag
     * true and calls playEpisode, the null-controller branch stores the play
     * in pendingPlay and returns BEFORE consuming the flag, and a subsequent
     * manual playEpisode tap arrives, consumes the stale flag, and arms the
     * autoplay-confirmation timer for a play the user perceives as manual.
     */
    val wasAutoplay: Boolean,
)
