package com.ultimatevideo.uveditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchAxisLockTest {
    private fun lock() = PinchAxisLock(slopPx = 10f).also { it.begin(300f, 100f) }

    @Test
    fun staysUndecidedInsideTheSlop() {
        assertEquals(PinchAxis.UNDECIDED, lock().update(305f, 104f))
    }

    @Test
    fun horizontalSpreadLocksHorizontal() {
        assertEquals(PinchAxis.HORIZONTAL, lock().update(340f, 105f))
    }

    @Test
    fun verticalSpreadLocksVertical() {
        assertEquals(PinchAxis.VERTICAL, lock().update(302f, 140f))
    }

    @Test
    fun aTieGoesToTheTimeAxis() {
        assertEquals(PinchAxis.HORIZONTAL, lock().update(320f, 120f))
    }

    @Test
    fun theChoiceSticksForTheRestOfThePinch() {
        val l = lock()
        l.update(340f, 105f)
        assertEquals(PinchAxis.HORIZONTAL, l.update(340f, 400f))
    }

    @Test
    fun beginResetsTheChoice() {
        val l = lock()
        l.update(340f, 105f)
        l.begin(300f, 100f)
        assertEquals(PinchAxis.UNDECIDED, l.axis)
    }

    @Test
    fun verticalFactorMultipliesHeldFactorAndStep() {
        assertEquals(1.2f * 1.5f, lock().verticalFactor(1.2f, 150f, 100f), 1e-5f)
    }

    @Test
    fun tinySpansLeaveTheLanesAlone() {
        val l = lock()
        assertEquals(1.0f, l.verticalFactor(1f, 50f, 0f), 0f)
        assertEquals(1.0f, l.verticalFactor(1f, 0.2f, 50f), 0f)
    }
}
