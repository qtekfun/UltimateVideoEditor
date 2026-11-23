package com.qtekfun.ultimatevideoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sizes of lists, pictures, cards and label columns in sheets and dialogs over the window and font-scale matrix. */
class SheetSizingTest {
    private val heights = listOf(200f, 320f, 360f, 568f, 640f, 891f, 960f, 1200f)
    private val scales = listOf(0.85f, 1f, 1.3f, 1.5f, 2f)

    @Test
    fun `a list never exceeds its preferred height and never drops below the floor`() {
        for (h in heights) for (s in scales) for (preferred in listOf(240f, 260f, 360f, 400f, 420f)) {
            val max = SheetSizing.listMaxHeightDp(h, s, preferred)
            assertTrue("h=$h s=$s", max <= preferred)
            assertTrue("h=$h s=$s", max >= minOf(preferred, SheetSizing.MIN_LIST_DP))
        }
    }

    @Test
    fun `a list leaves the room a dialog spends on its title and buttons when the window allows`() {
        for (h in heights) for (s in scales) {
            val max = SheetSizing.listMaxHeightDp(h, s, 420f)
            val chrome = SheetSizing.DIALOG_CHROME_DP * s.coerceIn(1f, 2f)
            if (max > SheetSizing.MIN_LIST_DP) assertTrue("h=$h s=$s", max + chrome <= h + 0.001f)
        }
    }

    @Test
    fun `a list is shorter in a shorter window and with larger text`() {
        for (s in scales) {
            var previous = 0f
            for (h in heights) {
                val max = SheetSizing.listMaxHeightDp(h, s, 420f)
                assertTrue("h=$h s=$s", max >= previous)
                previous = max
            }
        }
        for (h in heights) {
            var previous = Float.MAX_VALUE
            for (s in scales) {
                val max = SheetSizing.listMaxHeightDp(h, s, 420f)
                assertTrue("h=$h s=$s", max <= previous)
                previous = max
            }
        }
    }

    @Test
    fun `a roomy window gets the preferred height`() {
        assertEquals(420f, SheetSizing.listMaxHeightDp(1200f, 1f, 420f), 0f)
    }

    @Test
    fun `a label column grows with the text up to a cap`() {
        assertEquals(60f, SheetSizing.labelWidthDp(60f, 1f), 0.001f)
        assertEquals(60f, SheetSizing.labelWidthDp(60f, 0.85f), 0.001f)
        assertEquals(78f, SheetSizing.labelWidthDp(60f, 1.3f), 0.001f)
        assertEquals(96f, SheetSizing.labelWidthDp(60f, 2f), 0.001f)
    }

    @Test
    fun `a picture and a floating card stay inside the window`() {
        for (h in heights) {
            val picture = SheetSizing.pictureMaxHeightDp(h)
            assertTrue("h=$h", picture in 96f..360f)
            assertTrue("h=$h", picture <= maxOf(96f, h * 0.4f))
            val card = SheetSizing.popupMaxHeightDp(h)
            assertTrue("h=$h", card >= 120f)
            assertTrue("h=$h", card <= maxOf(120f, h * 0.6f))
        }
    }
}
