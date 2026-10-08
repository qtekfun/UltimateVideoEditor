package com.qtekfun.ultimatevideoeditor.engine.still

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import com.qtekfun.ultimatevideoeditor.data.readAtMost
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.engine.title.TitleBitmap
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a still clip shows: for a [StillKind.PHOTO] [id] is the image's `content://` URI, for a
 * [StillKind.STICKER] it is a built-in sticker id (see [StickerIds]). [frame] is the animation frame of an animated
 * GIF or WebP (0 for everything else, which is also the first frame of an animation).
 */
data class StillRef(val kind: StillKind, val id: String, val frame: Int = 0)

/** A photo or sticker could not be turned into pixels. The message is fit to show to the user. */
class StillRasterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a still into the premultiplied RGBA picture the compositor draws like a title: centred on the canvas, then
 * transformed by the clip. Photos keep their native size in the texture (reduced only when larger than their fit)
 * and carry the display size of the contain fit, EXIF orientation applied; stickers get a size relative to the canvas. The preview and
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

    /**
     * How a picture is stored and drawn: the texture keeps the picture's native size and is only reduced when the
     * picture is larger than its fit (a 50 MP photo on a 1080p canvas); the GPU scales it to the display size, which
     * is the same contain fit as before, so what is drawn does not change but a small picture no longer costs a
     * canvas-size bitmap.
     */
    data class Plan(val textureWidth: Int, val textureHeight: Int, val displayWidth: Int, val displayHeight: Int) {
        val textureBytes: Long get() = textureWidth.toLong() * textureHeight * 4
    }

    fun plan(srcWidth: Int, srcHeight: Int, canvasWidth: Int, canvasHeight: Int): Plan {
        val (dw, dh) = contain(srcWidth, srcHeight, canvasWidth, canvasHeight)
        val reduce = srcWidth.toLong() * srcHeight > dw.toLong() * dh
        return if (reduce) Plan(dw, dh, dw, dh) else Plan(srcWidth, srcHeight, dw, dh)
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
        val stored = when (ref.kind) {
            StillKind.PHOTO -> {
                val raw = decodeAnimatedFrame(ref) ?: decodePhoto(ref.id, canvasWidth, canvasHeight)
                val plan = StillFit.plan(raw.width, raw.height, canvasWidth, canvasHeight)
                Stored(scaleTo(raw, plan.textureWidth, plan.textureHeight), plan.displayWidth, plan.displayHeight)
            }
            StillKind.STICKER -> drawSticker(ref.id, canvasWidth, canvasHeight).let { Stored(it, it.width, it.height) }
        }
        val bitmap = stored.bitmap
        var argb: Bitmap? = null
        try {
            // Wide-colour or HDR photos can decode to a float config; the compositor wants 8-bit RGBA.
            argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = ByteBuffer.allocateDirect(argb.width * argb.height * BYTES_PER_PIXEL)
            argb.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return TitleBitmap(argb.width, argb.height, pixels, stored.displayWidth, stored.displayHeight)
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
        return decoded
    }

    /** A bitmap stored as a texture and the canvas size it is drawn at. */
    private class Stored(val bitmap: Bitmap, val displayWidth: Int, val displayHeight: Int)

    /** [decoded] scaled to [w] x [h]; the input bitmap is recycled when a new one is made. */
    private fun scaleTo(decoded: Bitmap, w: Int, h: Int): Bitmap {
        if (w == decoded.width && h == decoded.height) return decoded
        return try {
            Bitmap.createScaledBitmap(decoded, w, h, true)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to scale a picture", e)
        } finally {
            decoded.recycle()
        }
    }

    // The last few animated pictures opened, so playing or exporting one does not re-read the file for every frame.
    private val animations = object : LinkedHashMap<String, AnimatedPicture?>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimatedPicture?>?) = size > MAX_OPEN_ANIMATIONS
    }

    private fun animationOf(uri: String): AnimatedPicture? = synchronized(animations) {
        if (animations.containsKey(uri)) return animations[uri]
        val animation = try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readAtMost(MAX_ANIMATION_BYTES) }
            when {
                bytes == null -> null
                AnimationSniff.isGif(bytes) -> GifAnimation.parse(bytes)
                AnimationSniff.isWebp(bytes) -> WebpAnimation.parse(bytes, PlatformWebpDecoder)
                else -> null
            }
        } catch (e: GifFormatException) {
            null // the platform decoder shows the first frame
        } catch (e: WebpFormatException) {
            null // a still WebP, or one this reader cannot use: the platform decoder shows its first frame
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        animations[uri] = animation
        return animation
    }

    /**
     * Frame [StillRef.frame] of an animated GIF or WebP, composited by [AnimatedPicture]; null for a still photo,
     * frame 0, and any file that is not an animation this reader handles (the platform decoder shows its first frame).
     */
    private fun decodeAnimatedFrame(ref: StillRef): Bitmap? {
        if (ref.frame <= 0) return null
        val animation = animationOf(ref.id) ?: return null
        val pixels = try {
            animation.render(ref.frame % animation.frameCount)
        } catch (e: WebpFormatException) {
            throw StillRasterException("An animated picture could not be decoded", e)
        }
        return try {
            Bitmap.createBitmap(pixels, animation.width, animation.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw StillRasterException("Not enough memory to show an animated picture", e)
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
        const val MAX_OPEN_ANIMATIONS = 2
        const val MAX_ANIMATION_BYTES = 48 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4
    }
}

/** The memory one place decides for decoded pictures: the preview's uploaded stills and the exporter's frame cache. */
object PictureBudget {
    const val DEFAULT_BYTES = 128L * 1024 * 1024
}

/**
 * Remembers uploaded stills by what they show, handing out the positive keys the native side uses
 * (shared with titles, so these start far above the title cache's). Evicts the least recently used
 * until the estimated texture memory is within [budgetBytes]; evicted keys come back from [drain]
 * so the caller can release their textures. Pure bookkeeping, unit-testable.
 */
class StillKeyCache(private val budgetBytes: Long = DEFAULT_BUDGET_BYTES) {
    private data class Appearance(val ref: StillRef, val canvasWidth: Int, val canvasHeight: Int)

    private class Entry(val key: Int, var bytes: Long)

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
        // A guess until the picture is decoded and [resize] gives its real size: a picture never exceeds the canvas,
        // but a native-size one is usually far smaller, so the guess is capped.
        val bytes = minOf(canvasWidth.toLong() * canvasHeight * BYTES_PER_PIXEL, INITIAL_ESTIMATE_BYTES)
        val entry = Entry(next++, bytes)
        entries[appearance] = entry
        used += bytes
        evictOver(entry.key)
        return entry.key to true
    }

    /** Records the real size of the texture behind [key] once it is decoded, and evicts older pictures if needed. */
    fun resize(key: Int, bytes: Long) {
        val entry = entries.values.firstOrNull { it.key == key } ?: return
        used += bytes - entry.bytes
        entry.bytes = bytes
        evictOver(key)
    }

    /** Estimated texture memory of the pictures now held. */
    val usedBytes: Long get() = used

    // Evicts the least recently used until within budget, never [keep] and never the last picture.
    private fun evictOver(keep: Int) {
        val it = entries.entries.iterator()
        while (used > budgetBytes && entries.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.value.key == keep) continue
            evicted += eldest.value.key
            used -= eldest.value.bytes
            it.remove()
        }
    }

    /** True while [key] has not been evicted. */
    fun contains(key: Int): Boolean = entries.values.any { it.key == key }

    /** Keys evicted since the last call; their native textures can be released. */
    fun drain(): List<Int> = evicted.toList().also { evicted.clear() }

    companion object {
        /** Well above the title cache's keys, which count up from 1 and hold at most a few dozen. */
        const val FIRST_KEY = 1_000_000
        const val DEFAULT_BUDGET_BYTES = PictureBudget.DEFAULT_BYTES
        private const val INITIAL_ESTIMATE_BYTES = 4L * 1024 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/** Recognises the animated formats by their first bytes, not by what the file claims to be. */
object AnimationSniff {
    fun isGif(b: ByteArray): Boolean = b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    fun isWebp(b: ByteArray): Boolean = b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
}

/** Decodes the standalone still WebP of one animation frame with the platform: lossy, lossless and alpha. */
internal object PlatformWebpDecoder : WebpStillDecoder {
    override fun decode(webp: ByteArray): WebpPixels {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(webp))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Straight alpha: the animation composites frames itself, and the compositor premultiplies once.
                decoder.setUnpremultipliedRequired(true)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (e: IOException) {
            throw WebpFormatException("a WebP frame could not be decoded: ${e.message}")
        }
        try {
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return WebpPixels(bitmap.width, bitmap.height, argb)
        } finally {
            bitmap.recycle()
        }
    }
}
