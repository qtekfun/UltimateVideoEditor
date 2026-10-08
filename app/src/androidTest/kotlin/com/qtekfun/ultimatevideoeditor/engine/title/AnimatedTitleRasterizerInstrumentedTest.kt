package com.qtekfun.ultimatevideoeditor.engine.title

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatevideoeditor.domain.TitleAnimation
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLook
import com.qtekfun.ultimatevideoeditor.domain.TitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Draws the looks of an animated caption on a device. Needs the platform text stack, so it is not a JVM test. */
@RunWith(AndroidJUnit4::class)
class AnimatedTitleRasterizerInstrumentedTest {

    private val rasterizer = AndroidTitleRasterizer()
    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35))
    private val white = 0xFFFFFFFF.toInt()
    private val yellow = 0xFFFFE600.toInt()

    private fun title(animation: TitleAnimation, look: TitleLook = TitleLook.FULL) = TitleContent(
        text = "Say it loud",
        sizeFraction = 0.08,
        colorArgb = white,
        bold = true,
        outline = true,
        words = words,
        animation = animation,
        highlightArgb = yellow,
        look = look,
    )

    private fun opaquePixels(bitmap: TitleBitmap, matches: (Int, Int, Int) -> Boolean = { _, _, _ -> true }): Int {
        var count = 0
        for (i in 0 until bitmap.width * bitmap.height) {
            val a = bitmap.pixels.get(i * 4 + 3).toInt() and 0xFF
            if (a > 200 && matches(bitmap.pixels.get(i * 4).toInt() and 0xFF, bitmap.pixels.get(i * 4 + 1).toInt() and 0xFF, bitmap.pixels.get(i * 4 + 2).toInt() and 0xFF)) count++
        }
        return count
    }

    private fun isYellow(r: Int, g: Int, b: Int) = r > 200 && g > 180 && b < 80

    @Test
    fun hiddenWordsKeepTheBlockTheSameSizeSoTheTextDoesNotMove() {
        val full = rasterizer.rasterize(title(TitleAnimation.POP_IN), 1080, 1920)
        val first = rasterizer.rasterize(title(TitleAnimation.POP_IN, TitleLook(visibleWords = 1, activeWord = 0, activePercent = 100)), 1080, 1920)

        assertEquals(full.width, first.width)
        assertEquals(full.height, first.height)
        assertTrue("fewer opaque pixels with words hidden", opaquePixels(first) < opaquePixels(full))
    }

    @Test
    fun theActiveWordIsDrawnInTheHighlightColour() {
        val plain = rasterizer.rasterize(title(TitleAnimation.KARAOKE), 1080, 1920)
        val lit = rasterizer.rasterize(title(TitleAnimation.KARAOKE, TitleLook(activeWord = 1, activePercent = 100)), 1080, 1920)

        assertEquals(0, opaquePixels(plain, ::isYellow))
        assertTrue("yellow pixels ${opaquePixels(lit, ::isYellow)}", opaquePixels(lit, ::isYellow) > 100)
    }

    @Test
    fun anEnlargedWordGrowsTheMarginButNotTheLayout() {
        val normal = rasterizer.rasterize(title(TitleAnimation.KARAOKE, TitleLook(activeWord = 1, activePercent = 100)), 1080, 1920)
        val big = rasterizer.rasterize(title(TitleAnimation.KARAOKE, TitleLook(activeWord = 1, activePercent = 135)), 1080, 1920)

        assertTrue("margin grows for the pop: ${big.width} vs ${normal.width}", big.width > normal.width && big.height > normal.height)
        assertTrue(opaquePixels(big, ::isYellow) > opaquePixels(normal, ::isYellow))
    }

    @Test
    fun aTypewriterLookShowsOnlyTheLettersReached() {
        val full = rasterizer.rasterize(title(TitleAnimation.TYPEWRITER), 1080, 1920)
        val partial = rasterizer.rasterize(title(TitleAnimation.TYPEWRITER, TitleLook(visibleChars = 3)), 1080, 1920)

        assertEquals(full.width, partial.width)
        assertTrue(opaquePixels(partial) in 1 until opaquePixels(full))
    }

    @Test
    fun wordsThatNoLongerMatchTheTextAreDrawnAsAStaticTitle() {
        val stale = title(TitleAnimation.KARAOKE, TitleLook(activeWord = 1, activePercent = 135)).copy(text = "Something else")
        val plain = stale.copy(look = TitleLook.FULL)

        val a = rasterizer.rasterize(stale, 1080, 1920)
        val b = rasterizer.rasterize(plain, 1080, 1920)

        assertEquals(b.width, a.width)
        assertEquals(opaquePixels(b), opaquePixels(a))
    }
}
