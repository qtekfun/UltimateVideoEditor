package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.CurvePoint
import com.qtekfun.ultimatevideoeditor.domain.GradeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ColorGradeEditTest {

    private fun curve(vararg points: Pair<Double, Double>) = GradeCurve(points.map { CurvePoint(it.first, it.second) })

    // --- wheels ----------------------------------------------------------------------------

    @Test
    fun `the centre of a wheel is neutral`() {
        val (r, g, b) = WheelMath.toRgb(0.0, 0.0)
        assertEquals(0.0, r, 1e-12)
        assertEquals(0.0, g, 1e-12)
        assertEquals(0.0, b, 1e-12)
    }

    @Test
    fun `a touch near the puck grabs it and a touch far from it leaves the swipe to the panel`() {
        // A 200 px wheel with the puck at its centre: the middle grabs, a point near the rim does not.
        assertTrue(WheelMath.grabs(100f, 100f, 200f, 0.0, 0.0))
        assertTrue(WheelMath.grabs(120f, 100f, 200f, 0.0, 0.0))
        assertTrue(!WheelMath.grabs(100f, 190f, 200f, 0.0, 0.0))
        // The puck moved to the right rim: now the rim grabs and the middle does not.
        assertTrue(WheelMath.grabs(190f, 100f, 200f, 0.9, 0.0))
        assertTrue(!WheelMath.grabs(100f, 100f, 200f, 0.9, 0.0))
    }

    @Test
    fun `pushing towards a colour raises it and lowers the others`() {
        val red = WheelMath.toRgb(1.0, 0.0)
        assertEquals(0.5, red.first, 1e-12)
        assertEquals(-0.25, red.second, 1e-12)
        assertEquals(-0.25, red.third, 1e-12)
        // Green sits 120 degrees clockwise from red: down and to the left on screen.
        val green = WheelMath.toRgb(-0.5, 0.866025403784)
        assertEquals(0.5, green.second, 1e-9)
        assertTrue(green.first < 0.0 && green.third < 0.0)
        // Blue sits at 240 degrees: up and to the left.
        val blue = WheelMath.toRgb(-0.5, -0.866025403784)
        assertEquals(0.5, blue.third, 1e-9)
    }

    @Test
    fun `the channel values of a wheel always sum to zero`() {
        for (x in listOf(-1.0, -0.4, 0.0, 0.3, 0.9)) {
            for (y in listOf(-0.9, -0.2, 0.0, 0.5, 1.0)) {
                val (r, g, b) = WheelMath.toRgb(x, y)
                assertEquals(0.0, r + g + b, 1e-12)
            }
        }
    }

    @Test
    fun `a puck outside the disk is clamped to its edge`() {
        val (x, y) = WheelMath.clampToDisk(3.0, 4.0)
        assertEquals(1.0, hypot(x, y), 1e-12)
        assertEquals(0.6, x, 1e-12)
        val (cx, cy) = WheelMath.clampToDisk(0.3, 0.2)
        assertEquals(0.3, cx, 0.0)
        assertEquals(0.2, cy, 0.0)
        val (r, _, _) = WheelMath.toRgb(5.0, 0.0)
        assertEquals(0.5, r, 1e-12)
    }

    @Test
    fun `the puck position round trips through the channel values`() {
        for ((x, y) in listOf(0.0 to 0.0, 0.5 to 0.2, -0.7 to 0.3, 0.1 to -0.9, -0.4 to -0.4)) {
            val (r, g, b) = WheelMath.toRgb(x, y)
            val (px, py) = WheelMath.toPuck(r, g, b)
            assertEquals(x, px, 1e-9)
            assertEquals(y, py, 1e-9)
        }
    }

    @Test
    fun `a touch maps into the unit disk of the wheel`() {
        val centre = WheelMath.fromTouch(50f, 50f, 100f)
        assertEquals(0.0, centre.first, 1e-12)
        assertEquals(0.0, centre.second, 1e-12)
        val right = WheelMath.fromTouch(100f, 50f, 100f)
        assertEquals(1.0, right.first, 1e-12)
        val corner = WheelMath.fromTouch(100f, 100f, 100f)
        assertEquals(1.0, hypot(corner.first, corner.second), 1e-12)
    }

    // --- curve editing ---------------------------------------------------------------------

    @Test
    fun `adding a point keeps the points sorted and refuses duplicates`() {
        val added = CurveEdit.add(GradeCurve(), 0.5, 0.7)!!
        assertEquals(listOf(0.0, 0.5, 1.0), added.points.map { it.x })
        assertEquals(0.7, added.points[1].y, 0.0)
        assertNull(CurveEdit.add(added, 0.51, 0.2))
        assertNull(CurveEdit.add(added, 0.0, 0.2))
        val more = CurveEdit.add(added, 0.2, 0.1)!!
        assertEquals(listOf(0.0, 0.2, 0.5, 1.0), more.points.map { it.x })
    }

    @Test
    fun `a curve holds at most eight points`() {
        var c = GradeCurve()
        for (i in 1..6) c = CurveEdit.add(c, i / 7.0, 0.5)!!
        assertEquals(8, c.points.size)
        assertNull(CurveEdit.add(c, 0.02, 0.5))
    }

    @Test
    fun `moving a point keeps it between its neighbours and the ends on their columns`() {
        val c = curve(0.0 to 0.0, 0.3 to 0.3, 0.7 to 0.7, 1.0 to 1.0)
        val moved = CurveEdit.move(c, 1, 0.9, 0.4)
        assertEquals(0.7 - CurveEdit.MIN_GAP, moved.points[1].x, 1e-12)
        assertEquals(0.4, moved.points[1].y, 0.0)
        assertNull(moved.problem())
        val low = CurveEdit.move(c, 2, 0.0, 2.0)
        assertEquals(0.3 + CurveEdit.MIN_GAP, low.points[2].x, 1e-12)
        assertEquals(1.0, low.points[2].y, 0.0)
        val end = CurveEdit.move(c, 0, 0.4, 0.25)
        assertEquals(0.0, end.points[0].x, 0.0)
        assertEquals(0.25, end.points[0].y, 0.0)
        val last = CurveEdit.move(c, 3, 0.2, 0.9)
        assertEquals(1.0, last.points[3].x, 0.0)
        assertSame(c, CurveEdit.move(c, 9, 0.5, 0.5))
    }

    @Test
    fun `only interior points can be removed`() {
        val c = curve(0.0 to 0.0, 0.5 to 0.6, 1.0 to 1.0)
        assertEquals(2, CurveEdit.remove(c, 1).points.size)
        assertSame(c, CurveEdit.remove(c, 0))
        assertSame(c, CurveEdit.remove(c, 2))
        assertSame(c, CurveEdit.remove(c, 7))
    }

    @Test
    fun `nearest finds the closest point within the grab radius`() {
        val c = curve(0.0 to 0.0, 0.5 to 0.6, 1.0 to 1.0)
        assertEquals(1, CurveEdit.nearest(c, 0.52, 0.58, 0.08))
        assertEquals(0, CurveEdit.nearest(c, 0.03, 0.02, 0.08))
        assertNull(CurveEdit.nearest(c, 0.3, 0.9, 0.08))
        assertNotNull(CurveEdit.nearest(c, 0.5, 0.7, 0.2))
    }
}
