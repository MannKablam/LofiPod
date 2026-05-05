package com.lofipod.app.share

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pure-Kotlin QR encoder. Wraps ZXing core (no Android-scanning lib, no camera
 * permission) — given a payload string, produces an [ImageBitmap] suitable for
 * `Image(bitmap = ...)` in Compose.
 *
 * Used by Settings → Share to render a scannable code that points at the
 * latest signed APK. No WebView, no browser — the bitmap is just pixels.
 */
object QrCode {

    /**
     * @param text the payload to encode (URL, plain text, anything ZXing accepts).
     * @param sizePx the side length in pixels of the returned square bitmap.
     * @param foreground packed-int color for "on" modules. Defaults to opaque black.
     * @param background packed-int color for "off" modules. Defaults to opaque white.
     * @param errorCorrection how much resilience to bake in. `H` (≈30%) tolerates
     *     more glare / shadow / partial occlusion than `M` (≈15%) at the cost of a
     *     denser code. We default to `H` because the share QR is meant to be
     *     scanned by a friend's phone camera in whatever lighting they happen
     *     to be in — robustness wins over density.
     * @param margin "quiet zone" in modules. ZXing's default is 4; we use 1 since
     *     the surrounding [Surface] in Settings already provides the padding.
     */
    fun generate(
        text: String,
        sizePx: Int,
        foreground: Int = 0xFF000000.toInt(),
        background: Int = 0xFFFFFFFF.toInt(),
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
        margin: Int = 1,
    ): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to errorCorrection,
            EncodeHintType.MARGIN to margin,
        )
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                pixels[rowOffset + x] = if (matrix[x, y]) foreground else background
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }
}
