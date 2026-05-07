package com.lofipod.app.player

/**
 * Process-wide handoff between the activity-side [PlayerController] (which
 * owns the autoplay-confirmation timer) and the service-side
 * [PlaybackService] (which receives BT / vehicle / system-notification
 * media-button events via its `MediaSession.Callback.onPlayerCommandRequest`
 * hook). Same JVM, single-process app — a singleton with `@Volatile`
 * fields is enough; no IPC needed.
 *
 * Lifecycle:
 *   - PlayerController.connect → [bind] (pins the live controller as the
 *     confirm target).
 *   - PlayerController.release → [unbind] (drops the pin so a stale
 *     reference can't keep the activity alive past its scope).
 *   - PlaybackService.MediaSession.Callback → [handleMediaButtonPlayPause]
 *     on every external play/pause attempt.
 */
object AutoplayConfirmBridge {
    @Volatile private var instance: PlayerController? = null

    fun bind(controller: PlayerController) {
        instance = controller
    }

    fun unbind(controller: PlayerController) {
        // Reference-equality guard: if a faster activity recreate has
        // already swapped in a new controller we don't want to null out
        // the new pin from the old controller's release.
        if (instance === controller) instance = null
    }

    /**
     * Called by `PlaybackService.MediaSession.Callback.onPlayerCommandRequest`
     * for every `Player.COMMAND_PLAY_PAUSE` arriving from a remote
     * controller (BT, vehicle, system notification, etc).
     *
     * Returns `true` if the autoplay-confirmation timer was active and we
     * ran the confirm — the caller then denies the play/pause command so
     * playback continues uninterrupted. Returns `false` otherwise so the
     * caller can let the command through to default play/pause handling.
     *
     * Reads the timer state directly from the bound controller's StateFlow
     * rather than mirroring it on this bridge. That avoids a race where the
     * timer body has cleared its state but a collector-driven mirror
     * hasn't caught up yet, which would let an auto-pause command from
     * within the timer be intercepted as if it were a remote button press.
     */
    fun handleMediaButtonPlayPause(): Boolean {
        val ctrl = instance ?: return false
        if (ctrl.autoplayTimer.value == null) return false
        ctrl.confirmAutoplayContinuation()
        return true
    }
}
