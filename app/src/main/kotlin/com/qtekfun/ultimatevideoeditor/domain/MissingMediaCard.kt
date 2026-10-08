package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The picture the preview shows in place of a clip whose media cannot be read right now (a drive that is not plugged in, a lost
 * grant): a dark card with diagonal hatching, "Media missing" and the file's name. It is built as an ordinary multilayer
 * title, so the preview rasterises and uploads it through the title path and no native code or font handling is involved.
 *
 * It exists for the PREVIEW only. Export and "save frame" never see it: both refuse to start while a clip needs unreadable
 * media (`ExportViewModel`, `StillFrameViewModel`), and neither builds its layers from the preview's request list.
 */
object MissingMediaCard {
    const val HEADLINE = "Media missing"
    private const val MAX_NAME_CHARS = 48
    private const val BANDS = 11
    private const val BACKGROUND_ARGB = 0xFF1B1B1F.toInt()
    private const val BAND_ARGB = 0xFF2B2B33.toInt()
    private const val HEADLINE_ARGB = 0xFFFFB4AB.toInt()
    private const val NAME_ARGB = 0xFFC9C4CE.toInt()
    private const val PLATE_ARGB = 0xE61B1B1F.toInt()

    private val background = ShapeLayer(
        kind = ShapeKind.RECT,
        widthFraction = 2.0,
        heightFraction = 2.0,
        fillArgb = BACKGROUND_ARGB,
    )

    /** The card for a file called [name] on a canvas of [canvasWidth] x [canvasHeight] project pixels (the hatch follows the shape). */
    fun contentFor(name: String, canvasWidth: Int, canvasHeight: Int): TitleContent {
        val w = max(canvasWidth, 1).toDouble()
        val h = max(canvasHeight, 1).toDouble()
        // 45 degree bands x - y = c cover the canvas for c within +-(w + h) / 2, spread over BANDS positions.
        // (A canvas more than 3:1 tall would need bands beyond the title's placement limit; its corners stay plain.)
        val reach = ((1.0 + h / w) / 2.0).coerceAtMost(LayerPlacement.MAX_OFFSET)
        val step = 2.0 * reach / (BANDS - 1)
        // A band is half of the perpendicular distance between neighbours thick.
        val thickness = step * w / sqrt(2.0) / 2.0
        val bands = (0 until BANDS).map { i ->
            ShapeLayer(
                kind = ShapeKind.LINE,
                widthFraction = 2.0,
                heightFraction = (thickness / h).coerceIn(ShapeLayer.MIN_SIDE, ShapeLayer.MAX_SIDE),
                fillArgb = BAND_ARGB,
                placement = LayerPlacement(offsetX = -reach + i * step, rotationDegrees = -45.0),
            )
        }
        val headline = TextLayer(
            text = HEADLINE,
            sizeFraction = 0.07,
            colorArgb = HEADLINE_ARGB,
            bold = true,
            box = LayerBox(PLATE_ARGB, 0.02, 0.012),
            placement = LayerPlacement(offsetY = -0.05),
        )
        val file = TextLayer(
            text = shortened(name),
            sizeFraction = 0.045,
            colorArgb = NAME_ARGB,
            box = LayerBox(PLATE_ARGB, 0.014, 0.01),
            placement = LayerPlacement(offsetY = 0.07),
        )
        return TitleContent(text = HEADLINE, layers = listOf(background) + bands + headline + file)
    }

    /** True when [content] is a card made by [contentFor]; the export plan must never hold one. */
    fun isCard(content: TitleContent): Boolean = content.layers.firstOrNull() == background

    private fun shortened(name: String): String {
        val clean = name.replace('\n', ' ').trim().ifEmpty { "(unnamed file)" }
        return if (clean.length <= MAX_NAME_CHARS) clean else clean.take(MAX_NAME_CHARS - 1) + "…"
    }
}
