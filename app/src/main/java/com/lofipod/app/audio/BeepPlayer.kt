package com.lofipod.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays the autoplay-confirmation beeps using a synthesized square-wave tone
 * (piezo-buzzer character) at high frequency. Pauses the given [Player] for
 * the duration of each beep so the beep is unmixed with podcast audio —
 * GPS-style "stop the music, play the chime, resume the music."
 *
 * **Ducking design (v0.5.9 redesign).** Earlier versions tried to duck via
 * `player.volume = 0f`; user reports showed it didn't actually silence on
 * device. The path through `MediaController` → session → `ExoPlayer.volume`
 * is supposed to be synchronous, but in practice some combination of
 * decoder buffer + audio sink + MediaController IPC delay was leaving the
 * podcast audible during the beep. Switching to `pause()` + `play()` is
 * bulletproof: when paused, ExoPlayer stops feeding the audio sink and
 * the audio output silences within one buffer (tens of ms). On resume, the
 * audio sink picks up where it left off — no buffer flush, no rebuffer.
 *
 * The beep AudioTrack also gets an explicit `setVolume(BEEP_TRACK_VOLUME)`
 * call so the dev has a runtime tuning knob independent of the
 * pre-rendered amplitude (`sustainPeak` in the synthesizer). Combine for
 * effective amplitude = `sustainPeak * BEEP_TRACK_VOLUME` at full system
 * volume.
 *
 * Pre-v0.4.x used [android.media.ToneGenerator]'s `TONE_PROP_BEEP`, but
 * that tone is a low/mid dual-frequency chirp that gets buried under
 * typical podcast-voice content. Switched to a self-rendered AudioTrack
 * so we can pick a high single-frequency square wave that punches
 * through speech.
 *
 * Lifecycle: instantiate per autoplay-confirmation window, run beeps via
 * [playBeeps], call [release] in a `finally` so cancellation can't leak
 * the underlying AudioTracks or leave the player permanently paused.
 *
 * All Player operations are wrapped in `runCatching` because the
 * MediaController behind the Player can be torn down mid-flight (release
 * during the autoplay window) and we'd rather miss a state restore than
 * crash the timer coroutine.
 */
class BeepPlayer(private val player: Player) {

    /** Pre-rendered PCM buffer for one beep. Mono int16 at [SAMPLE_RATE]. */
    private val toneBuffer: ShortArray = synthesizePiezoTone(
        freqHz = TONE_FREQ_HZ,
        durationMs = BEEP_DURATION_MS,
        sampleRate = SAMPLE_RATE,
    )

    /** Shorter tone for the playback-handoff cue (HTTP -> local file swap).
     *  80 ms is just long enough to register as an intentional ping rather
     *  than a click — short enough that a 2-beep cue lands in ~200 ms total
     *  and partially fills the audio gap from setMediaItem + prepare. */
    private val quickToneBuffer: ShortArray = synthesizePiezoTone(
        freqHz = TONE_FREQ_HZ,
        durationMs = QUICK_BEEP_DURATION_MS,
        sampleRate = SAMPLE_RATE,
    )

    /**
     * Play [count] short beeps in sequence, [BEEP_GAP_MS] apart. Ducks
     * `player.volume` to 0 for the duration of each tone and restores in a
     * `finally` so a cancellation always unmutes.
     */
    suspend fun playBeeps(count: Int) {
        require(count in 1..3) { "beep count must be 1..3, got $count" }
        repeat(count) { i ->
            if (i > 0) delay(BEEP_GAP_MS)
            duckedBeep()
        }
    }

    /**
     * Two short tones, no ducking. Used as the handoff cue when the
     * player swaps from HTTP streaming to a freshly-completed local
     * file mid-playback — the swap itself causes a brief silent gap
     * (setMediaItem + prepare), and these beeps land inside that gap
     * to make the transition feel intentional rather than glitched.
     *
     * No `pause()/play()` ducking because the swap already silences
     * the source for ~50–100 ms; we don't want to extend that with a
     * full BEEP_DURATION_MS pause-and-resume cycle.
     */
    suspend fun playHandoffCue() {
        repeat(2) { i ->
            if (i > 0) delay(QUICK_BEEP_GAP_MS)
            unduckedBeep(quickToneBuffer)
        }
    }

    private suspend fun unduckedBeep(buf: ShortArray) {
        var track: AudioTrack? = null
        try {
            track = buildTrack(buf.size)
            runCatching { track.setVolume(BEEP_TRACK_VOLUME) }
            track.write(buf, 0, buf.size)
            track.play()
            delay(buf.size * 1000L / SAMPLE_RATE)
        } catch (e: Exception) {
            // OEM audio quirks (rare). Degrade quietly.
            Log.w(TAG, "Unducked beep failed; skipping handoff cue", e)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private suspend fun duckedBeep() {
        // Pause the podcast so the beep plays unmixed. Capture wasPlaying
        // BEFORE pausing so we can correctly restore on the way out — we
        // don't want to auto-resume if the user had already paused before
        // the beep window started.
        //
        // History: prior to v0.10.13 this pause was silently denied by
        // PlaybackService.AutoplayConfirmCallback — the autoplay-
        // confirmation intercept treated our own MediaController's pause
        // as a remote-controller play/pause press, fired
        // confirmAutoplayContinuation, and cancelled the timer (and
        // therefore the in-flight beep coroutine). v0.10.13 added a
        // controller.uid == Process.myUid() check in onPlayerCommandRequest
        // so our own process's pauses always pass through. See
        // PlaybackService.AutoplayConfirmCallback for the full history.
        val wasPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        if (wasPlaying) {
            runCatching { player.pause() }
        }
        var track: AudioTrack? = null
        try {
            track = buildTrack()
            // Per-track volume cap independent of the pre-rendered tone
            // amplitude. Effective level = sustainPeak * BEEP_TRACK_VOLUME
            // at full system volume; tune either or both.
            runCatching { track.setVolume(BEEP_TRACK_VOLUME) }
            // STATIC mode: write once, then play. Smaller-than-buffer writes
            // would short-play; we sized the track to exactly the buffer.
            track.write(toneBuffer, 0, toneBuffer.size)
            track.play()
            // Block for the tone duration so the next beep / unpause happens
            // after the audio actually finishes. AudioTrack.MODE_STATIC keeps
            // playing until the buffer ends, but we still need to wait that
            // wallclock time before continuing.
            delay(BEEP_DURATION_MS)
        } catch (e: Exception) {
            // OEM audio quirks (rare). Degrade quietly — countdown UI still
            // tells the user the timer is running.
            Log.w(TAG, "Piezo beep failed; skipping audio strike", e)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            // Resume playback if we paused it. If the user paused mid-beep,
            // wasPlaying was already false and we leave them paused.
            if (wasPlaying) {
                runCatching { player.play() }
            }
        }
    }

    private fun buildTrack(samples: Int = toneBuffer.size): AudioTrack {
        // A fresh AudioTrack per beep keeps state simple — no need to track
        // playback-head position or reset between strikes. The allocation
        // cost is small and beeps fire at most a few times per session.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack(
            attrs,
            fmt,
            samples * 2,  // bytes (int16)
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    fun release() {
        // No persistent state to free here — each beep allocates its own
        // AudioTrack and releases it in the finally block. The empty body is
        // kept so callers' existing `try/finally { release() }` patterns
        // continue to compile + run unchanged.
    }

    companion object {
        private const val TAG = "LofiPodPlayer"

        // High frequency for a piezo-buzzer character. 2700 Hz sits in the
        // "alarm clock" sweet spot — well above typical voice fundamental
        // (~120 Hz male / ~200 Hz female) and the bulk of consonant
        // sibilance (~4–7 kHz), so it cuts through podcast playback the
        // way a kitchen-timer alarm does.
        private const val TONE_FREQ_HZ = 2700

        // Sustained duration. Half a second of tone per strike; gap between
        // strikes matches it. Longer than the 35 ms ToneGenerator envelope
        // we used to use, so each strike reads as a deliberate marker rather
        // than a click.
        const val BEEP_DURATION_MS = 500L
        const val BEEP_GAP_MS = 500L

        /** Handoff-cue tone duration. Short enough to feel like a quick
         *  ping ("✓✓") rather than an alarm. Total cue with one gap
         *  lands at 80 + 40 + 80 = 200 ms. */
        const val QUICK_BEEP_DURATION_MS = 80L
        const val QUICK_BEEP_GAP_MS = 40L

        // 22.05 kHz mono is plenty of headroom for a 2.7 kHz fundamental
        // (Nyquist is 11 kHz; the square wave's harmonics up to ~9 kHz pass
        // cleanly). Halving the rate vs 44.1 kHz also halves buffer size.
        private const val SAMPLE_RATE = 22050

        /**
         * Per-AudioTrack volume cap, multiplied with the pre-rendered tone
         * amplitude (`sustainPeak`) to give the effective beep level at full
         * system volume. 0.5 is a starting point — the user can iterate
         * via this knob without rebuilding the synthesized buffer.
         *
         * The earlier "duck via player.volume = 0" approach was unreliable
         * on device, so the beep often mixed with the podcast at full
         * podcast level — making any beep feel "too loud." The pause/play
         * design in [duckedBeep] means the beep plays alone now, so this
         * volume controls the actual audible level rather than fighting
         * mixed audio.
         */
        private const val BEEP_TRACK_VOLUME = 0.5f

        /**
         * Render a square-wave-with-soft-edges PCM buffer for one beep. Soft
         * 5 ms attack/release ramps avoid the click that a hard-onset square
         * would generate at start/stop; the steady-state remains a square so
         * the harmonic content stays "piezo-buzzer" rather than "pure sine".
         */
        private fun synthesizePiezoTone(
            freqHz: Int,
            durationMs: Long,
            sampleRate: Int,
        ): ShortArray {
            val numSamples = (sampleRate * durationMs / 1000).toInt()
            val buf = ShortArray(numSamples)
            val period = sampleRate.toDouble() / freqHz
            val rampSamples = (sampleRate * 0.005).toInt().coerceAtLeast(1)  // 5 ms
            // 0.3 amplitude × BEEP_TRACK_VOLUME = effective ~0.15 (~-16 dBFS)
            // at full system volume. Earlier values (0.85 → 0.5 → 0.2) were
            // calibrated against a broken ducking implementation that left
            // the beep mixed with podcast audio. With the v0.5.9 pause/play
            // ducking redesign the beep plays alone, so the perceived level
            // is just sustainPeak × BEEP_TRACK_VOLUME × system volume —
            // tune either constant to taste.
            val sustainPeak = 0.3
            for (i in 0 until numSamples) {
                // Square wave value at this position (±1.0).
                val phase = (i % period.toInt()).toDouble() / period
                val square = if (phase < 0.5) 1.0 else -1.0
                // Linear attack ramp at the start, linear release at the end.
                // Smoothes the start/stop transients so the beep doesn't
                // click in/out.
                val env = when {
                    i < rampSamples -> i.toDouble() / rampSamples
                    i > numSamples - rampSamples -> (numSamples - i).toDouble() / rampSamples
                    else -> 1.0
                }
                // Gentle sine-blend on the leading edge of each square half-
                // cycle (the first ~10% of each period gets sine-weighted).
                // Keeps the buzz character but takes the harshest edge off
                // so it doesn't sound like a literal piezo full of HF dirt.
                val edgeBlend = 0.1
                val blend = if (phase < edgeBlend) {
                    val t = phase / edgeBlend
                    square * (1.0 - t) + sin(t * PI / 2.0) * (if (square > 0) 1 else -1) * t
                } else if (phase >= 0.5 && phase < 0.5 + edgeBlend) {
                    val t = (phase - 0.5) / edgeBlend
                    square * (1.0 - t) + sin(t * PI / 2.0) * (-1.0) * t
                } else {
                    square
                }
                val sample = (blend * env * sustainPeak * Short.MAX_VALUE).toInt()
                buf[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return buf
        }
    }
}
