package com.ultimatevideo.uveditor.engine.title

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * A rasterised title: [width] x [height] premultiplied RGBA pixels, top row first, in a direct
 * buffer. The size is in project canvas pixels, so the compositor draws it 1:1 (then applies the
 * clip's transform). Shared by the preview and the exporter, which is what keeps them identical.
 */
class TitleBitmap(val width: Int, val height: Int, val pixels: ByteBuffer) {
    init {
        require(width > 0 && height > 0) { "title bitmap must not be empty: ${width}x$height" }
        require(pixels.isDirect && pixels.remaining() >= width * height * BYTES_PER_PIXEL) { "title pixels do not match ${width}x$height" }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

/** Failure to rasterise a title (for example a size the device cannot allocate). */
class TitleRasterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns title content into pixels for a canvas. Implementations must be deterministic. */
fun interface TitleRasterizer {
    /** @throws TitleRasterException if the title cannot be drawn. */
    fun rasterize(content: TitleContent, canvasWidth: Int, canvasHeight: Int): TitleBitmap
}

/**
 * Draws text with the platform's text stack. The font size is `sizeFraction` of the canvas height,
 * lines wrap at 90 % of the canvas width, and the bitmap is cropped to the text block plus a small
 * margin, so it stays small however large the canvas is.
 */
class AndroidTitleRasterizer : TitleRasterizer {

    override fun rasterize(content: TitleContent, canvasWidth: Int, canvasHeight: Int): TitleBitmap {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = (content.sizeFraction * canvasHeight).toFloat().coerceAtLeast(MIN_TEXT_PX)
            color = content.colorArgb
            typeface = if (content.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxLineWidth = max((canvasWidth * MAX_LINE_FRACTION).toInt(), 1)
        val alignment = when (content.alignment) {
            TitleAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TitleAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TitleAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        // Measure with the widest allowed line, then lay out again at the real width so alignment
        // works inside the cropped block.
        val measured = StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, maxLineWidth).setAlignment(alignment).build()
        var blockWidth = 1
        for (line in 0 until measured.lineCount) blockWidth = max(blockWidth, ceil(measured.getLineWidth(line).toDouble()).toInt())
        val layout = StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, blockWidth).setAlignment(alignment).build()

        val margin = ceil(paint.textSize * MARGIN_FRACTION).toInt()
        val width = blockWidth + 2 * margin
        val height = layout.height + 2 * margin
        if (width > MAX_SIDE || height > MAX_SIDE) throw TitleRasterException("The title is too large to draw (${width}x$height)")

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw TitleRasterException("Not enough memory to draw the title", e)
        }
        try {
            Canvas(bitmap).apply {
                translate(margin.toFloat(), margin.toFloat())
                layout.draw(this)
            }
            val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
            bitmap.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return TitleBitmap(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MIN_TEXT_PX = 4f
        const val MAX_LINE_FRACTION = 0.9
        const val MARGIN_FRACTION = 0.1f
        const val MAX_SIDE = 8192
        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * Remembers rasterised titles by what they look like, handing out the small positive keys the
 * native side uses. [keyFor] returns a key and whether it still has to be uploaded; keys of titles
 * dropped to stay within [capacity] are returned from [drain] so the caller can release their
 * textures. Pure bookkeeping: no Android types, unit-testable.
 */
class TitleKeyCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private data class Appearance(val content: TitleContent, val canvasWidth: Int, val canvasHeight: Int)

    private val keys = LinkedHashMap<Appearance, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val evicted = ArrayList<Int>()
    private var next = 1

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** The key for [content] on a canvas, and true if this is the first time it is seen (upload it). */
    fun keyFor(content: TitleContent, canvasWidth: Int, canvasHeight: Int): Pair<Int, Boolean> {
        val appearance = Appearance(content, canvasWidth, canvasHeight)
        keys[appearance]?.let { return it to false }
        val key = next++
        keys[appearance] = key
        while (keys.size > capacity) {
            val eldest = keys.entries.first()
            evicted += eldest.value
            keys.remove(eldest.key)
        }
        return key to true
    }

    /** Keys evicted since the last call; their native textures can be released. */
    fun drain(): List<Int> = evicted.toList().also { evicted.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 24
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
