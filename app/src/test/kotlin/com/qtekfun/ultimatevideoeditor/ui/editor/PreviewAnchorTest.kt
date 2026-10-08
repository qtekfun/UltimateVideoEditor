package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAnchorTest {

    private val fps = FrameRate(30, 1)
    private val second = 1_000_000_000L

    @Test
    fun `nothing is anchored at first so the first call anchors`() {
        val anchor = PreviewAnchor(thresholdFrames = 2)

        assertNull(anchor.expectedFrame(0, fps))
        assertTrue(anchor.needsReanchor("a", heardFrame = 0, nowNanos = 0, fps = fps))
    }

    @Test
    fun `expected frame advances with the monotonic clock`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", frame = 100, nowNanos = 5 * second)

        assertEquals(100L, anchor.expectedFrame(5 * second, fps))
        assertEquals(130L, anchor.expectedFrame(6 * second, fps))
        // Integer math: 1.5 s at 30 fps is exactly 45 frames, 1.49 s floors to 44.
        assertEquals(145L, anchor.expectedFrame(6 * second + second / 2, fps))
        assertEquals(144L, anchor.expectedFrame(6 * second + 490_000_000L, fps))
    }

    @Test
    fun `heard frame close to the expected one keeps the native clock running`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", 100, 0)

        // Expected 130 after one second; 2 frames either way is still within the threshold.
        assertFalse(anchor.needsReanchor("a", 130, second, fps))
        assertFalse(anchor.needsReanchor("a", 132, second, fps))
        assertFalse(anchor.needsReanchor("a", 128, second, fps))
    }

    @Test
    fun `drift beyond the threshold re-anchors`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", 100, 0)

        assertTrue(anchor.needsReanchor("a", 133, second, fps))
        assertTrue(anchor.needsReanchor("a", 127, second, fps))
    }

    @Test
    fun `a changed composition re-anchors even without drift`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor(listOf("clip-1"), 100, 0)

        assertTrue(anchor.needsReanchor(listOf("clip-2"), 130, second, fps))
        assertFalse(anchor.needsReanchor(listOf("clip-1"), 130, second, fps))
    }

    @Test
    fun `a seek while playing is a drift`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", 100, 0)

        assertTrue(anchor.needsReanchor("a", heardFrame = 900, nowNanos = second / 10, fps = fps))
    }

    @Test
    fun `reset forgets the anchor`() {
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", 100, 0)
        anchor.reset()

        assertFalse(anchor.isActive)
        assertTrue(anchor.needsReanchor("a", 100, 0, fps))
    }

    @Test
    fun `fractional rates use the exact rational`() {
        val ntsc = FrameRate(30000, 1001)
        val anchor = PreviewAnchor(2)
        anchor.anchor("a", 0, 0)

        // 1001 s at 30000/1001 is exactly 30000 frames.
        assertEquals(30_000L, anchor.expectedFrame(1001 * second, ntsc))
    }
}
