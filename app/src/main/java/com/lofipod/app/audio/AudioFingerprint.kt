package com.lofipod.app.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Streaming spectral-fingerprint tokenizer for the offline analyzer —
 * the raw material of the cross-episode shared-audio matcher
 * ([com.lofipod.app.data.AdSpanMatcher]). Produced sponsor reads,
 * promos, and theme music repeat VERBATIM across episodes of a feed
 * while real content never does, so "this stretch also occurs in a
 * sibling episode" identifies skippable sections with boundary
 * precision no texture heuristic can match.
 *
 * Token design (Philips-robust-hash style, validated offline against
 * real canon audio 2026-07-17 — the same pipeline located NTWAF's
 * repeated 138 s intro sponsor block and 29 s mid-roll across two
 * independently encoded episodes, while three Compelled episode pairs
 * shared only their theme):
 *   - 200 ms hann-windowed FFT every 100 ms over the mono mix;
 *   - energies summed in 5 log bands (edges 300/600/1200/2400/4800/
 *     9600 Hz);
 *   - one byte per hop carrying 4 significant bits: bit b = sign of
 *     the TIME-delta of the adjacent-band log-energy difference
 *     (E[b]-E[b+1], four adjacent pairs from five bands). Sign-of-delta
 *     survives re-encoding, loudness normalization, and codec choice —
 *     everything that differs between two stitched copies of one ad.
 *     (Verified on-device 2026-07-23: tokens computed by this class
 *     from the emulator's own decode of an episode matched the
 *     offline-pipeline tokens of a different encode of the same
 *     episode with a 705-hit shared run.)
 *
 * Feed it decoded mono samples in stream order; [tokens] returns the
 * byte-per-100ms sequence. ~36 KB per audio hour. A sample-rate change
 * mid-file ([configure]) restarts the window cleanly; the few glitched
 * tokens at the seam are noise the matcher's shingle voting ignores.
 */
class AudioFingerprint {

    private var sampleRate = 0
    private var win = 0
    private var hop = 0
    private var nfft = 0
    private var hann = FloatArray(0)
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var cosTab = FloatArray(0)
    private var sinTab = FloatArray(0)
    private var bitRev = IntArray(0)
    private var bandBinLo = IntArray(0)
    private var bandBinHi = IntArray(0)

    /** Ring of the last [win] mono samples. */
    private var ring = FloatArray(0)
    private var ringPos = 0
    private var filled = 0
    private var sinceHop = 0

    private var prevD = FloatArray(BAND_EDGES_HZ.size - 2)
    private var havePrev = false

    private val out = ByteArrayOutputStream()

    fun configure(newSampleRate: Int) {
        if (newSampleRate == sampleRate || newSampleRate <= 0) return
        sampleRate = newSampleRate
        win = sampleRate / 5           // 200 ms
        hop = sampleRate / 10          // 100 ms
        nfft = Integer.highestOneBit(win - 1) shl 1
        hann = FloatArray(win) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (win - 1))).toFloat()
        }
        re = FloatArray(nfft)
        im = FloatArray(nfft)
        val half = nfft / 2
        cosTab = FloatArray(half)
        sinTab = FloatArray(half)
        for (i in 0 until half) {
            cosTab[i] = cos(-2.0 * PI * i / nfft).toFloat()
            sinTab[i] = sin(-2.0 * PI * i / nfft).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(nfft)
        bitRev = IntArray(nfft) { i -> Integer.reverse(i) ushr (32 - bits) }
        val nBands = BAND_EDGES_HZ.size - 1
        bandBinLo = IntArray(nBands)
        bandBinHi = IntArray(nBands)
        for (b in 0 until nBands) {
            // Bin k covers frequency k * sr / nfft. Half-open [lo, hi).
            bandBinLo[b] = (BAND_EDGES_HZ[b].toLong() * nfft / sampleRate).toInt()
            bandBinHi[b] = (BAND_EDGES_HZ[b + 1].toLong() * nfft / sampleRate).toInt()
                .coerceAtMost(nfft / 2)
        }
        ring = FloatArray(win)
        ringPos = 0
        filled = 0
        sinceHop = 0
        havePrev = false
    }

    /** One decoded mono sample, full-scale ±1. */
    fun push(sample: Float) {
        if (win == 0) return
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % win
        if (filled < win) filled++
        sinceHop++
        if (filled >= win && sinceHop >= hop) {
            sinceHop = 0
            emitToken()
        }
    }

    fun tokens(): ByteArray = out.toByteArray()

    private fun emitToken() {
        // Unroll the ring into re[] oldest-first, hann it, zero-pad.
        var idx = ringPos  // oldest sample
        for (i in 0 until win) {
            re[i] = ring[idx] * hann[i]
            idx++
            if (idx == win) idx = 0
        }
        java.util.Arrays.fill(re, win, nfft, 0f)
        java.util.Arrays.fill(im, 0f)
        fft()

        val nBands = bandBinLo.size
        val d = FloatArray(nBands - 1)
        var prevBand = bandEnergyLog(0)
        for (b in 1 until nBands) {
            val e = bandEnergyLog(b)
            d[b - 1] = prevBand - e
            prevBand = e
        }
        if (havePrev) {
            var tok = 0
            for (b in d.indices) {
                if (d[b] - prevD[b] > 0f) tok = tok or (1 shl b)
            }
            out.write(tok)
        }
        d.copyInto(prevD)
        havePrev = true
    }

    private fun bandEnergyLog(b: Int): Float {
        var acc = 0.0
        for (k in bandBinLo[b] until bandBinHi[b]) {
            acc += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        return ln(acc + 1e-12).toFloat()
    }

    /** In-place iterative radix-2 complex FFT over re/im (length nfft). */
    private fun fft() {
        val n = nfft
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var base = 0
            while (base < n) {
                var k = 0
                for (j in base until base + len / 2) {
                    val wr = cosTab[k]
                    val wi = sinTab[k]
                    val j2 = j + len / 2
                    val tr = re[j2] * wr - im[j2] * wi
                    val ti = re[j2] * wi + im[j2] * wr
                    re[j2] = re[j] - tr
                    im[j2] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                base += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** Band edges, Hz: 6 edges -> 5 bands -> 4 pair-bits per token. */
        val BAND_EDGES_HZ = intArrayOf(300, 600, 1_200, 2_400, 4_800, 9_600)

        /** Hop length the token stream is timed in. */
        const val HOP_MS = 100L
    }
}
