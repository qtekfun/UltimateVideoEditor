package com.qtekfun.ultimatevideoeditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** A clip drag keeps to one axis: down to another lane without drifting in time, along a lane without changing it. */
class DragAxisLockTest {
    private fun lock() = DragAxisLock(decidePx = 14f, releasePx = 48f).also { it.begin(100f, 200f) }

    @Test
    fun `until the direction is clear the clip stays where it was picked up`() {
        val l = lock()
        assertEquals(DragAxisLock.Point(100f, 200f), l.apply(106f, 195f))
        assertEquals(DragAxisLock.Axis.UNDECIDED, l.axis)
    }

    @Test
    fun `a drag mainly downward changes lane and keeps the position in time, however the finger drifts sideways`() {
        val l = lock()
        assertEquals(DragAxisLock.Point(100f, 240f), l.apply(106f, 240f)) // 40 down, 6 across
        assertEquals(DragAxisLock.Axis.VERTICAL, l.axis)
        assertEquals(DragAxisLock.Point(100f, 300f), l.apply(120f, 300f)) // still 20 across: under the release distance
        assertEquals(DragAxisLock.Point(100f, 180f), l.apply(80f, 180f)) // and upward too
    }

    @Test
    fun `a drag mainly along the lane moves in time and cannot tip into another lane`() {
        val l = lock()
        assertEquals(DragAxisLock.Point(160f, 200f), l.apply(160f, 212f))
        assertEquals(DragAxisLock.Axis.HORIZONTAL, l.axis)
        assertEquals(DragAxisLock.Point(40f, 200f), l.apply(40f, 230f)) // leftwards, 30 down: still the same lane
    }

    @Test
    fun `a diagonal drag is not locked`() {
        val l = lock()
        assertEquals(DragAxisLock.Point(150f, 250f), l.apply(150f, 250f))
        assertEquals(DragAxisLock.Axis.FREE, l.axis)
        assertEquals(DragAxisLock.Point(300f, 205f), l.apply(300f, 205f))
    }

    @Test
    fun `a lock lets go for good once the finger is far along the other axis`() {
        val v = lock()
        v.apply(100f, 260f) // vertical lock
        assertEquals(DragAxisLock.Point(100f, 300f), v.apply(148f, 300f)) // exactly the release distance across: still locked
        assertEquals(DragAxisLock.Point(150f, 300f), v.apply(150f, 300f)) // beyond it: the clip follows on both axes
        assertEquals(DragAxisLock.Axis.FREE, v.axis)
        assertEquals(DragAxisLock.Point(110f, 310f), v.apply(110f, 310f)) // and stays free when the finger comes back

        val h = lock()
        h.apply(200f, 202f) // horizontal lock
        assertEquals(DragAxisLock.Point(260f, 200f), h.apply(260f, 248f))
        assertEquals(DragAxisLock.Point(260f, 260f), h.apply(260f, 260f))
        assertEquals(DragAxisLock.Axis.FREE, h.axis)
    }

    @Test
    fun `a new drag starts undecided at its own anchor`() {
        val l = lock()
        l.apply(100f, 300f)
        assertEquals(DragAxisLock.Axis.VERTICAL, l.axis)

        l.begin(500f, 400f)

        assertEquals(DragAxisLock.Axis.UNDECIDED, l.axis)
        assertEquals(DragAxisLock.Point(500f, 400f), l.apply(503f, 404f))
        assertEquals(DragAxisLock.Point(540f, 400f), l.apply(540f, 410f))
        assertEquals(DragAxisLock.Axis.HORIZONTAL, l.axis)
    }

    @Test
    fun `moving an audio clip to another lane keeps its frames, the case that drifted before`() {
        // The finger goes down 20 px across and 90 px down, slowly curving: every position keeps the grabbed x.
        val l = lock()
        val path = listOf(0f to 8f, 3f to 20f, 6f to 45f, 12f to 70f, 20f to 90f)
        for ((dx, dy) in path) {
            val p = l.apply(100f + dx, 200f + dy)
            assertEquals("x at ($dx, $dy)", 100f, p.x, 0f)
        }
    }
}
