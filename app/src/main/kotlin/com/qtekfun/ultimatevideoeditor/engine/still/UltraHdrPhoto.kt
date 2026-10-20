package com.qtekfun.ultimatevideoeditor.engine.still

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Makes the HDR picture of an Ultra HDR photo (a JPEG with a gain map, Android 14 and later) in the HLG signal of an HDR project,
 * 8 bits per channel, with SDR white at 203 nit like every other SDR source. The maths is [GainmapMath]. A photo without a
 * gain map, or a failure, gives null and the photo is shown as it was before (its SDR rendition at reference white).
 */
internal object UltraHdrPhoto {
    private const val TAG = "UVStill"

    fun toHlg(bitmap: Bitmap): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !bitmap.hasGainmap()) return null
        return try {
            convert(bitmap)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "HDR photo not made, out of memory: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "HDR photo not made: ${e.message}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun convert(bitmap: Bitmap): Bitmap? {
        val gainmap = bitmap.gainmap ?: return null
        val params = GainmapParams(
            gainmap.ratioMin, gainmap.ratioMax, gainmap.gamma, gainmap.epsilonSdr, gainmap.epsilonHdr, gainmap.displayRatioForFullHdr,
        )
        val planes = planesOf(gainmap.gainmapContents) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (source !== bitmap) source.recycle()
        val signal = FloatArray(3)
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val u = (x + 0.5f) / w
                val r = GainmapMath.hdrLinear(GainmapMath.SRGB_TO_LINEAR[Color.red(p)], planes.at(u, v, 0), params, 0)
                val g = GainmapMath.hdrLinear(GainmapMath.SRGB_TO_LINEAR[Color.green(p)], planes.at(u, v, 1), params, 1)
                val b = GainmapMath.hdrLinear(GainmapMath.SRGB_TO_LINEAR[Color.blue(p)], planes.at(u, v, 2), params, 2)
                GainmapMath.hlgSignal(r, g, b, signal)
                pixels[y * w + x] = Color.argb(
                    Color.alpha(p),
                    GainmapMath.quantise(signal[0], x, y),
                    GainmapMath.quantise(signal[1], x, y),
                    GainmapMath.quantise(signal[2], x, y),
                )
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The gain map's values as planes: one channel (ALPHA_8 or a grey picture) repeats, a colour map keeps its three. */
    private fun planesOf(contents: Bitmap): GainmapPlanes? {
        val readable = if (contents.config == Bitmap.Config.HARDWARE) contents.copy(Bitmap.Config.ARGB_8888, false) ?: return null else contents
        val w = readable.width
        val h = readable.height
        val pixels = IntArray(w * h)
        readable.getPixels(pixels, 0, w, 0, 0, w, h)
        val single = readable.config == Bitmap.Config.ALPHA_8
        val result = if (single) {
            GainmapPlanes.gray(w, h, FloatArray(w * h) { Color.alpha(pixels[it]) / 255f })
        } else {
            GainmapPlanes(
                w, h,
                arrayOf(
                    FloatArray(w * h) { Color.red(pixels[it]) / 255f },
                    FloatArray(w * h) { Color.green(pixels[it]) / 255f },
                    FloatArray(w * h) { Color.blue(pixels[it]) / 255f },
                ),
            )
        }
        if (readable !== contents) readable.recycle()
        return result
    }
}
