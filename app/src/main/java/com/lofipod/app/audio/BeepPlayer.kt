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
 * (piezo-buzzer character) at high frequency, while ducking the given
 * [Player]'s output volume to zero for the duration of each beep — GPS-style.
 *
 * Pre-v0.4.x used [android.media.ToneGenerator]'s `TONE_PROP_BEEP`, but that
 * tone is a low/mid dual-frequency chirp that gets buried under typical
 * podcast-voice content. Switched to a self-rendered AudioTrack so we can
 * pick a high single-frequency square wave that punches through speech the
 * way an actual piezo alarm does. Duration is now sustained for the full
 * [BEEP_DURATION_MS] (was ~35 ms of ToneGenerator's built-in envelope), and
 * the gap between strikes matches that duration so the cadence reads as
 * deliberate rather than rushed.
 *
 * Lifecycle: instantiate per autoplay-confirmation window, run beeps via
 * [playBeeps], call [release] in a `finally` so cancellation can't leak the
 * underlying AudioTracks or leave the player permanently muted.
 *
 * All Player volume reads/writes are wrapped in `runCatching` because the
 * MediaController behind the Player can be torn down mid-flight (release
 * during the autoplay window) and we'd rather miss a volume restore than
 * crash the timer coroutine.
 */
class BeepPlayer(private val player: Player) {

    /** Pre-rendered PCM buffer for one beep. Mono int16 at [SAMPLE_RATE]. */
    private val toneBuffer: ShortArray = synthesizePiezoTone(
        freqHz = TONE_FREQ_HZ,
        durationMs = BEEP_DURATION_MS,
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

    private suspend fun duckedBeep() {
        val priorVolume = runCatching { player.volume }.getOrDefault(1f)
        runCatching { player.volume = 0f }
        var track: AudioTrack? = null
        try {
            track = buildTrack()
            // STATIC mode: write once, then play. Smaller-than-buffer writes
            // would short-play; we sized the track to exactly the buffer.
            track.write(toneBuffer, 0, toneBuffer.size)
            track.play()
            // Block for the tone duration so the next beep / unduck happens
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
            runCatching { player.volume = priorVolume }
        }
    }

    private fun buildTrack(): AudioTrack {
        // A fresh AudioTrack per beep keeps state simple — no need to track
        // playback-head position or reset between strikes. The allocation
        // cost is small (~22 KB buffer at 22 kHz / 500 ms) and beeps fire at
        // most once per minute.
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
            toneBuffer.size * 2,  // bytes (int16)
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

        // 22.05 kHz mono is plenty of headroom for a 2.7 kHz fundamental
        // (Nyquist is 11 kHz; the square wave's harmonics up to ~9 kHz pass
        // cleanly). Halving the rate vs 44.1 kHz also halves buffer size.
        private const val SAMPLE_RATE = 22050

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
            val sustainPeak = 0.85  // leave a bit of headroom
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
