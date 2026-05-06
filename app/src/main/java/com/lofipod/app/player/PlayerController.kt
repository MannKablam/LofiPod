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

    fun connect(onReady: () -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
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
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _pendingReturn.value = null
        pendingPlay = null
        scope.cancel()
    }

    /** Sticky error from the last [Player.Listener.onPlayerError] call.
     *  Cleared by [pushState] when the player transitions back to a healthy
     *  state (BUFFERING or READY) — error chips disappear automatically once
     *  the user retries successfully. */
    @Volatile private var lastError: String? = null

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
            }
            pushState()
            if (playbackState == Player.STATE_ENDED) {
                // Remove the just-finished episode from the queue and auto-advance.
                val finishedGuid = controller?.currentMediaItem?.mediaId
                scope.launch { advanceToNextInQueue(finishedGuid) }
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
     * Re-evaluate the EQ enabled flag for [guid]. Effective state is the AND of
     * the master "Audio enhancement" toggle (Settings.audioEnhancementEnabled)
     * and the inverse of the per-episode override (episode_state.eqDisabled).
     *
     * Called on item transitions, after the user toggles the per-episode
     * override on PlayerScreen, and after the user flips the master toggle on
     * the EQ screen — three writers, one source of truth (this method) so the
     * processor's enabled flag doesn't get clobbered by whichever path ran
     * last. Earlier bug: master toggle and per-episode override both wrote
     * directly to `sharedEq.enabled`, so a track transition would silently
     * undo a user's master-off toggle.
     */
    fun applyEqOverrideFor(guid: String) {
        scope.launch {
            val episodeDisabled = withContext(Dispatchers.IO) {
                dao.get(guid)?.eqDisabled ?: false
            }
            val globalEnabled = com.lofipod.app.data.Settings(context)
                .audioEnhancementEnabled.first()
            // setEnabled is volatile-safe; no need to bounce back to main.
            PlaybackService.sharedEq.setEnabled(globalEnabled && !episodeDisabled)
        }
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

            // Auto-download episodes the user starts playing. Reasoning: by
            // the time playback is rolling, the user is committed enough that
            // having an offline copy for the next session is the better
            // default. Idempotent — DownloadManager skips if the guid is
            // already queued/downloading/completed; this check is just a
            // cheap precheck against the in-memory snapshot to avoid the
            // service-call round trip. Skips guids already in downloads
            // including FAILED, so a previously-failed download isn't auto-
            // retried on every play (the user can retry from the chip).
            val app = context.applicationContext as LofiPodApp
            if (!app.downloadsApi.byId.value.containsKey(ep.guid)) {
                app.downloadsApi.start(ep)
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
    fun pause() { controller?.pause() }

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
            playEpisode(ep, cached?.title ?: "", cached?.artworkUrl ?: queueNext.artworkUrl)
            return
        }

        // Queue empty — fall back to the next episode in the same feed if the
        // user has the setting on.
        val enabled = com.lofipod.app.data.Settings(app).autoPlayNextInFeed.first()
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
        val candidate = pod.episodes
            .filter { it.guid != excluding && it.guid !in playedGuids }
            .maxByOrNull { it.pubDateMillis ?: Long.MIN_VALUE }
            ?: return

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
        const val REASON_JUMP_FROM = "jump_from"
        const val REASON_SESSION_END = "session_end"
        const val REASON_PROMOTED_TO_MOST_EXCELLENT = "promoted_to_most_excellent"
        // Queue position step. Big enough that enqueueNext (minPos - STEP) stays
        // sortable for many operations before we'd need to re-densify.
        private const val STEP = 1024L
    }
}

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
