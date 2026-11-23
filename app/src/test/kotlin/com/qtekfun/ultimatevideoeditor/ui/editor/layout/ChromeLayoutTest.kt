package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.ui.editor.formatTimecode
import com.qtekfun.ultimatevideoeditor.ui.editor.formatTimecodeCompact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** (width, height) in dp of the windows the editor has to survive, in the shapes the owner listed. */
private val WINDOWS = listOf(
    320f to 568f, 360f to 640f, 411f to 891f, // phones upright
    640f to 360f, 740f to 360f, // phone sideways
    600f to 960f, 840f to 1200f, 1280f to 800f, // tablets
    700f to 900f, 800f to 740f, // foldable postures
    400f to 300f, 360f to 300f, // split screen and a landscape split
)
private val FONT_SCALES = listOf(1f, 1.3f, 2f)
private const val MIN_WIDTH_FOR_PLAN = 320f

class TopBarLayoutTest {
    private val all = TopBarAction.entries

    @Test
    fun `every action is inline or in the menu, once, in drawing order`() {
        for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            if (w < MIN_WIDTH_FOR_PLAN) continue
            val plan = resolveTopBar(w, fs)
            assertEquals("$w x $fs", all.toSet(), (plan.inline + plan.overflow).toSet())
            assertEquals(all.size, plan.inline.size + plan.overflow.size)
            assertEquals(plan.inline, all.filter { it in plan.inline })
            assertEquals(plan.overflow, all.filter { it in plan.overflow })
        }
    }

    @Test
    fun `back undo and export never leave the bar`() {
        for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTopBar(w, fs)
            assertTrue("$w x $fs", plan.inline.containsAll(listOf(TopBarAction.BACK, TopBarAction.UNDO, TopBarAction.EXPORT)))
        }
    }

    @Test
    fun `the buttons and the name fit the width`() {
        for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTopBar(w, fs)
            val buttons = plan.inline.size + if (plan.hasOverflow) 1 else 0
            val used = buttons * CHROME_BUTTON_DP + TOP_BAR_PADDING_DP + TOP_BAR_NAME_MIN_DP * maxOf(fs, 1f)
            assertTrue("$w x $fs uses $used", used <= w)
        }
    }

    @Test
    fun `actions leave in priority order help then layout then redo`() {
        val rank = TOP_BAR_PRIORITY.withIndex().associate { it.value to it.index }
        for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTopBar(w, fs)
            val worstKept = plan.inline.maxOf { rank.getValue(it) }
            val bestLeft = plan.overflow.minOfOrNull { rank.getValue(it) } ?: Int.MAX_VALUE
            assertTrue("$w x $fs", worstKept < bestLeft)
        }
        assertEquals(listOf(TopBarAction.HELP, TopBarAction.LAYOUT), resolveTopBar(320f, 2f).overflow)
    }

    @Test
    fun `a roomy window shows everything and a narrow one needs the menu`() {
        assertFalse(resolveTopBar(1280f, 1f).hasOverflow)
        assertFalse(resolveTopBar(411f, 1f).hasOverflow)
        assertFalse(resolveTopBar(320f, 1f).hasOverflow)
        assertTrue(resolveTopBar(320f, 1.3f).hasOverflow)
        assertTrue(resolveTopBar(320f, 2f).hasOverflow)
        assertFalse(resolveTopBar(1280f, 2f).hasOverflow)
    }

    @Test
    fun `a window too narrow for anything still keeps the three essentials`() {
        val plan = resolveTopBar(200f, 2f)
        assertEquals(listOf(TopBarAction.BACK, TopBarAction.UNDO, TopBarAction.EXPORT), plan.inline)
        assertEquals(3, plan.overflow.size)
    }
}

class TransportLayoutTest {
    private val everything = TransportAction.entries
    private val withoutZoom = listOf(TransportAction.SAVE_FRAME, TransportAction.FIT)

    @Test
    fun `inline and menu hold exactly the existing actions`() {
        for (actions in listOf(everything, withoutZoom)) for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTransport(w, fs, actions)
            assertEquals("$w x $fs", actions.toSet(), (plan.inline + plan.overflow).toSet())
            assertEquals(actions.size, plan.inline.size + plan.overflow.size)
        }
    }

    @Test
    fun `nothing is wider than its room`() {
        for (actions in listOf(everything, withoutZoom)) for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTransport(w, fs, actions)
            val width = w - TRANSPORT_PADDING_DP
            val tc = timecodeWidthDp(plan.timecode, fs)
            val trailing = (plan.inline.size + if (plan.overflow.isNotEmpty()) 1 else 0) * CHROME_BUTTON_DP
            val message = "$w x $fs $plan"
            if (plan.stacked) {
                assertTrue(message, tc + trailing + 8f <= width)
                assertTrue(message, 3 * CHROME_BUTTON_DP <= width)
            } else {
                val side = (width - 3 * CHROME_BUTTON_DP) / 2f
                assertTrue(message, tc <= side)
                assertTrue(message, trailing <= side)
            }
        }
    }

    @Test
    fun `a roomy window keeps the one line, the full timecode and every action`() {
        for (fs in FONT_SCALES) {
            val plan = resolveTransport(1280f, fs, everything)
            assertEquals(TransportPlan(TimecodeForm.FULL, false, everything, emptyList()), plan)
        }
        assertEquals(TransportPlan(TimecodeForm.FULL, false, everything, emptyList()), resolveTransport(600f, 1f, everything))
    }

    @Test
    fun `fit is the last action to leave and frame save the first`() {
        val rank = listOf(TransportAction.FIT, TransportAction.ZOOM_DETAIL, TransportAction.SAVE_FRAME).withIndex().associate { it.value to it.index }
        for ((w, _) in WINDOWS) for (fs in FONT_SCALES) {
            val plan = resolveTransport(w, fs, everything)
            val worstKept = plan.inline.maxOfOrNull { rank.getValue(it) } ?: -1
            val bestLeft = plan.overflow.minOfOrNull { rank.getValue(it) } ?: Int.MAX_VALUE
            assertTrue("$w x $fs", worstKept < bestLeft)
        }
        val narrow = resolveTransport(320f, 1f, everything)
        assertEquals(listOf(TransportAction.FIT), narrow.inline)
        assertEquals(listOf(TransportAction.SAVE_FRAME, TransportAction.ZOOM_DETAIL), narrow.overflow)
    }

    @Test
    fun `the timecode turns compact before the row splits, and the row splits before anything is clipped`() {
        val narrow = resolveTransport(320f, 1f, everything)
        assertEquals(TimecodeForm.COMPACT, narrow.timecode)
        assertFalse(narrow.stacked)
        val big = resolveTransport(320f, 2f, everything)
        assertTrue(big.stacked)
    }

    @Test
    fun `an action that does not exist is never planned`() {
        val plan = resolveTransport(320f, 2f, withoutZoom)
        assertFalse(TransportAction.ZOOM_DETAIL in plan.inline + plan.overflow)
        val none = resolveTransport(320f, 2f, emptyList())
        assertTrue(none.inline.isEmpty() && none.overflow.isEmpty())
    }

    @Test
    fun `the timecode readout follows the font only up to its cap`() {
        assertEquals(timecodeWidthDp(TimecodeForm.FULL, 1.3f), timecodeWidthDp(TimecodeForm.FULL, 2f), 0.001f)
        assertTrue(timecodeWidthDp(TimecodeForm.FULL, 1f) < timecodeWidthDp(TimecodeForm.FULL, 1.3f))
        assertTrue(timecodeWidthDp(TimecodeForm.COMPACT, 1f) < timecodeWidthDp(TimecodeForm.FULL, 1f))
    }

    @Test
    fun `the compact timecode folds hours into minutes and is never longer than nine characters`() {
        val fps = FrameRate(30, 1)
        assertEquals("00:00:00", formatTimecodeCompact(0, fps))
        assertEquals("01:02:03", formatTimecodeCompact((62L * 30) + 3, fps))
        assertEquals("65:00:10", formatTimecodeCompact((65L * 60 * 30) + 10, fps))
        assertEquals("01:05:00:10", formatTimecode((65L * 60 * 30) + 10, fps))
        assertEquals("120:00:00", formatTimecodeCompact(120L * 60 * 30, fps))
        assertTrue(formatTimecodeCompact(99L * 60 * 60 * 30, fps).length <= 11) // rare: a 99 hour project
        assertEquals("00:01:00", formatTimecodeCompact(24, FrameRate(24000, 1001)))
    }
}

class VerticalBudgetTest {
    private val handle = 18
    private val minPreview = MIN_PREVIEW_DP.toInt()
    private val minTimeline = MIN_TIMELINE_DP.toInt()
    private val minChrome = MIN_CHROME_DP.toInt()

    private fun heights(total: Int, wanted: Int, fraction: Float): Pair<Int, StackedHeights> {
        val cap = chromeMaxHeight(total, handle, minPreview, minTimeline, minChrome)
        val measured = minOf(wanted, cap) // a scrolling column is as tall as its content, up to the cap
        return cap to resolveStacked(total, handle, measured, fraction, minPreview, minTimeline)
    }

    @Test
    fun `the three parts and the divider add up to the block`() {
        for ((_, h) in WINDOWS) for (wanted in listOf(60, 130, 220, 500)) for (fraction in listOf(0.15f, 0.4f, 0.75f)) {
            val total = h.toInt() - 48 // the top bar is above the block
            val (_, r) = heights(total, wanted, fraction)
            assertEquals("$h $wanted $fraction", (total - handle).coerceAtLeast(0), r.chrome + r.preview + r.timeline)
            assertTrue(r.chrome >= 0 && r.preview >= 0 && r.timeline >= 0)
        }
    }

    @Test
    fun `preview and timeline keep their minimum heights whenever the window can hold them`() {
        for ((_, h) in WINDOWS) for (wanted in listOf(60, 130, 220, 500)) for (fraction in listOf(0.15f, 0.4f, 0.75f)) {
            val total = h.toInt() - 48
            val (_, r) = heights(total, wanted, fraction)
            if (total - handle - minChrome >= minPreview + minTimeline) {
                assertTrue("$h $wanted $fraction $r", r.preview >= minPreview)
                assertTrue("$h $wanted $fraction $r", r.timeline >= minTimeline)
            }
        }
    }

    @Test
    fun `controls taller than their room are capped, keeping at least one row`() {
        val (cap, r) = heights(total = 300, wanted = 500, fraction = 0.4f)
        assertEquals(300 - handle - minPreview - minTimeline, cap)
        assertEquals(cap, r.chrome)
        assertEquals(minPreview, r.preview)
        assertEquals(minTimeline, r.timeline)
        val (smallCap, small) = heights(total = 200, wanted = 500, fraction = 0.4f)
        assertEquals(minChrome, smallCap)
        assertEquals(200 - handle, small.chrome + small.preview + small.timeline)
        assertTrue(small.preview > 0 && small.timeline > 0)
    }

    @Test
    fun `short controls leave the divider where the person put it`() {
        val (_, r) = heights(total = 800, wanted = 100, fraction = 0.4f)
        assertEquals(100, r.chrome)
        assertEquals(((800 - handle - 100) * 0.4f).roundToInt(), r.preview)
    }

    @Test
    fun `a block shorter than the divider gives nothing negative`() {
        val r = resolveStacked(10, handle, 40, 0.5f, minPreview, minTimeline)
        assertEquals(StackedHeights(0, 0, 0), r)
        assertEquals(0, chromeMaxHeight(10, handle, minPreview, minTimeline, minChrome))
    }

    @Test
    fun `the fraction is honoured between the minimums`() {
        val high = resolveStacked(1000, handle, 100, 0.75f, minPreview, minTimeline)
        val low = resolveStacked(1000, handle, 100, 0.15f, minPreview, minTimeline)
        assertTrue(high.preview > low.preview)
        assertEquals(minPreview, resolveStacked(300, handle, 100, 0.01f, minPreview, minTimeline).preview)
    }

    @Test
    fun `side by side loses no pixel and keeps both minimums when it can`() {
        for ((w, _) in WINDOWS) for (fraction in listOf(0.25f, 0.4f, 0.7f)) {
            val room = w.toInt() - handle
            val s = resolveSideSplit(room, fraction, MIN_SIDE_PREVIEW_DP.toInt(), MIN_SIDE_REST_DP.toInt())
            assertEquals("$w $fraction", room, s.preview + s.rest)
            if (room >= MIN_SIDE_PREVIEW_DP + MIN_SIDE_REST_DP) {
                assertTrue("$w $fraction $s", s.preview >= MIN_SIDE_PREVIEW_DP && s.rest >= MIN_SIDE_REST_DP)
            }
        }
        val tiny = resolveSideSplit(200, 0.4f, 96, 240)
        assertEquals(200, tiny.preview + tiny.rest)
        assertTrue(tiny.preview > 0 && tiny.rest > 0)
        assertEquals(SidePx(0, 0), resolveSideSplit(-5, 0.4f, 96, 240))
    }
}

class OverlayChipsTest {
    @Test
    fun `chips sit side by side only when both fit and stack otherwise`() {
        assertFalse(overlayChipLayout(1280f, 400f, 1f).stacked)
        assertTrue(overlayChipLayout(411f, 400f, 1f).stacked)
        assertTrue(overlayChipLayout(640f, 400f, 1.3f).stacked)
        assertFalse(overlayChipLayout(800f, 400f, 1f).stacked)
    }

    @Test
    fun `a short timeline puts them over the ruler instead of cutting them`() {
        assertEquals(40f, overlayChipLayout(411f, 300f, 1f).topDp, 0f)
        assertEquals(4f, overlayChipLayout(411f, 96f, 1f).topDp, 0f)
    }
}

class DockInTinyWindowsTest {
    @Test
    fun `columns never go negative however small the window`() {
        for (width in listOf(0, 40, 100, 200, 320, 480)) {
            val cols = resolveColumns(width, 320, 320, leftOpen = true, rightOpen = true, handlePx = 18, fullscreen = false)
            assertTrue("$width $cols", cols.left >= 0 && cols.right >= 0 && cols.main >= 0)
            assertTrue("$width $cols", cols.left + cols.right + cols.leftHandle + cols.rightHandle <= maxOf(width, cols.leftHandle + cols.rightHandle))
        }
    }

    @Test
    fun `collapsed strips never take more than the window`() {
        val cols = resolveColumns(60, 40, 40, leftOpen = false, rightOpen = false, handlePx = 18, fullscreen = false)
        assertTrue(cols.main >= 0)
    }
}
