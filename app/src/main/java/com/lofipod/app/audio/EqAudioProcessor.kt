package com.lofipod.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Real-time audio processor: graphic EQ + volume boost (gain) + soft clipper.
 *
 * Audio path:
 *   PCM input  ->  per-channel biquad chain (one per band)  ->  linear gain  ->  tanh soft clipper  ->  PCM output
 *
 * Operates on 16-bit PCM (the most common output of decoders going into ExoPlayer's audio sink).
 * For other encodings, we throw UnhandledAudioFormatException and Media3 will fall back to passthrough.
 *
 * Settings can be changed live; coefficient updates take effect on the next buffer.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    // ---- Settings (volatile so UI thread changes are seen by the audio thread) ----
    @Volatile private var bands: List<EqBand> = EqPresets.FLAT
    @Volatile private var gainDb: Float = 0f          // volume boost, 0..+12 dB typical
    @Volatile private var enabled: Boolean = true
    @Volatile private var dirty: Boolean = true       // recompute coefficients on next buffer

    // ---- Internal DSP state ----
    private var sampleRate = 0
    private var channelCount = 0
    // filters[channel][band]
    private var filters: Array<Array<Biquad>> = emptyArray()

    fun setBands(newBands: List<EqBand>) { bands = newBands; dirty = true }
    fun setGainDb(db: Float) { gainDb = db.coerceIn(-12f, 12f) }
    fun setEnabled(on: Boolean) { enabled = on }

    fun currentBands(): List<EqBand> = bands
    fun currentGainDb(): Float = gainDb

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        // Allocate filter matrix
        filters = Array(channelCount) { Array(bands.size) { Biquad() } }
        dirty = true
        return inputAudioFormat   // we output the same format
    }

    override fun onFlush() {
        for (ch in filters) for (b in ch) b.reset()
    }

    override fun onReset() {
        filters = emptyArray()
    }

    private fun ensureCoefficients() {
        if (!dirty) return
        // If band count changed, reallocate
        if (filters.isNotEmpty() && filters[0].size != bands.size) {
            filters = Array(channelCount) { Array(bands.size) { Biquad() } }
        }
        for (ch in 0 until channelCount) {
            for (i in bands.indices) {
                val band = bands[i]
                filters[ch][i].setPeaking(sampleRate, band.centerHz, band.gainDb, band.qFactor)
            }
        }
        dirty = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameCount = inputBuffer.remaining() / (2 * channelCount)
        if (frameCount == 0) return

        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val src = inputBuffer.order(ByteOrder.nativeOrder())

        if (!enabled || channelCount == 0) {
            // Passthrough
            out.put(src)
            out.flip()
            return
        }

        ensureCoefficients()

        // Linear gain factor from dB
        val gainLinear = 10.0.pow((gainDb / 20.0)).toFloat()
        // Apply a touch of headroom before clipping kicks in
        val driveScale = 1f / 32768f
        val invDrive = 32767f

        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                // Read 16-bit sample, convert to float [-1, 1)
                val sampleI = src.short.toInt()
                var x = sampleI * driveScale

                // EQ chain
                val chFilters = filters[ch]
                for (i in chFilters.indices) {
                    x = chFilters[i].process(x)
                }
                // Gain
                x *= gainLinear
                // Soft clipper — tanh keeps it musical when the user pushes +6/+12 dB
                if (x > 0.95f || x < -0.95f) {
                    x = tanh(x)
                }
                // Back to int16 with hard safety clamp
                val outI = (x * invDrive).toInt().coerceIn(-32768, 32767)
                out.putShort(outI.toShort())
            }
        }
        out.flip()
    }
}
