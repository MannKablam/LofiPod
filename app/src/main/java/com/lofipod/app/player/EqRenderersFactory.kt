package com.lofipod.app.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
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
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .setEnableFloatOutput(false)        // we want int16 in our processor
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
