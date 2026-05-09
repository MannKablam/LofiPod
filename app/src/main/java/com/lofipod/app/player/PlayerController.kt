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
import kotlinx.coroutines.flow.MutableStateFlow
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
            val art = ep.episodeArtworkUrl
            val item = MediaItem.Builder()
                .setMediaId(ep.guid)
                .setUri(ep.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist("")
                        .setArtworkUri(art?.let { android.net.Uri.parse(it) })
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
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()
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
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = pushState()
        // The next two fire as the MediaController syncs its state from a
        // running session — without them, reconnecting to a session that's
        // already mid-playback (cold launch case) leaves PlayerState empty
        // because no transition / state-change happens to wake the listener.
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = pushState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = pushState()
    }

    /**
     * Re-evaluate the EQ enabled flag for the episode at [guid]. Effective
     * state is the AND of the master "Audio enhancement" toggle
     * (Settings.audioEnhancementEnabled) and the inverse of the per-PODCAST
     * override (podcast_state.eqDisabled, looked up by the episode's feedUrl).
     *
     * Per-podcast (not per-episode) since v0.6.11 — see PodcastStateEntity
     * docstring. Settles the same way at three writer sites (track
     * transitions, EQ screen toggle, master toggle): single source of truth
     * here so the processor's enabled flag doesn't get clobbered by the
     * last-wins of three competing writers.
     */
    fun applyEqOverrideFor(guid: String) {
        scope.launch {
            val state = withContext(Dispatchers.IO) { dao.get(guid) }
            val feedUrl = state?.feedUrl
            val podcastState = if (feedUrl != null) {
                withContext(Dispatchers.IO) { podcastStateDao.get(feedUrl) }
            } else null
            val podcastDisabled = podcastState?.eqDisabled ?: false
            val podcastOverrideCsv = podcastState?.eqBandsCsvOverride
            val settings = com.lofipod.app.data.Settings(context)
            val globalEnabled = settings.audioEnhancementEnabled.first()
            // setEnabled is volatile-safe; no need to bounce back to main.
            PlaybackService.sharedEq.setEnabled(globalEnabled && !podcastDisabled)

            // Per-podcast EQ override: when the user has dialed in custom band
            // gains for the current podcast, apply them in place of the global
            // preset. When no override exists (CSV is null), reapply the
            // global bands — matters on cross-podcast transitions where the
            // previous podcast had an override and we need to swap back to
            // the global tuning. settings.eqBandsCsv.first() can be null
            // (Settings stores it as nullable) which is fine — we skip
            // applying bands and leave the processor on whatever it had.
            val targetCsv: String? = podcastOverrideCsv ?: settings.eqBandsCsv.first()
            if (targetCsv != null) {
                val parsed = parseEqBandsCsv(targetCsv)
                if (parsed != null) {
                    PlaybackService.sharedEq.setBands(parsed)
                }
            }
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
        _state.value = PlayerState(
            isPlaying = c.isPlaying,
            isReady = c.playbackState == Player.STATE_READY,
            // Buffering counts only when we WANT to play (playWhenReady). A
            // paused player may also pass through BUFFERING but we shouldn't
            // surface a "Buffering…" indicator to the user in that case.
            isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            errorMessage = lastError,
            currentTitle = c.mediaMetadata.title?.toString(),
            currentArtist = c.mediaMetadata.artist?.toString(),
            currentArtworkUri = c.mediaMetadata.artworkUri?.toString(),
            currentEpisodeGuid = c.currentMediaItem?.mediaId,
            speed = c.playbackParameters.speed
        )
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
        val c = controller
        if (c == null) {
            // Controller still building — queue and bail. The drain in
            // [connect]'s listener replays this call once we're live.
            pendingPlay = PendingPlay(ep, podcastTitle, podcastArt, forcedStartMs)
            return
        }
        // Consume the autoplay-detection flag at entry. Whether or not we
        // re-arm the timer below, any previous timer is for a different
        // episode and must be torn down: the body uses guid identity to
        // decide whether to pause, but the StateFlow still drives a stale
        // countdown UI until cleared explicitly.
        val wasAutoplay = lastPlayWasAutoplay
        lastPlayWasAutoplay = false
        autoplayTimerJob?.cancel()
        autoplayTimerJob = null
        _autoplayTimer.value = null

        // Housekeeping: prune any auto-downloads from PRIOR episodes whose
        // playback finished and whose 1-hour TTL has elapsed. Runs on every
        // track change so old auto-downloads don't accumulate even in long
        // sessions where connect() doesn't re-fire.
        sweepExpiredAutoDownloads()

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
                c.setPlaybackSpeed(speedOverride)
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
            val existingDl = app.downloadsApi.byId.value[ep.guid]
            val needsStart = existingDl == null ||
                existingDl.state == com.lofipod.app.data.LofiDownload.State.FAILED
            if (needsStart) {
                withContext(Dispatchers.IO) {
                    autoDownloadDao.upsert(
                        com.lofipod.app.data.db.AutoDownloadEntity(
                            guid = ep.guid,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                app.downloadsApi.start(ep)
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
     */
    fun togglePlay() {
        val c = controller ?: return
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
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekTo(0)
                c.play()
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
        controller?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 3.0f))
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
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
)
