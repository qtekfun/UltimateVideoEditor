package com.ultimatevideo.uveditor.engine.still

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.title.TitleBitmap
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a still clip shows: for a [StillKind.PHOTO] [id] is the image's `content://` URI, for a
 * [StillKind.STICKER] it is a built-in sticker id (see [StickerIds]).
 */
data class StillRef(val kind: StillKind, val id: String)

/** A photo or sticker could not be turned into pixels. The message is fit to show to the user. */
class StillRasterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a still into the premultiplied RGBA picture the compositor draws like a title: in project
 * canvas pixels, 1:1, centred, then transformed by the clip. Photos are fitted into the canvas
 * (contain, EXIF orientation applied); stickers get a size relative to the canvas. The preview and
 * the exporter both go through this, which keeps them identical.
 */
fun interface StillRasterizer {
    /** Blocking: decodes a file for photos. Call off the main thread. @throws StillRasterException */
    fun rasterize(ref: StillRef, canvasWidth: Int, canvasHeight: Int): TitleBitmap
}

/** Pure sizing rules, shared with the tests. */
object StillFit {
    /** Longest side of any still picture, in pixels; larger canvases are never exceeded anyway. */
    const val MAX_SIDE = 8192

    /** A sticker's square side as a fraction of the canvas' shorter side. */
    const val STICKER_FRACTION = 0.35

    /** Size of a [srcWidth] x [srcHeight] picture fitted inside the canvas without cropping. */
    fun contain(srcWidth: Int, srcHeight: Int, canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> {
        require(srcWidth > 0 && srcHeight > 0 && canvasWidth > 0 && canvasHeight > 0) {
            "sizes must be positive: ${srcWidth}x$srcHeight into ${canvasWidth}x$canvasHeight"
        }
        val scale = min(canvasWidth.toDouble() / srcWidth, canvasHeight.toDouble() / srcHeight)
        val w = (srcWidth * scale).roundToInt().coerceIn(1, min(canvasWidth, MAX_SIDE))
        val h = (srcHeight * scale).roundToInt().coerceIn(1, min(canvasHeight, MAX_SIDE))
        return w to h
    }

    /** Side of a sticker's square picture on a canvas. */
    fun stickerSide(canvasWidth: Int, canvasHeight: Int): Int =
        (min(canvasWidth, canvasHeight) * STICKER_FRACTION).roundToInt().coerceAtLeast(MIN_STICKER_SIDE)

    /**
     * Largest power-of-two decode reduction that still leaves the picture's long side at least as
     * long as the canvas'. Keeps a 50 MP photo from being decoded at full size for a 1080p canvas.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, canvasWidth: Int, canvasHeight: Int): Int {
        val srcLong = max(srcWidth, srcHeight)
        val target = max(canvasWidth, canvasHeight)
        var sample = 1
        while (srcLong / (sample * 2) >= target) sample *= 2
        return sample
    }

    private const val MIN_STICKER_SIDE = 64
}

/**
 * Decodes photos with the platform's [ImageDecoder] and draws stickers with [StickerArt].
 * Deterministic for the same input and canvas.
 */
class AndroidStillRasterizer(private val context: Context) : StillRasterizer {

    override fun rasterize(ref: StillRef, canvasWidth: Int, canvasHeight: Int): TitleBitmap {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        val bitmap = when (ref.kind) {
            StillKind.PHOTO -> decodePhoto(ref.id, canvasWidth, canvasHeight)
            StillKind.STICKER -> drawSticker(ref.id, canvasWidth, canvasHeight)
        }
        var argb: Bitmap? = null
        try {
            // Wide-colour or HDR photos can decode to a float config; the compositor wants 8-bit RGBA.
            argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = ByteBuffer.allocateDirect(argb.width * argb.height * BYTES_PER_PIXEL)
            argb.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return TitleBitmap(argb.width, argb.height, pixels)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to show a picture", e)
        } finally {
            if (argb != null && argb !== bitmap) argb.recycle()
            bitmap.recycle()
        }
    }

    private fun decodePhoto(uri: String, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
                // Software memory so the pixels can be copied out; sRGB so wide-gamut photos match the pipeline.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val size = info.size
                decoder.setTargetSampleSize(StillFit.sampleSize(size.width, size.height, canvasWidth, canvasHeight))
            }
        } catch (e: FileNotFoundException) {
            throw StillRasterException("A picture is missing, so it cannot be shown", e)
        } catch (e: SecurityException) {
            throw StillRasterException("No permission to read a picture", e)
        } catch (e: ImageDecoder.DecodeException) {
            // Before IOException, which it extends: an unsupported or corrupt file is not a read error.
            throw StillRasterException("This picture format is not supported", e)
        } catch (e: IOException) {
            throw StillRasterException("A picture could not be read", e)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to decode a picture", e)
        }
        // The decoder has applied the EXIF orientation, so width and height are the upright ones.
        val (w, h) = StillFit.contain(decoded.width, decoded.height, canvasWidth, canvasHeight)
        if (w == decoded.width && h == decoded.height) return decoded
        return try {
            Bitmap.createScaledBitmap(decoded, w, h, true)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to scale a picture", e)
        } finally {
            decoded.recycle()
        }
    }

    private fun drawSticker(id: String, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val art = StickerArt.find(id) ?: throw StillRasterException("A sticker is no longer available")
        val side = StillFit.stickerSide(canvasWidth, canvasHeight)
        val bitmap = try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to draw a sticker", e)
        }
        art.draw(Canvas(bitmap), side.toFloat())
        return bitmap
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * Remembers uploaded stills by what they show, handing out the positive keys the native side uses
 * (shared with titles, so these start far above the title cache's). Evicts the least recently used
 * until the estimated texture memory is within [budgetBytes]; evicted keys come back from [drain]
 * so the caller can release their textures. Pure bookkeeping, unit-testable.
 */
class StillKeyCache(private val budgetBytes: Long = DEFAULT_BUDGET_BYTES) {
    private data class Appearance(val ref: StillRef, val canvasWidth: Int, val canvasHeight: Int)

    private class Entry(val key: Int, val bytes: Long)

    private val entries = LinkedHashMap<Appearance, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val evicted = ArrayList<Int>()
    private var used = 0L
    private var next = FIRST_KEY

    init {
        require(budgetBytes > 0) { "budget must be positive" }
    }

    /** The key for [ref] on a canvas, and true if this is the first time it is seen (load and upload it). */
    fun keyFor(ref: StillRef, canvasWidth: Int, canvasHeight: Int): Pair<Int, Boolean> {
        val appearance = Appearance(ref, canvasWidth, canvasHeight)
        entries[appearance]?.let { return it.key to false }
        // A picture never exceeds the canvas, so its pixels are bounded by it; stickers are smaller still.
        val bytes = canvasWidth.toLong() * canvasHeight * BYTES_PER_PIXEL
        val entry = Entry(next++, bytes)
        entries[appearance] = entry
        used += bytes
        while (used > budgetBytes && entries.size > 1) {
            val eldest = entries.entries.first()
            evicted += eldest.value.key
            used -= eldest.value.bytes
            entries.remove(eldest.key)
        }
        return entry.key to true
    }

    /** True while [key] has not been evicted. */
    fun contains(key: Int): Boolean = entries.values.any { it.key == key }

    /** Keys evicted since the last call; their native textures can be released. */
    fun drain(): List<Int> = evicted.toList().also { evicted.clear() }

    companion object {
        /** Well above the title cache's keys, which count up from 1 and hold at most a few dozen. */
        const val FIRST_KEY = 1_000_000
        const val DEFAULT_BUDGET_BYTES = 192L * 1024 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
