package com.lofipod.app.player

/**
 * Process-wide handoff between the app-side [PlayerController] (which
 * owns the autoplay-confirmation timer) and the service-side
 * [PlaybackService] (which receives BT / vehicle / system-notification
 * media-button events via its `MediaSession.Callback.onPlayerCommandRequest`
 * hook). Same JVM, single-process app — a singleton with `@Volatile`
 * fields is enough; no IPC needed.
 *
 * Lifecycle:
 *   - PlayerController.connect → [bind] (pins the live controller as the
 *     confirm target; the process-scoped controller re-pins itself on
 *     every activity recreation, a harmless same-instance overwrite).
 *   - PlayerController.release → [unbind] (drops the pin so a released
 *     controller can't be driven by media buttons; unused on the
 *     process-scoped happy path, where the pin lives as long as the JVM).
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
     * Returns `true` if the autoplay countdown was PERCEPTIBLY running
     * (post-first-beep, playback live — same
     * [PlayerController.shouldConsumePlayPauseAsAutoplayConfirm] gate as
     * the in-app button) and we ran the confirm — the caller then denies
     * the play/pause command so playback continues uninterrupted. Returns
     * `false` otherwise so the caller lets the command through to default
     * play/pause handling. The gate matters: before the first beep a
     * remote listener has heard nothing — their pause press is a pause
     * press, and consuming it here both ate the command AND cancelled the
     * auto-pause that would have ended the run.
     *
     * Reads the gate directly off the bound controller's state rather
     * than mirroring it on this bridge. That avoids a race where the
     * timer body has cleared its state but a collector-driven mirror
     * hasn't caught up yet, which would let an auto-pause command from
     * within the timer be intercepted as if it were a remote button press.
     */
    fun handleMediaButtonPlayPause(): Boolean {
        val ctrl = instance ?: return false
        if (!ctrl.shouldConsumePlayPauseAsAutoplayConfirm()) return false
        ctrl.confirmAutoplayContinuation()
        return true
    }
}
