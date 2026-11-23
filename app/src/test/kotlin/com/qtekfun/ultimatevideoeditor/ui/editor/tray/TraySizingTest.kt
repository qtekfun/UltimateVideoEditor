package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom tray's size over the window and font-scale matrix: it never takes the room the picture and timeline need. */
class TraySizingTest {
    private val heights = listOf(200f, 320f, 360f, 480f, 568f, 640f, 740f, 891f, 960f, 1200f, 1600f)
    private val scales = listOf(0.85f, 1f, 1.3f, 1.5f, 2f)
    private val widths = listOf(240f, 320f, 360f, 411f, 600f, 840f, 1280f)

    @Test
    fun `the body never exceeds what is left after the picture, the timeline and the editor rows`() {
        for (h in heights) for (s in scales) for (snap in TrayHeight.entries) {
            val body = TraySizing.bodyHeightDp(h, s, snap)
            assertTrue("h=$h s=$s $snap", body >= 0f)
            assertTrue("h=$h s=$s $snap", body <= maxOf(0f, TraySizing.availableBodyDp(h, s)) + 0.001f)
        }
    }

    @Test
    fun `the picture and the timeline keep their minimum with the tray at its tallest`() {
        for (h in heights) for (s in scales) {
            val body = TraySizing.bodyHeightDp(h, s, TrayHeight.FULL)
            val rest = h - body - TraySizing.HEADER_DP * s.coerceIn(1f, 2f) - TraySizing.CHROME_DP * s.coerceIn(1f, 2f)
            if (body > 0f) assertTrue("h=$h s=$s rest=$rest", rest >= TraySizing.MIN_PREVIEW_DP + TraySizing.MIN_TIMELINE_DP - 0.001f)
        }
    }

    @Test
    fun `a body is either absent or usable`() {
        for (h in heights) for (s in scales) for (snap in TrayHeight.entries) {
            val body = TraySizing.bodyHeightDp(h, s, snap)
            assertTrue("h=$h s=$s $snap body=$body", body == 0f || body >= TraySizing.MIN_BODY_DP)
        }
    }

    @Test
    fun `collapsed is always zero`() {
        for (h in heights) for (s in scales) assertEquals(0f, TraySizing.bodyHeightDp(h, s, TrayHeight.COLLAPSED), 0f)
    }

    @Test
    fun `snap heights grow with the snap and with the window`() {
        for (s in scales) {
            var previousHalf = 0f
            var previousFull = 0f
            for (h in heights) {
                val half = TraySizing.bodyHeightDp(h, s, TrayHeight.HALF)
                val full = TraySizing.bodyHeightDp(h, s, TrayHeight.FULL)
                assertTrue("h=$h s=$s half=$half full=$full", full >= half)
                assertTrue("window grows h=$h s=$s", half >= previousHalf && full >= previousFull)
                previousHalf = half
                previousFull = full
            }
        }
    }

    @Test
    fun `larger text never gives a taller tray`() {
        for (h in heights) for (snap in TrayHeight.entries) {
            var previous = Float.MAX_VALUE
            for (s in scales.sorted()) {
                val body = TraySizing.bodyHeightDp(h, s, snap)
                assertTrue("h=$h $snap s=$s", body <= previous + 0.001f)
                previous = body
            }
        }
    }

    @Test
    fun `a short landscape window has no room for a bottom tray`() {
        assertFalse(TraySizing.canExpand(360f, 1f))
        assertFalse(TraySizing.canExpand(320f, 1f))
        assertEquals(0f, TraySizing.bodyHeightDp(360f, 1f, TrayHeight.HALF), 0f)
    }

    @Test
    fun `phones and tablets in portrait get the old sizes or a little less`() {
        // 640 dp phone: a usable half; the old fixed 250 / 420 are the ceiling for these sizes.
        assertTrue(TraySizing.canExpand(891f, 1f))
        assertTrue(TraySizing.bodyHeightDp(891f, 1f, TrayHeight.HALF) in 160f..280f)
        assertTrue(TraySizing.bodyHeightDp(1200f, 1f, TrayHeight.FULL) <= 520f)
    }

    @Test
    fun `an open tray in a window that shrinks still has a way to collapse`() {
        // The header shows its toggle while the tray is open even when the body no longer fits (see MediaTray).
        assertFalse(TraySizing.canExpand(360f, 2f))
        assertEquals(0f, TraySizing.bodyHeightDp(360f, 2f, TrayHeight.FULL), 0f)
    }

    @Test
    fun `tile width grows with the font scale and never exceeds the panel`() {
        for (w in widths) for (s in scales) {
            val tile = TraySizing.tileMinWidthDp(w, s)
            assertTrue("w=$w s=$s", tile <= w)
            assertTrue("w=$w s=$s", tile > 0f)
        }
        assertEquals(96f, TraySizing.tileMinWidthDp(600f, 1f), 0.001f)
        assertTrue(TraySizing.tileMinWidthDp(600f, 1.5f) > TraySizing.tileMinWidthDp(600f, 1f))
        assertEquals(96f * 1.6f, TraySizing.tileMinWidthDp(600f, 2f), 0.001f)
    }
}
