package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.SpeedKey
import com.qtekfun.ultimatevideoeditor.domain.SpeedRamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCurveModelTest {

    private val duration = 100L

    @Test
    fun `weight and height map to each other on a log scale`() {
        assertEquals(0f, SpeedCurveModel.weightToFraction(SpeedRamps.MIN_WEIGHT), 1e-6f)
        assertEquals(1f, SpeedCurveModel.weightToFraction(SpeedRamps.MAX_WEIGHT), 1e-6f)
        // Doubling the speed is the same distance up everywhere.
        val d1 = SpeedCurveModel.weightToFraction(1000) - SpeedCurveModel.weightToFraction(500)
        val d2 = SpeedCurveModel.weightToFraction(4000) - SpeedCurveModel.weightToFraction(2000)
        assertEquals(d1, d2, 1e-5f)
        for (w in listOf(100, 250, 500, 1000, 2000, 4000, 8000)) {
            val back = SpeedCurveModel.fractionToWeight(SpeedCurveModel.weightToFraction(w))
            assertTrue("$w came back as $back", kotlin.math.abs(back - w) <= (w / 100).coerceAtLeast(10))
        }
        assertEquals(SpeedRamps.MAX_WEIGHT, SpeedCurveModel.fractionToWeight(5f))
        assertEquals(SpeedRamps.MIN_WEIGHT, SpeedCurveModel.fractionToWeight(-5f))
        assertEquals(0, SpeedCurveModel.fractionToWeight(0.4f) % 10)
    }

    @Test
    fun `frames and widths map to each other and stay inside the clip`() {
        assertEquals(0L, SpeedCurveModel.fractionToFrame(0f, duration))
        assertEquals(99L, SpeedCurveModel.fractionToFrame(1f, duration))
        assertEquals(50L, SpeedCurveModel.fractionToFrame(0.5f, duration))
        assertEquals(99L, SpeedCurveModel.fractionToFrame(9f, duration))
        assertEquals(0L, SpeedCurveModel.fractionToFrame(-2f, duration))
        assertEquals(0.5f, SpeedCurveModel.frameToFraction(50, 101), 1e-6f)
        assertEquals(0f, SpeedCurveModel.frameToFraction(5, 1), 0f)
    }

    @Test
    fun `adding a key keeps the shape of the curve and its position in order`() {
        val keys = listOf(SpeedKey(0, 400), SpeedKey(99, 1600))
        val (added, index) = SpeedCurveModel.add(keys, 50, duration)
        assertEquals(1, index)
        assertEquals(listOf(0L, 50L, 99L), added.map { it.frame })
        assertTrue(kotlin.math.abs(SpeedRamps.weightAt(keys, 50.0) - added[1].weightPermille) <= 1.0)
        // The shape is unchanged by the new key: both curves agree everywhere (within rounding).
        for (f in 0..99) {
            assertEquals(SpeedRamps.weightAt(keys, f.toDouble()), SpeedRamps.weightAt(added, f.toDouble()), 6.0)
        }
        // A key already at that frame is selected, not duplicated.
        assertEquals(added to 1, SpeedCurveModel.add(added, 50, duration))
    }

    @Test
    fun `adding to an empty curve starts a flat curve with a key at each end`() {
        val (curve, index) = SpeedCurveModel.add(emptyList(), 30, duration)
        assertEquals(listOf(0L, 30L, 99L), curve.map { it.frame })
        assertTrue(curve.all { it.weightPermille == 1000 })
        assertEquals(1, index)
        assertEquals(emptyList<SpeedKey>(), SpeedCurveModel.add(emptyList(), 0, 1).first)
    }

    @Test
    fun `a new key inside an eased segment is eased too`() {
        val keys = listOf(SpeedKey(0, 400, true), SpeedKey(99, 1600))
        val added = SpeedCurveModel.add(keys, 50, duration).first
        assertTrue(added[1].smooth)
    }

    @Test
    fun `moving a key never crosses its neighbours or leaves the clip or the weight range`() {
        val keys = listOf(SpeedKey(10, 1000), SpeedKey(50, 1000), SpeedKey(90, 1000))
        assertEquals(listOf(10L, 11L, 90L), SpeedCurveModel.move(keys, 1, 5, 1000, duration).let { moved ->
            // Dragging the middle key left past the first one stops one frame after it.
            listOf(moved[0].frame, moved[1].frame, moved[2].frame)
        })
        assertEquals(89L, SpeedCurveModel.move(keys, 1, 500, 1000, duration)[1].frame)
        assertEquals(0L, SpeedCurveModel.move(keys, 0, -50, 1000, duration)[0].frame)
        assertEquals(99L, SpeedCurveModel.move(keys, 2, 500, 1000, duration)[2].frame)
        assertEquals(SpeedRamps.MAX_WEIGHT, SpeedCurveModel.move(keys, 1, 50, 99999, duration)[1].weightPermille)
        assertEquals(SpeedRamps.MIN_WEIGHT, SpeedCurveModel.move(keys, 1, 50, 1, duration)[1].weightPermille)
        assertEquals(keys, SpeedCurveModel.move(keys, 7, 50, 1000, duration))
        // Moved curves are always valid for the domain.
        assertNull(SpeedRamps.problem(SpeedCurveModel.move(keys, 1, 20, 3000, duration), duration))
    }

    @Test
    fun `removing a key keeps the others and a curve of fewer than two keys disappears`() {
        val keys = listOf(SpeedKey(0, 400), SpeedKey(50, 2000), SpeedKey(99, 600))
        assertEquals(listOf(SpeedKey(0, 400), SpeedKey(99, 600)), SpeedCurveModel.remove(keys, 1))
        assertEquals(emptyList<SpeedKey>(), SpeedCurveModel.remove(SpeedCurveModel.remove(keys, 1), 0))
        assertEquals(keys, SpeedCurveModel.remove(keys, 9))
    }

    @Test
    fun `easing flips for the segment after a key`() {
        val keys = listOf(SpeedKey(0, 400), SpeedKey(99, 600))
        val eased = SpeedCurveModel.toggleSmooth(keys, 0)
        assertTrue(eased[0].smooth)
        assertFalse(eased[1].smooth)
        assertEquals(keys, SpeedCurveModel.toggleSmooth(eased, 0))
    }

    @Test
    fun `the nearest key within the radius is picked and nothing outside it`() {
        val keys = listOf(SpeedKey(0, 1000), SpeedKey(50, 2000), SpeedKey(99, 1000))
        val x = SpeedCurveModel.frameToFraction(50, duration)
        val y = SpeedCurveModel.weightToFraction(2000)
        assertEquals(1, SpeedCurveModel.nearestKey(keys, x + 0.01f, y - 0.01f, duration, 3f, 0.12f))
        assertNull(SpeedCurveModel.nearestKey(keys, 0.25f, 0.9f, duration, 3f, 0.12f))
    }

    @Test
    fun `the drawn polyline starts and ends at the plot edges and follows eased segments`() {
        val keys = listOf(SpeedKey(10, 500, true), SpeedKey(80, 2000))
        val points = SpeedCurveModel.polyline(keys, duration)
        assertEquals(0f, points.first().first, 0f)
        assertEquals(1f, points.last().first, 0f)
        // The weight holds before the first key and after the last.
        assertEquals(SpeedCurveModel.weightToFraction(500), points.first().second, 1e-6f)
        assertEquals(SpeedCurveModel.weightToFraction(2000), points.last().second, 1e-6f)
        // Eased segment: sampled inside, so more points than keys.
        assertTrue(points.size > 6)
        // Horizontal position never goes backwards.
        assertTrue(points.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertEquals(emptyList<Pair<Float, Float>>(), SpeedCurveModel.polyline(emptyList(), duration))
    }

    @Test
    fun `curves equal up to the editor resolution are the same`() {
        val a = listOf(SpeedKey(0, 400), SpeedKey(50, 1000))
        assertTrue(SpeedCurveModel.same(a, a.toList()))
        assertTrue(SpeedCurveModel.same(a, listOf(SpeedKey(0, 404), SpeedKey(50, 1000))))
        assertFalse(SpeedCurveModel.same(a, listOf(SpeedKey(0, 450), SpeedKey(50, 1000))))
        assertFalse(SpeedCurveModel.same(a, a.dropLast(1)))
        assertFalse(SpeedCurveModel.same(a, listOf(SpeedKey(0, 400, true), SpeedKey(50, 1000))))
    }
}
