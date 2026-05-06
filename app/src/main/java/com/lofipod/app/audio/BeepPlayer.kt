package com.lofipod.app.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * Plays the autoplay-confirmation beeps via [ToneGenerator] while ducking the
 * given [Player]'s output volume to zero for the duration of each beep —
 * GPS-style. Used by the autoplay-confirmation timer in
 * [com.lofipod.app.player.PlayerController].
 *
 * Lifecycle: instantiate per autoplay-confirmation window, run beeps via
 * [playBeeps], call [release] in a `finally` so cancellation can't leak
 * the underlying ToneGenerator or leave the player permanently muted.
 *
 * All Player volume reads/writes are wrapped in `runCatching` because the
 * MediaController behind the Player can be torn down mid-flight (release
 * during the autoplay window) and we'd rather miss a volume restore than
 * crash the timer coroutine.
 */
class BeepPlayer(private val player: Player) {

    private val toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, MAX_TONE_VOLUME)
    } catch (e: RuntimeException) {
        // A few low-end OEM ROMs ship without the proprietary-beep tone bank,
        // and ToneGenerator's ctor throws on first use. Degrade silently —
        // the autoplay-confirmation timer keeps running, just without audible
        // strikes (the visible countdown — phase 3 — still cues the user).
        Log.w(TAG, "ToneGenerator unavailable; beeps disabled for this window", e)
        null
    }

    /**
     * Play [count] short beeps in sequence, [BEEP_GAP_MS] apart. Ducks
     * `player.volume` to 0 for the duration of each tone and restores in a
     * `finally` so a cancellation always unmutes.
     *
     * No-op if the underlying ToneGenerator failed to construct; the call
     * still suspends for the equivalent total duration so callers don't have
     * to special-case that path.
     */
    suspend fun playBeeps(count: Int) {
        require(count in 1..3) { "beep count must be 1..3, got $count" }
        repeat(count) { i ->
            if (i > 0) delay(BEEP_GAP_MS)
            duckedBeep()
        }
    }

    private suspend fun duckedBeep() {
        val priorVolume = runCatching { player.volume }.getOrDefault(1f)
        runCatching { player.volume = 0f }
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS.toInt())
            delay(BEEP_DURATION_MS)
        } finally {
            runCatching { player.volume = priorVolume }
        }
    }

    fun release() {
        toneGen?.release()
    }

    companion object {
        private const val TAG = "LofiPodPlayer"
        // User spec: each beep ~0.2s, gaps "about a third of a second apart"
        // for the multi-beep strikes (2 and 3).
        const val BEEP_DURATION_MS = 200L
        const val BEEP_GAP_MS = 333L
        // ToneGenerator's per-call volume scale is 0..100; 100 ≈ system
        // music volume so the beep matches whatever the user has dialed.
        private const val MAX_TONE_VOLUME = 100
    }
}
