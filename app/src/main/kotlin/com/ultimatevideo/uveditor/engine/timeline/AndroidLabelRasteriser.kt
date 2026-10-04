package com.ultimatevideo.uveditor.engine.timeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Makes the canvas's text bitmaps with the system typeface (and the system's fallback fonts for accents, symbols and
 * emoji). Text is white on transparent so the canvas can tint it; a bitmap that has colour of its own (an emoji) is
 * flagged and drawn untinted. Sizes are in dp times the screen density, drawn 1:1 by the canvas, so the text is sharp.
 * Not thread-safe: one instance is used by the [LabelPump] thread only.
 */
class AndroidLabelRasteriser(density: Float) : LabelRasteriser {
    private val paints = arrayOf(
        paint(LABEL_DP * density, bold = false),
        paint(SMALL_DP * density, bold = false),
        paint(LABEL_DP * density, bold = true),
    )
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_BYTES).order(ByteOrder.nativeOrder())

    override fun render(need: LabelNeed): RasterLabel? {
        if (need.text.isEmpty()) return null
        val paint = paints[need.sizeClass.coerceIn(0, paints.size - 1)]
        val metrics = paint.fontMetricsInt
        val height = metrics.bottom - metrics.top
        // One pixel of slack on a multi-character label for glyphs that overhang their advance; a single glyph is exactly
        // its advance wide, so the canvas can set text by adding those widths.
        val advance = ceil(paint.measureText(need.text)).toInt()
        val width = minOf(MAX_WIDTH, advance + if (need.text.length > 1) 1 else 0)
        if (width <= 0 || height <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        try {
            Canvas(bitmap).drawText(need.text, 0f, -metrics.top.toFloat(), paint)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val colour = pixels.any { p -> Color.alpha(p) > 32 && (Color.red(p) < 240 || Color.green(p) < 240 || Color.blue(p) < 240) }
            val bytes = width * height * 4
            if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            scratch.clear()
            scratch.limit(bytes)
            bitmap.copyPixelsToBuffer(scratch)
            scratch.rewind()
            return RasterLabel(width, height, colour, scratch)
        } finally {
            bitmap.recycle()
        }
    }

    private fun paint(sizePx: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textSize = sizePx
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private companion object {
        const val LABEL_DP = 11f
        const val SMALL_DP = 9.5f
        const val MAX_WIDTH = 1600
        const val INITIAL_BYTES = 64 * 1024
    }
}
