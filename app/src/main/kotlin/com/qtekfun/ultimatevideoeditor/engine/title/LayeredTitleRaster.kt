package com.qtekfun.ultimatevideoeditor.engine.title

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.LayerPlacement
import com.qtekfun.ultimatevideoeditor.domain.LayerShadow
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLayer
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Finds the typeface of an imported font. A missing font must fall back to the default one, never fail. */
fun interface FontResolver {
    fun typeface(fontId: String?, bold: Boolean, italic: Boolean): Typeface

    companion object {
        /** The system font only, for tests and for projects with no imported fonts. */
        val SYSTEM = FontResolver { _, bold, italic -> Typeface.create(Typeface.DEFAULT, styleOf(bold, italic)) }

        fun styleOf(bold: Boolean, italic: Boolean): Int = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
    }
}

/** Loads the picture of an image layer: a bitmap whose longer side is about [targetLongSidePx], or null if it is unavailable. The caller never recycles it. */
fun interface LayerImages {
    fun load(layer: ImageLayer, targetLongSidePx: Int): Bitmap?

    companion object {
        /** No pictures: image layers are skipped. */
        val NONE = LayerImages { _, _ -> null }
    }
}

/**
 * Draws a multilayer title into one bitmap, the way [AndroidTitleRasterizer] draws a plain one: the
 * result is centred on the canvas centre and cropped (symmetrically, see [LayerBounds]) to what the
 * layers cover, so it stays small. Layers are drawn in list order; each is turned, scaled and faded by
 * its own placement. A photo that cannot be loaded is skipped and the rest of the title still draws.
 */
internal class LayeredTitleDrawer(private val images: LayerImages, private val fonts: FontResolver) {

    fun draw(content: TitleContent, canvasWidth: Int, canvasHeight: Int): TitleBitmap {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        val prepared = content.layers.mapNotNull { prepare(it, canvasWidth, canvasHeight) }
        val capX = max((canvasWidth * CAP_FRACTION).toInt(), 1)
        val capY = max((canvasHeight * CAP_FRACTION).toInt(), 1)
        val (halfX, halfY) = LayerBounds.halfExtents(prepared.map { it.footprint }, capX, capY)
        val width = 2 * halfX
        val height = 2 * halfY
        if (width > MAX_SIDE || height > MAX_SIDE) throw TitleRasterException("The title is too large to draw (${width}x$height)")
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw TitleRasterException("Not enough memory to draw the title", e)
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(halfX.toFloat(), halfY.toFloat())
            for (layer in prepared) layer.drawOn(canvas)
            val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
            bitmap.copyPixelsToBuffer(pixels)
            pixels.rewind()
            return TitleBitmap(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun prepare(layer: TitleLayer, canvasWidth: Int, canvasHeight: Int): PreparedLayer? = when (layer) {
        is TextLayer -> PreparedText(layer, canvasWidth, canvasHeight, fonts)
        is ShapeLayer -> PreparedShape(layer, canvasWidth, canvasHeight)
        is ImageLayer -> PreparedImage.of(layer, canvasWidth, canvasHeight, images)
    }

    private companion object {
        /** A title bitmap is at most this fraction of the canvas beyond the canvas on each side: bounds memory for layers pushed far off screen. */
        const val CAP_FRACTION = 0.6
        const val MAX_SIDE = 8192
        const val BYTES_PER_PIXEL = 4
    }
}

/** A layer ready to draw: its unscaled size in pixels, and how to draw itself centred on the origin. */
private abstract class PreparedLayer(private val placement: LayerPlacement, canvasWidth: Int, canvasHeight: Int) {
    /** Unscaled content size and the extra margin of a shadow or outline, in pixels. */
    protected abstract val width: Double
    protected abstract val height: Double
    protected abstract val reach: Double

    private val centerX = placement.offsetX * canvasWidth
    private val centerY = placement.offsetY * canvasHeight

    val footprint: LayerFootprint
        get() = LayerFootprint(centerX, centerY, width * placement.scale, height * placement.scale, placement.rotationDegrees, reach * placement.scale)

    /** Draws the layer centred on (0, 0), unscaled and unrotated. */
    protected abstract fun render(canvas: Canvas)

    fun drawOn(canvas: Canvas) {
        canvas.save()
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate(placement.rotationDegrees.toFloat())
        canvas.scale(placement.scale.toFloat(), placement.scale.toFloat())
        if (placement.opacity < 1.0) {
            val halfW = (width / 2 + reach).toFloat()
            val halfH = (height / 2 + reach).toFloat()
            canvas.saveLayerAlpha(-halfW, -halfH, halfW, halfH, (placement.opacity * OPAQUE).roundToInt().coerceIn(0, OPAQUE))
            render(canvas)
            canvas.restore()
        } else {
            render(canvas)
        }
        canvas.restore()
    }

    private companion object {
        const val OPAQUE = 255
    }
}

/** How far a shadow reaches beyond its layer, in pixels. */
private fun shadowReach(shadow: LayerShadow?, canvasHeight: Int): Double =
    if (shadow == null) 0.0 else (abs(shadow.dxFraction) + abs(shadow.dyFraction) + 2 * shadow.blurFraction) * canvasHeight

private fun applyShadow(paint: Paint, shadow: LayerShadow?, canvasHeight: Int) {
    if (shadow == null) {
        paint.clearShadowLayer()
    } else {
        // A radius of 0 would remove the shadow layer; a hard shadow uses a sub-pixel blur instead.
        paint.setShadowLayer(max((shadow.blurFraction * canvasHeight).toFloat(), MIN_BLUR), (shadow.dxFraction * canvasHeight).toFloat(), (shadow.dyFraction * canvasHeight).toFloat(), shadow.colorArgb)
    }
}

private const val MIN_BLUR = 0.5f

private class PreparedText(
    private val layer: TextLayer,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    fonts: FontResolver,
) : PreparedLayer(layer.placement, canvasWidth, canvasHeight) {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = (layer.sizeFraction * canvasHeight).toFloat().coerceAtLeast(MIN_TEXT_PX)
        color = layer.colorArgb
        typeface = fonts.typeface(layer.fontId, layer.bold, layer.italic)
        letterSpacing = layer.letterSpacing.toFloat()
    }
    private val alignment = when (layer.alignment) {
        TitleAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
        TitleAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        TitleAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }
    private val textLayout: StaticLayout
    private val textWidth: Int
    private val pad = (layer.box?.paddingFraction ?: 0.0) * canvasHeight

    init {
        val maxLineWidth = max((canvasWidth * MAX_LINE_FRACTION).toInt(), 1)
        val text = layer.text
        // Measure with the widest allowed line, then lay out again at the real width so alignment works inside the block.
        val measured = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxLineWidth)
            .setAlignment(alignment).setLineSpacing(0f, layer.lineHeight.toFloat()).build()
        var blockWidth = 1
        for (line in 0 until measured.lineCount) blockWidth = max(blockWidth, ceil(measured.getLineWidth(line).toDouble()).toInt())
        textWidth = blockWidth
        textLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, blockWidth)
            .setAlignment(alignment).setLineSpacing(0f, layer.lineHeight.toFloat()).build()
    }

    override val width: Double = textWidth + 2 * pad
    override val height: Double = textLayout.height + 2 * pad
    override val reach: Double =
        max(shadowReach(layer.shadow, canvasHeight), (layer.border?.widthFraction ?: 0.0) * canvasHeight / 2.0) + EDGE_PADDING

    override fun render(canvas: Canvas) {
        canvas.translate((-width / 2).toFloat(), (-height / 2).toFloat())
        val box = layer.box
        if (box != null) {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box.colorArgb }
            val radius = (box.cornerRadiusFraction * canvasHeight).toFloat()
            // The box carries the shadow when there is one: the text over it must not throw a second shadow.
            applyShadow(boxPaint, layer.shadow, canvasHeight)
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, boxPaint)
        }
        canvas.translate(pad.toFloat(), pad.toFloat())
        val border = layer.border
        val textShadow = if (box == null) layer.shadow else null
        if (border != null) {
            // Stroke first, then the fill on top, so only the outside of the glyphs takes the border colour.
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (border.widthFraction * canvasHeight * 2.0).toFloat()
            paint.color = border.colorArgb
            applyShadow(paint, textShadow, canvasHeight)
            textLayout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = layer.colorArgb
            paint.clearShadowLayer()
            textLayout.draw(canvas)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = layer.colorArgb
            applyShadow(paint, textShadow, canvasHeight)
            textLayout.draw(canvas)
        }
    }

    private companion object {
        const val MIN_TEXT_PX = 4f
        const val MAX_LINE_FRACTION = 0.9
        const val EDGE_PADDING = 2.0
    }
}

private class PreparedShape(
    private val layer: ShapeLayer,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
) : PreparedLayer(layer.placement, canvasWidth, canvasHeight) {
    override val width: Double = max(layer.widthFraction * canvasWidth, 1.0)
    override val height: Double = max(layer.heightFraction * canvasHeight, 1.0)
    override val reach: Double =
        max(shadowReach(layer.shadow, canvasHeight), (layer.stroke?.widthFraction ?: 0.0) * canvasHeight / 2.0) + EDGE_PADDING

    override fun render(canvas: Canvas) {
        val rect = RectF((-width / 2).toFloat(), (-height / 2).toFloat(), (width / 2).toFloat(), (height / 2).toFloat())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = layer.fillArgb
        }
        applyShadow(fill, layer.shadow, canvasHeight)
        draw(canvas, rect, fill)
        val stroke = layer.stroke
        if (stroke != null) {
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (stroke.widthFraction * canvasHeight).toFloat()
                color = stroke.colorArgb
            }
            draw(canvas, rect, outline)
        }
    }

    private fun draw(canvas: Canvas, rect: RectF, paint: Paint) {
        when (layer.kind) {
            ShapeKind.RECT -> canvas.drawRect(rect, paint)
            ShapeKind.ROUNDED_RECT -> {
                val radius = (layer.cornerRadiusFraction * min(width, height)).toFloat()
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            ShapeKind.ELLIPSE -> canvas.drawOval(rect, paint)
            ShapeKind.LINE -> {
                val radius = (height / 2).toFloat()
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private companion object {
        const val EDGE_PADDING = 2.0
    }
}

private class PreparedImage private constructor(
    private val layer: ImageLayer,
    private val picture: Bitmap,
    private val drawWidth: Int,
    private val drawHeight: Int,
    canvasWidth: Int,
    private val canvasHeight: Int,
) : PreparedLayer(layer.placement, canvasWidth, canvasHeight) {
    override val width: Double = drawWidth.toDouble()
    override val height: Double = drawHeight.toDouble()
    override val reach: Double = shadowReach(layer.shadow, canvasHeight) + EDGE_PADDING

    override fun render(canvas: Canvas) {
        val destination = RectF((-width / 2).toFloat(), (-height / 2).toFloat(), (width / 2).toFloat(), (height / 2).toFloat())
        val shadow = layer.shadow
        if (shadow != null) drawShadow(canvas, destination, shadow)
        canvas.drawBitmap(picture, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** A blurred, tinted copy of the picture's outline, offset by the shadow's distance. */
    private fun drawShadow(canvas: Canvas, destination: RectF, shadow: LayerShadow) {
        val scaled = if (picture.width == drawWidth && picture.height == drawHeight) picture else Bitmap.createScaledBitmap(picture, drawWidth, drawHeight, true)
        val offset = IntArray(2)
        val blur = max((shadow.blurFraction * canvasHeight).toFloat(), MIN_BLUR)
        val maskPaint = Paint().apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
        val alpha = scaled.extractAlpha(maskPaint, offset)
        val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadow.colorArgb }
        canvas.drawBitmap(alpha, destination.left + offset[0] + (shadow.dxFraction * canvasHeight).toFloat(), destination.top + offset[1] + (shadow.dyFraction * canvasHeight).toFloat(), tint)
        alpha.recycle()
        if (scaled !== picture) scaled.recycle()
    }

    companion object {
        private const val EDGE_PADDING = 2.0

        /** The image layer, or null when its picture cannot be loaded (the layer is then skipped). */
        fun of(layer: ImageLayer, canvasWidth: Int, canvasHeight: Int, images: LayerImages): PreparedImage? {
            val target = max((layer.sizeFraction * min(canvasWidth, canvasHeight)).roundToInt(), 1)
            val picture = images.load(layer, target) ?: return null
            if (picture.width <= 0 || picture.height <= 0) return null
            val scale = target.toDouble() / max(picture.width, picture.height)
            return PreparedImage(
                layer,
                picture,
                max((picture.width * scale).roundToInt(), 1),
                max((picture.height * scale).roundToInt(), 1),
                canvasWidth,
                canvasHeight,
            )
        }
    }
}
