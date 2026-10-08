package com.qtekfun.ultimatevideoeditor.engine.title

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleAnimation
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLook
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionAnimator
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * A rasterised title: [width] x [height] premultiplied RGBA pixels, top row first, in a direct
 * buffer. The size is in project canvas pixels, so the compositor draws it 1:1 (then applies the
 * clip's transform). Shared by the preview and the exporter, which is what keeps them identical.
 */
class TitleBitmap(
    val width: Int,
    val height: Int,
    val pixels: ByteBuffer,
    /** Canvas pixels the picture covers at scale 1; a still keeps its native size and is drawn scaled to this. */
    val displayWidth: Int = width,
    val displayHeight: Int = height,
) {
    init {
        require(width > 0 && height > 0) { "title bitmap must not be empty: ${width}x$height" }
        require(displayWidth > 0 && displayHeight > 0) { "display size must be positive: ${displayWidth}x$displayHeight" }
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
class AndroidTitleRasterizer(
    images: LayerImages = LayerImages.NONE,
    fonts: FontResolver = FontResolver.SYSTEM,
) : TitleRasterizer {
    private val layered = LayeredTitleDrawer(images, fonts)

    override fun rasterize(content: TitleContent, canvasWidth: Int, canvasHeight: Int): TitleBitmap {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        // A multilayer title (text, shapes, pictures) has its own drawer; the plain one below also draws captions.
        if (content.isLayered) return layered.draw(content, canvasWidth, canvasHeight)
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
        // An animated caption hides or highlights words with spans (hidden words keep their space, so
        // the block does not move); the active word may be redrawn larger on top. Without a drawable
        // animation the text is drawn plainly.
        val ranges = if (content.look.isFull || content.animation == TitleAnimation.NONE) null else CaptionAnimator.wordRanges(content.text, content.words)
        val spannable = if (ranges != null) SpannableString(content.text) else null
        val styled: CharSequence = spannable ?: content.text
        // Measure with the widest allowed line, then lay out again at the real width so alignment
        // works inside the cropped block.
        val measured = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, maxLineWidth).setAlignment(alignment).build()
        var blockWidth = 1
        for (line in 0 until measured.lineCount) blockWidth = max(blockWidth, ceil(measured.getLineWidth(line).toDouble()).toInt())
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, blockWidth).setAlignment(alignment).build()

        // The active word is redrawn larger on top when it sits on one line and the look asks for it.
        val scaledWord = if (ranges != null) scaledWordOf(content.look, ranges, layout) else null
        if (spannable != null && ranges != null) applySpans(spannable, content, ranges, redrawn = scaledWord != null)
        val growth = if (scaledWord != null) (content.look.activePercent - 100) / 100f * ACTIVE_MARGIN_FACTOR else 0f
        val margin = ceil(paint.textSize * (MARGIN_FRACTION + growth)).toInt()
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
                if (content.outline) {
                    // Stroke first, then the fill on top, so only the outside of the glyphs is dark.
                    paint.style = Paint.Style.STROKE
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = paint.textSize * OUTLINE_FRACTION
                    paint.color = OUTLINE_COLOR
                    layout.draw(this)
                    scaledWord?.draw(this, content, paint, stroke = true)
                    paint.style = Paint.Style.FILL
                    paint.color = content.colorArgb
                }
                layout.draw(this)
                scaledWord?.draw(this, content, paint, stroke = false)
            }
            val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
            bitmap.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return TitleBitmap(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /** The active word to redraw enlarged, when the look wants it and the word fits on one line. */
    private fun scaledWordOf(look: TitleLook, ranges: List<IntRange>, layout: StaticLayout): ScaledWord? {
        val index = look.activeWord
        if (index !in ranges.indices || look.activePercent == 100) return null
        val range = ranges[index]
        val line = layout.getLineForOffset(range.first)
        if (layout.getLineForOffset(range.last) != line) return null
        return ScaledWord(range, line, look.activePercent / 100f, layout)
    }

    /** Hides the words and letters the look has not reached yet and highlights the active word. */
    private fun applySpans(text: SpannableString, content: TitleContent, ranges: List<IntRange>, redrawn: Boolean) {
        val look = content.look
        fun hide(from: Int, to: Int) {
            if (to > from) text.setSpan(PassColorSpan(0, 0), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (look.visibleWords != TitleLook.ALL) {
            for (i in look.visibleWords.coerceAtLeast(0) until ranges.size) hide(ranges[i].first, ranges[i].last + 1)
        }
        if (look.visibleChars != TitleLook.ALL) hide(look.visibleChars.coerceIn(0, text.length), text.length)
        val active = look.activeWord
        if (active in ranges.indices) {
            val range = ranges[active]
            if (redrawn) {
                // Drawn again, larger, by ScaledWord; leave a hole here so it is not drawn twice.
                hide(range.first, range.last + 1)
            } else {
                text.setSpan(PassColorSpan(content.highlightArgb, STROKE_COLOR), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private companion object {
        const val MIN_TEXT_PX = 4f
        const val MAX_LINE_FRACTION = 0.9
        const val MARGIN_FRACTION = 0.1f
        const val ACTIVE_MARGIN_FACTOR = 1.5f
        const val OUTLINE_FRACTION = 0.14f
        const val OUTLINE_COLOR = STROKE_COLOR
        const val MAX_SIDE = 8192
        const val BYTES_PER_PIXEL = 4
    }
}

private const val STROKE_COLOR = 0xFF000000.toInt()

/** Colours text by drawing pass: [fill] for the glyphs, [stroke] for the outline pass (0 hides it). */
private class PassColorSpan(private val fill: Int, private val stroke: Int) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = if (tp.style == Paint.Style.STROKE) stroke else fill
    }
}

/** A word drawn again, scaled around its own centre, so growing it does not move the other words. */
private class ScaledWord(val range: IntRange, val line: Int, val scale: Float, val layout: StaticLayout) {
    fun draw(canvas: Canvas, content: TitleContent, paint: TextPaint, stroke: Boolean) {
        val left = layout.getPrimaryHorizontal(range.first)
        val right = layout.getPrimaryHorizontal(range.last + 1)
        val centreY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        val saved = paint.color
        paint.color = if (stroke) STROKE_COLOR else content.highlightArgb
        canvas.save()
        canvas.scale(scale, scale, (left + right) / 2f, centreY)
        canvas.drawText(content.text, range.first, range.last + 1, left, layout.getLineBaseline(line).toFloat(), paint)
        canvas.restore()
        paint.color = saved
    }
}

/**
 * Remembers rasterised titles by what they look like, handing out the small positive keys the
 * native side uses. [keyFor] returns a key and whether it still has to be uploaded; keys of titles
 * dropped to stay within [capacity] are returned from [drain] so the caller can release their
 * textures. Pure bookkeeping: no Android types, unit-testable.
 */
class TitleKeyCache(private val capacity: Int = DEFAULT_CAPACITY) {
    // Word timing is left out of the key: it decides which look to draw, not what a look is.
    private class Appearance(content: TitleContent, val canvasWidth: Int, val canvasHeight: Int) {
        val content: TitleContent = content.withoutTiming()
        override fun equals(other: Any?) = other is Appearance && other.content == content && other.canvasWidth == canvasWidth && other.canvasHeight == canvasHeight
        override fun hashCode() = (content.hashCode() * 31 + canvasWidth) * 31 + canvasHeight
    }

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

    /** Forgets every title (a font or picture they use changed); all their keys come back from [drain]. */
    fun clear() {
        evicted += keys.values
        keys.clear()
    }

    companion object {
        // An animated phrase has a handful of looks (a typewriter one per letter), and a clip may hold several phrases.
        const val DEFAULT_CAPACITY = 96
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
