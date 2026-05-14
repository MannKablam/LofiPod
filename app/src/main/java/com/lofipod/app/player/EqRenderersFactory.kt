package com.lofipod.app.player

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lofipod.app.audio.EqAudioProcessor
import com.lofipod.app.audio.SilenceSkippingProcessor

/**
 * Custom RenderersFactory that injects [EqAudioProcessor] +
 * [SilenceSkippingProcessor] into ExoPlayer's audio path, while preserving
 * the default chain that handles Sonic — which is what powers playback
 * speed / pitch changes.
 *
 * Audio chain order:
 *   decoder -> EQ -> SilenceSkipping(custom) -> Sonic (speed) -> sink
 *
 * EQ runs first so its biquad coefficients are computed against the source's
 * native sample rate. Our silence-skipping runs against the EQ-treated signal
 * so a heavy bass cut doesn't accidentally re-classify low rumble as
 * "silence." Sonic operates downstream and preserves sample rate, so neither
 * EQ nor silence-detection is thrown off by the user's speed setting.
 *
 * Media3's built-in [SilenceSkippingAudioProcessor] still has to be passed to
 * the slot the chain reserves for it, but we never enable it (our custom
 * processor is doing the actual work above it in the chain). Passing a fresh
 * disabled instance is the cheapest way to satisfy the chain's typed slot
 * without rewriting the chain plumbing.
 */
class EqRenderersFactory(
    context: Context,
    private val eq: EqAudioProcessor,
    private val skipSilence: SilenceSkippingProcessor,
) : DefaultRenderersFactory(context) {

    init {
        // MediaCodecSelector preferring SOFTWARE MP3 decoders over hardware
        // ones. Field logs (LofiPod log 7d4b926806be.txt:683-684, 689-691,
        // 708, 712-713, ...) show repeated MediaCodec accounting errors
        // from `c2.android.mp3.decoder`:
        //   D CCodecBuffers: Client returned a buffer it does not own
        //   D MediaCodec: keep callback message for reclaim
        //   I CCodecConfig: query failed after returning 8 values (BAD_INDEX)
        // These are known Codec 2 software-decoder bugs on Android 13+
        // when ExoPlayer rapidly transitions states (seek + speed change +
        // resume). They co-occur with AudioTrack underruns at the HAL layer.
        //
        // Counterintuitively the issue is with `c2.android.mp3.decoder`
        // (Google's Codec 2 SOFTWARE decoder) — not a hardware OEM
        // decoder. The selector below prefers any NON-c2.android.* MP3
        // decoder available on the device, falling back to the Codec 2
        // version only if nothing else is registered. On Pixel devices
        // this typically picks `OMX.google.mp3.decoder` (the older OMX
        // software decoder) which has been stable across the Codec 2
        // transition window.
        //
        // For non-MP3 codecs we let the default selector pick — they
        // haven't shown the same accounting issues in field logs.
        setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
            )
            if (mimeType != MimeTypes.AUDIO_MPEG) return@setMediaCodecSelector defaults
            // Partition: non-Codec2 software decoders first, then everything
            // else (preserving relative order within each partition). The
            // MediaCodecRenderer will try them in this order, so the first
            // viable non-Codec2 entry wins.
            val (preferred, fallback) = defaults.partition { info: MediaCodecInfo ->
                info.softwareOnly && !info.name.startsWith("c2.android.")
            }
            preferred + fallback
        }
    }

    // Audio offload is implicitly disabled: we override buildAudioSink to return
    // our own DefaultAudioSink, and DefaultAudioSink.Builder doesn't enable offload
    // unless explicitly configured. Offload would bypass software audio processors.

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val chain = DefaultAudioSink.DefaultAudioProcessorChain(
            /* audioProcessors = */ arrayOf<AudioProcessor>(eq, skipSilence),
            /* silenceSkippingAudioProcessor = */ SilenceSkippingAudioProcessor(),
            /* sonicAudioProcessor = */ SonicAudioProcessor()
        )
        // AudioTrack output buffer sized for variable-speed playback. Media3's
        // default DefaultAudioTrackBufferSizeProvider gives a 250 ms minimum
        // PCM buffer with a 4× multiplier of the OS minimum. At 2× playback
        // speed (the user's common setting) that buffer drains in 125 ms
        // wall-clock — any momentary DSP-thread slowdown longer than 125 ms
        // (thermal throttle, JIT, GC pause, kernel scheduling jitter) starves
        // the AudioTrack and produces the cycling-position underrun loop.
        //
        // Bumped to 1.5 s minimum / 3.0 s max with an 8× multiplier so a
        // short DSP hiccup at 2× has up to ~750 ms wall-clock to recover
        // before the AudioTrack runs dry. ~576 KB at 48 kHz stereo int16 —
        // negligible on any modern device, large win against the hang.
        val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(1_500_000)
            .setMaxPcmBufferDurationUs(3_000_000)
            .setPcmBufferMultiplicationFactor(8)
            .build()
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .setEnableFloatOutput(false)        // we want int16 in our processor
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(bufferSizeProvider)
            .build()
    }
}
