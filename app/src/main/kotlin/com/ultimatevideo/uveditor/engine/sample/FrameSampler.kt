package com.ultimatevideo.uveditor.engine.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A colour read from a picture: straight red, green and blue in 0..1, as the picture is shown (display referred). */
data class SampledColor(val r: Double, val g: Double, val b: Double) {
    init {
        require(r in 0.0..1.0 && g in 0.0..1.0 && b in 0.0..1.0) { "colour out of range: $r $g $b" }
    }
}

/**
 * Reads a colour out of a clip's picture, for the eyedropper. It sits behind an interface so the editor's logic runs on
 * the JVM with a fake; [AndroidFrameSampler] is the real one. All calls may block: run them off the main thread.
 */
interface FrameSampler {
    /** Width / height of the upright picture of [asset] (rotation applied), or null when it cannot be read. */
    suspend fun aspect(asset: MediaAssetDto): Double?

    /**
     * The colour at ([u], [v]) of the upright picture of [asset] (0..1 from the top left) at [timeMicros] into a video
     * (ignored for photos), averaged over a small patch so one noisy pixel does not set the key; null when the
     * picture cannot be read.
     */
    suspend fun colorAt(asset: MediaAssetDto, timeMicros: Long, u: Double, v: Double): SampledColor?
}

/** A sampler that reads nothing; the default where there is no engine, and in tests that do not pick colours. */
object NoFrameSampler : FrameSampler {
    override suspend fun aspect(asset: MediaAssetDto): Double? = null

    override suspend fun colorAt(asset: MediaAssetDto, timeMicros: Long, u: Double, v: Double): SampledColor? = null
}

/** Pure patch averaging, shared with the tests: the mean of the ARGB pixels in a (2 * [radius] + 1) square. */
object PatchAverage {
    /** Mean colour of the square around ([cx], [cy]) clipped to the picture; null if the picture is empty. */
    fun of(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int): SampledColor? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(height - 1)) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(width - 1)) {
                val p = pixels[y * width + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0L) return null
        return SampledColor(r / (255.0 * n), g / (255.0 * n), b / (255.0 * n))
    }

    /** The pixel index a picture position falls on: [u], [v] in 0..1 map to the centre of the nearest pixel. */
    fun pixelOf(u: Double, v: Double, width: Int, height: Int): Pair<Int, Int> =
        (u * width).toInt().coerceIn(0, width - 1) to (v * height).toInt().coerceIn(0, height - 1)
}

/** Reads video frames with [MediaMetadataRetriever] and photos with [ImageDecoder]. */
class AndroidFrameSampler(private val context: Context) : FrameSampler {

    override suspend fun aspect(asset: MediaAssetDto): Double? = withContext(Dispatchers.IO) {
        bitmapOf(asset, 0L)?.let { bitmap ->
            try {
                bitmap.width.toDouble() / bitmap.height.toDouble()
            } finally {
                bitmap.recycle()
            }
        }
    }

    override suspend fun colorAt(asset: MediaAssetDto, timeMicros: Long, u: Double, v: Double): SampledColor? =
        withContext(Dispatchers.IO) {
            val bitmap = bitmapOf(asset, timeMicros) ?: return@withContext null
            try {
                val w = bitmap.width
                val h = bitmap.height
                val (px, py) = PatchAverage.pixelOf(u, v, w, h)
                // Only the patch is read, not the whole (possibly 4K) frame.
                val radius = PATCH_RADIUS.coerceAtMost(maxOf(0, minOf(w, h) / 2 - 1))
                val x0 = (px - radius).coerceAtLeast(0)
                val y0 = (py - radius).coerceAtLeast(0)
                val pw = minOf(w - x0, 2 * radius + 1)
                val ph = minOf(h - y0, 2 * radius + 1)
                val pixels = IntArray(pw * ph)
                bitmap.getPixels(pixels, 0, pw, x0, y0, pw, ph)
                PatchAverage.of(pixels, pw, ph, px - x0, py - y0, radius)
            } finally {
                bitmap.recycle()
            }
        }

    /** The upright picture of [asset] at [timeMicros] as an 8-bit software bitmap, or null when it cannot be read. */
    private fun bitmapOf(asset: MediaAssetDto, timeMicros: Long): Bitmap? = try {
        if (asset.isImage) decodeImage(asset.uri) else decodeVideoFrame(asset.uri, timeMicros)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: RuntimeException) {
        null // MediaMetadataRetriever reports an unreadable file this way
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun decodeImage(uri: String): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val longest = maxOf(info.size.width, info.size.height)
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        decoder.setTargetSampleSize(sample)
    }

    private fun decodeVideoFrame(uri: String, timeMicros: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            // The retriever applies the file's rotation, so the frame is the upright picture.
            return retriever.getFrameAtTime(timeMicros.coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val PATCH_RADIUS = 2
        const val MAX_SIDE = 2048
    }
}
