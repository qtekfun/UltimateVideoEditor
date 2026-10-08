package com.qtekfun.ultimatevideoeditor.engine.title

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TitleRasterizerInstrumentedTest {

    private val rasterizer = AndroidTitleRasterizer()

    private fun alphaAt(bitmap: TitleBitmap, x: Int, y: Int): Int = bitmap.pixels.get((y * bitmap.width + x) * 4 + 3).toInt() and 0xFF

    private fun opaqueColumns(bitmap: TitleBitmap, rowRange: IntRange): List<Int> =
        (0 until bitmap.width).filter { x -> rowRange.any { y -> alphaAt(bitmap, x, y) > 128 } }

    @Test
    fun drawsTextWithATransparentMargin() {
        val bitmap = rasterizer.rasterize(TitleContent("Hello", sizeFraction = 0.1, colorArgb = 0xFFFF0000.toInt()), 1920, 1080)

        // About 108 px text: a few hundred pixels wide, cropped to the block plus a margin.
        assertTrue("width ${bitmap.width}", bitmap.width in 100..1920)
        assertTrue("height ${bitmap.height}", bitmap.height in 50..400)
        assertEquals(0, alphaAt(bitmap, 0, 0))
        var opaque = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (alphaAt(bitmap, x, y) > 200) opaque++
        assertTrue("opaque pixels $opaque", opaque > 500)
    }

    @Test
    fun pixelsArePremultipliedRgba() {
        val bitmap = rasterizer.rasterize(TitleContent("MMMM", sizeFraction = 0.2, colorArgb = 0xFFFF0000.toInt()), 1920, 1080)

        // Red text: wherever alpha is full the pixel is pure red, red channel first in memory.
        var checked = 0
        for (i in 0 until bitmap.width * bitmap.height) {
            val a = bitmap.pixels.get(i * 4 + 3).toInt() and 0xFF
            if (a == 255) {
                assertEquals(255, bitmap.pixels.get(i * 4).toInt() and 0xFF)
                assertEquals(0, bitmap.pixels.get(i * 4 + 1).toInt() and 0xFF)
                assertEquals(0, bitmap.pixels.get(i * 4 + 2).toInt() and 0xFF)
                checked++
            }
        }
        assertTrue("fully opaque pixels $checked", checked > 100)
    }

    @Test
    fun sameContentRendersIdentically() {
        val content = TitleContent("Same", sizeFraction = 0.08)

        val first = rasterizer.rasterize(content, 1280, 720)
        val second = rasterizer.rasterize(content, 1280, 720)

        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertEquals(first.pixels, second.pixels)
    }

    @Test
    fun theSizeFollowsTheCanvasHeight() {
        val small = rasterizer.rasterize(TitleContent("Scale", sizeFraction = 0.1), 1280, 720)
        val large = rasterizer.rasterize(TitleContent("Scale", sizeFraction = 0.1), 2560, 1440)

        val ratio = large.height.toDouble() / small.height
        assertTrue("ratio $ratio", ratio in 1.8..2.2)
    }

    @Test
    fun longTextWrapsInsideTheCanvas() {
        val text = "This is a rather long title that cannot fit on a single line of the canvas at this size"
        val bitmap = rasterizer.rasterize(TitleContent(text, sizeFraction = 0.1), 1280, 720)

        assertTrue("width ${bitmap.width}", bitmap.width <= (1280 * 0.9).toInt() + 2 * 20)
        assertTrue("wrapped to several lines: ${bitmap.height}", bitmap.height > 150)
    }

    @Test
    fun alignmentPlacesShortLinesInsideTheBlock() {
        val text = "a very long first line\nx"
        fun firstColumnOfLastLine(alignment: TitleAlignment): Int {
            val bitmap = rasterizer.rasterize(TitleContent(text, sizeFraction = 0.1, alignment = alignment), 1920, 1080)
            val lastLineRows = (bitmap.height * 2 / 3) until bitmap.height
            return opaqueColumns(bitmap, lastLineRows).first() * 100 / bitmap.width
        }

        val left = firstColumnOfLastLine(TitleAlignment.LEFT)
        val center = firstColumnOfLastLine(TitleAlignment.CENTER)
        val right = firstColumnOfLastLine(TitleAlignment.RIGHT)

        assertTrue("left $left center $center right $right", left < center && center < right)
    }

    @Test
    fun anAbsurdlyLargeTitleIsRefusedInsteadOfCrashing() {
        val text = (1..400).joinToString("\n") { "line $it" }

        assertThrows(TitleRasterException::class.java) {
            rasterizer.rasterize(TitleContent(text, sizeFraction = 0.5), 1920, 1080)
        }
    }
}
