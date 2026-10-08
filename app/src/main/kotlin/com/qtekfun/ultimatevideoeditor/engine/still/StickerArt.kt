package com.qtekfun.ultimatevideoeditor.engine.still

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** A built-in sticker as the picker lists it. */
data class StickerInfo(val id: String, val label: String)

/**
 * The built-in sticker set. Shapes are drawn procedurally by [StickerArt] (original artwork, no
 * bundled files) and emoji with the system's emoji font, so nothing needs a licence or a network.
 * Ids are stored in projects: never rename or reuse one.
 */
object StickerIds {
    const val EMOJI_PREFIX = "emoji:"
    const val SHAPE_PREFIX = "shape:"

    val shapes: List<StickerInfo> = listOf(
        StickerInfo("shape:heart", "Heart"),
        StickerInfo("shape:star", "Star"),
        StickerInfo("shape:arrow", "Arrow"),
        StickerInfo("shape:check", "Check"),
        StickerInfo("shape:burst", "Burst"),
        StickerInfo("shape:bubble", "Speech"),
        StickerInfo("shape:ring", "Ring"),
        StickerInfo("shape:exclaim", "Alert"),
    )

    val emoji: List<StickerInfo> = listOf(
        "🔥" to "Fire",
        "❤️" to "Red heart",
        "👍" to "Thumbs up",
        "😂" to "Laughing",
        "🎉" to "Party",
        "⭐" to "Star",
        "💯" to "Hundred",
        "😍" to "Heart eyes",
    ).map { (glyph, label) -> StickerInfo(EMOJI_PREFIX + glyph, label) }

    val all: List<StickerInfo> get() = shapes + emoji

    /**
     * Plain bars the text templates stretch into lower thirds and subtitle plates. They are valid
     * stickers (so saved projects draw them) but are not offered in the picker.
     */
    val templateBars: List<StickerInfo> = listOf(
        StickerInfo("shape:bar-dark", "Bar (dark)"),
        StickerInfo("shape:bar-accent", "Bar (accent)"),
    )

    fun isKnown(id: String): Boolean = all.any { it.id == id } || templateBars.any { it.id == id }
}

/** Draws one sticker into a square of [side] pixels, transparent around the artwork. */
fun interface StickerDrawing {
    fun draw(canvas: Canvas, side: Float)
}

object StickerArt {

    /** The drawing for [id], or null if no built-in sticker has that id. */
    fun find(id: String): StickerDrawing? = when {
        id.startsWith(StickerIds.EMOJI_PREFIX) && StickerIds.isKnown(id) -> emoji(id.removePrefix(StickerIds.EMOJI_PREFIX))
        id.startsWith(StickerIds.SHAPE_PREFIX) && StickerIds.isKnown(id) -> shape(id.removePrefix(StickerIds.SHAPE_PREFIX))
        else -> null
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun emoji(glyph: String) = StickerDrawing { canvas, side ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = side * EMOJI_SIZE
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        canvas.drawText(glyph, side / 2f, side / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    private fun shape(name: String): StickerDrawing? = when (name) {
        "heart" -> StickerDrawing { c, s -> outlined(c, s, heartPath(s), RED) }
        "star" -> StickerDrawing { c, s -> outlined(c, s, starPath(s, points = 5, inner = 0.42f), YELLOW) }
        "burst" -> StickerDrawing { c, s -> outlined(c, s, starPath(s, points = 12, inner = 0.72f), ORANGE) }
        "arrow" -> StickerDrawing { c, s -> outlined(c, s, arrowPath(s), GREEN) }
        "check" -> StickerDrawing { c, s -> check(c, s) }
        "bubble" -> StickerDrawing { c, s -> outlined(c, s, bubblePath(s), WHITE) }
        "ring" -> StickerDrawing { c, s -> c.drawCircle(s / 2f, s / 2f, s * 0.38f, stroke(RED, s * 0.12f)) }
        "exclaim" -> StickerDrawing { c, s -> exclaim(c, s) }
        // Edge to edge and square-cornered, so stretching it into a bar does not distort anything.
        "bar-dark" -> StickerDrawing { c, s -> c.drawRect(0f, 0f, s, s, fill(BAR_DARK)) }
        "bar-accent" -> StickerDrawing { c, s -> c.drawRect(0f, 0f, s, s, fill(BAR_ACCENT)) }
        else -> null
    }

    /** Fills [path] and draws a dark outline so it reads on any footage. */
    private fun outlined(canvas: Canvas, side: Float, path: Path, color: Int) {
        canvas.drawPath(path, fill(color))
        canvas.drawPath(path, stroke(OUTLINE, side * OUTLINE_FRACTION))
    }

    private fun heartPath(s: Float) = Path().apply {
        moveTo(s * 0.5f, s * 0.86f)
        cubicTo(s * 0.04f, s * 0.54f, s * 0.12f, s * 0.14f, s * 0.36f, s * 0.14f)
        cubicTo(s * 0.46f, s * 0.14f, s * 0.5f, s * 0.22f, s * 0.5f, s * 0.28f)
        cubicTo(s * 0.5f, s * 0.22f, s * 0.54f, s * 0.14f, s * 0.64f, s * 0.14f)
        cubicTo(s * 0.88f, s * 0.14f, s * 0.96f, s * 0.54f, s * 0.5f, s * 0.86f)
        close()
    }

    private fun starPath(s: Float, points: Int, inner: Float) = Path().apply {
        val cx = s / 2f
        val cy = s / 2f
        val outer = s * 0.44f
        val steps = points * 2
        for (i in 0 until steps) {
            val radius = if (i % 2 == 0) outer else outer * inner
            val angle = -Math.PI / 2 + i * Math.PI / points
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun arrowPath(s: Float) = Path().apply {
        moveTo(s * 0.10f, s * 0.38f)
        lineTo(s * 0.52f, s * 0.38f)
        lineTo(s * 0.52f, s * 0.16f)
        lineTo(s * 0.92f, s * 0.50f)
        lineTo(s * 0.52f, s * 0.84f)
        lineTo(s * 0.52f, s * 0.62f)
        lineTo(s * 0.10f, s * 0.62f)
        close()
    }

    private fun bubblePath(s: Float) = Path().apply {
        addRoundRect(RectF(s * 0.08f, s * 0.14f, s * 0.92f, s * 0.68f), s * 0.16f, s * 0.16f, Path.Direction.CW)
        moveTo(s * 0.30f, s * 0.66f)
        lineTo(s * 0.26f, s * 0.90f)
        lineTo(s * 0.52f, s * 0.66f)
        close()
    }

    private fun check(canvas: Canvas, s: Float) {
        canvas.drawCircle(s / 2f, s / 2f, s * 0.42f, fill(GREEN))
        canvas.drawCircle(s / 2f, s / 2f, s * 0.42f, stroke(OUTLINE, s * OUTLINE_FRACTION))
        val tick = Path().apply {
            moveTo(s * 0.30f, s * 0.52f)
            lineTo(s * 0.45f, s * 0.67f)
            lineTo(s * 0.72f, s * 0.36f)
        }
        canvas.drawPath(tick, stroke(WHITE, s * 0.10f))
    }

    private fun exclaim(canvas: Canvas, s: Float) {
        val triangle = Path().apply {
            moveTo(s * 0.5f, s * 0.10f)
            lineTo(s * 0.94f, s * 0.86f)
            lineTo(s * 0.06f, s * 0.86f)
            close()
        }
        outlined(canvas, s, triangle, YELLOW)
        canvas.drawLine(s * 0.5f, s * 0.36f, s * 0.5f, s * 0.60f, stroke(OUTLINE, s * 0.08f))
        canvas.drawCircle(s * 0.5f, s * 0.71f, s * 0.04f, fill(OUTLINE))
    }

    private const val BAR_DARK = 0xFF111418.toInt()
    private const val BAR_ACCENT = 0xFFFFB300.toInt()
    private const val EMOJI_SIZE = 0.78f
    private const val OUTLINE_FRACTION = 0.045f
    private const val RED = 0xFFE53935.toInt()
    private const val YELLOW = 0xFFFFC107.toInt()
    private const val ORANGE = 0xFFFF7043.toInt()
    private const val GREEN = 0xFF43A047.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val OUTLINE = 0xFF212121.toInt()
}
