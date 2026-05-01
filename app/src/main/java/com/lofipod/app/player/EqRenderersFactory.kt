package com.lofipod.app.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.lofipod.app.audio.EqAudioProcessor

/**
 * Custom RenderersFactory that injects [EqAudioProcessor] into ExoPlayer's audio path,
 * while preserving the default chain that handles silence-skipping and (critically)
 * Sonic — which is what powers playback speed / pitch changes.
 *
 * Audio chain order:
 *   decoder -> EQ -> SilenceSkipping -> Sonic (speed) -> sink
 *
 * EQ runs first so its biquad coefficients are computed against the source's
 * native sample rate. Sonic and silence-skipping operate downstream and
 * preserve sample rate, so the EQ frequency response stays correct regardless
 * of the user's speed setting.
 */
class EqRenderersFactory(
    context: Context,
    private val eq: EqAudioProcessor
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
            /* audioProcessors = */ arrayOf<AudioProcessor>(eq),
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
