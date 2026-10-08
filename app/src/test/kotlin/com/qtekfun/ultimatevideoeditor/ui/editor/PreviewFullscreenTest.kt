package com.qtekfun.ultimatevideoeditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFullscreenTest {
    private val off = FullscreenState()

    @Test
    fun `a double tap enters with the overlay up and a second one leaves`() {
        val on = off.reduce(FullscreenAction.DoubleTap)
        assertTrue(on.active)
        assertTrue(on.overlayVisible)
        val back = on.reduce(FullscreenAction.DoubleTap)
        assertFalse(back.active)
        assertFalse(back.overlayVisible)
    }

    @Test
    fun `back leaves fullscreen first and is not consumed otherwise`() {
        val on = off.reduce(FullscreenAction.DoubleTap)
        assertTrue(on.consumesBack)
        assertFalse(on.reduce(FullscreenAction.Exit).active)
        assertFalse(off.consumesBack)
        assertEquals(off, off.reduce(FullscreenAction.Exit))
    }

    @Test
    fun `a single tap does nothing outside fullscreen`() {
        assertEquals(off, off.reduce(FullscreenAction.Tap))
        assertEquals(off, off.reduce(FullscreenAction.Interact))
    }

    @Test
    fun `a single tap toggles the overlay`() {
        val on = off.reduce(FullscreenAction.DoubleTap)
        val hidden = on.reduce(FullscreenAction.Tap)
        assertFalse(hidden.overlayVisible)
        val shown = hidden.reduce(FullscreenAction.Tap)
        assertTrue(shown.overlayVisible)
        assertTrue(shown.overlayEpoch > on.overlayEpoch)
    }

    @Test
    fun `the timer hides the overlay only for the showing it was armed for`() {
        val on = off.reduce(FullscreenAction.DoubleTap)
        val staleEpoch = on.overlayEpoch
        val touched = on.reduce(FullscreenAction.Interact)
        assertTrue(touched.reduce(FullscreenAction.Timeout(staleEpoch)).overlayVisible)
        assertFalse(touched.reduce(FullscreenAction.Timeout(touched.overlayEpoch)).overlayVisible)
    }

    @Test
    fun `a late timeout after leaving changes nothing`() {
        val on = off.reduce(FullscreenAction.DoubleTap)
        val left = on.reduce(FullscreenAction.Exit)
        assertEquals(left, left.reduce(FullscreenAction.Timeout(on.overlayEpoch)))
    }

    @Test
    fun `re-entering never reuses an epoch`() {
        val first = off.reduce(FullscreenAction.DoubleTap)
        val again = first.reduce(FullscreenAction.Exit).reduce(FullscreenAction.DoubleTap)
        assertTrue(again.overlayEpoch > first.overlayEpoch)
    }

    @Test
    fun `two quick close taps are a double tap`() {
        val tracker = DoubleTapTracker(maxGapMs = 300, maxDistancePx = 100f)
        assertFalse(tracker.tap(downMs = 0, upMs = 50, x = 10f, y = 10f))
        assertTrue(tracker.continuesTap(downMs = 200, x = 20f, y = 15f))
        assertTrue(tracker.tap(downMs = 200, upMs = 250, x = 20f, y = 15f))
    }

    @Test
    fun `slow or distant second taps are not`() {
        val tracker = DoubleTapTracker(300, 100f)
        tracker.tap(0, 50, 0f, 0f)
        assertFalse(tracker.continuesTap(downMs = 351, x = 0f, y = 0f))
        assertFalse(tracker.continuesTap(downMs = 100, x = 300f, y = 0f))
    }

    @Test
    fun `a third tap after a double tap starts a new pair`() {
        val tracker = DoubleTapTracker(300, 100f)
        tracker.tap(0, 50, 0f, 0f)
        assertTrue(tracker.tap(100, 150, 0f, 0f))
        assertFalse(tracker.tap(200, 250, 0f, 0f))
    }

    @Test
    fun `a reset forgets the first tap`() {
        val tracker = DoubleTapTracker(300, 100f)
        tracker.tap(0, 50, 0f, 0f)
        tracker.reset()
        assertFalse(tracker.tap(100, 150, 0f, 0f))
    }
}
