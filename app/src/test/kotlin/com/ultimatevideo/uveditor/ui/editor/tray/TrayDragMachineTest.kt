package com.ultimatevideo.uveditor.ui.editor.tray

import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayDragMachineTest {
    private fun machine() = TrayDragMachine(holdMs = 300, slopPx = 10f)

    @Test
    fun `holding still for 300 ms picks the tile up where the finger went down`() {
        val m = machine()
        m.down(1, 100f, 200f, nowMs = 1000)
        assertEquals(TrayDragPhase.ARMED, m.phase)
        assertEquals(300L, m.remainingHoldMs(1000))
        assertEquals(120L, m.remainingHoldMs(1180))
        assertEquals(0L, m.remainingHoldMs(1400))
        assertEquals(TrayDragStep.PickedUp(100f, 200f), m.holdElapsed())
        assertEquals(TrayDragPhase.CARRYING, m.phase)
    }

    @Test
    fun `small jitter during the hold does not cancel it`() {
        val m = machine()
        m.down(1, 100f, 200f, 0)
        assertEquals(TrayDragStep.None, m.move(1, 104f, 203f, 100))
        assertEquals(TrayDragPhase.ARMED, m.phase)
        assertTrue(m.holdElapsed() is TrayDragStep.PickedUp)
    }

    @Test
    fun `moving past the slop before the hold hands the touch back to scrolling`() {
        val m = machine()
        m.down(1, 100f, 200f, 0)
        assertEquals(TrayDragStep.Cancelled(TrayDragCancel.MOVED_BEFORE_HOLD), m.move(1, 100f, 215f, 100))
        assertEquals(TrayDragPhase.IDLE, m.phase)
        // The hold timer of the dead gesture does nothing.
        assertEquals(TrayDragStep.None, m.holdElapsed())
    }

    @Test
    fun `lifting before the hold is a tap`() {
        val m = machine()
        m.down(1, 100f, 200f, 0)
        assertEquals(TrayDragStep.Tapped, m.up(1, 100f, 200f))
        assertEquals(TrayDragPhase.IDLE, m.phase)
    }

    @Test
    fun `a move that arrives after the hold time picks the tile up too`() {
        val m = machine()
        m.down(1, 100f, 200f, 0)
        assertEquals(TrayDragStep.PickedUp(103f, 200f), m.move(1, 103f, 200f, 350))
    }

    @Test
    fun `once carried every move is reported, however far from the tile, and the lift drops there`() {
        val m = machine()
        m.down(1, 100f, 200f, 0)
        m.holdElapsed()
        assertEquals(TrayDragStep.Moved(900f, -50f), m.move(1, 900f, -50f, 500))
        assertEquals(TrayDragStep.Dropped(640f, 310f), m.up(1, 640f, 310f))
        assertEquals(TrayDragPhase.IDLE, m.phase)
    }

    @Test
    fun `a second finger cancels while armed or carrying`() {
        val armed = machine()
        armed.down(1, 0f, 0f, 0)
        assertEquals(TrayDragStep.Cancelled(TrayDragCancel.SECOND_POINTER), armed.secondPointer())
        assertEquals(TrayDragPhase.IDLE, armed.phase)

        val carrying = machine()
        carrying.down(1, 0f, 0f, 0)
        carrying.holdElapsed()
        assertEquals(TrayDragStep.Cancelled(TrayDragCancel.SECOND_POINTER), carrying.secondPointer())
        // The finger that is left lifting later drops nothing.
        assertEquals(TrayDragStep.None, carrying.up(1, 5f, 5f))
    }

    @Test
    fun `back or a system cancel interrupts a carried tile and an idle machine ignores it`() {
        val m = machine()
        assertEquals(TrayDragStep.None, m.interrupt())
        m.down(1, 0f, 0f, 0)
        m.holdElapsed()
        assertEquals(TrayDragStep.Cancelled(TrayDragCancel.INTERRUPTED), m.interrupt())
        assertEquals(TrayDragPhase.IDLE, m.phase)
    }

    @Test
    fun `events of another pointer are ignored and a second down while busy is ignored`() {
        val m = machine()
        m.down(1, 0f, 0f, 0)
        assertEquals(TrayDragStep.None, m.move(2, 500f, 500f, 100))
        assertEquals(TrayDragStep.None, m.up(2, 500f, 500f))
        assertEquals(TrayDragPhase.ARMED, m.phase)
        m.down(2, 40f, 40f, 50)
        m.holdElapsed()
        assertEquals(TrayDragStep.PickedUp(0f, 0f).javaClass, TrayDragStep.PickedUp(1f, 1f).javaClass)
        assertEquals(TrayDragStep.Dropped(1f, 1f), m.up(1, 1f, 1f))
    }

    // region where the finger is

    private val bounds = RootBounds(left = 200f, top = 600f, right = 1000f, bottom = 1000f)

    @Test
    fun `root coordinates become timeline view pixels, and outside the view is no point`() {
        assertEquals(ViewPoint(50f, 100f), toTimelinePoint(250f, 700f, bounds))
        assertEquals(ViewPoint(0f, 0f), toTimelinePoint(200f, 600f, bounds))
        assertNull(toTimelinePoint(199f, 700f, bounds))
        assertNull(toTimelinePoint(500f, 599f, bounds))
        assertNull(toTimelinePoint(1000f, 700f, bounds))
        assertNull(toTimelinePoint(500f, 1000f, bounds))
        assertNull(toTimelinePoint(500f, 700f, null))
    }

    /** A stand-in for the native hit-test: lanes of [laneHeight] pixels starting [rulerHeight] down, scrolled by [scrollY], frames at [pxPerFrame]. */
    private fun fakeTimeline(laneHeight: Float, scrollY: Float, scrollX: Float, pxPerFrame: Float, lanes: Int) = { x: Float, y: Float ->
        val rulerHeight = 40f
        val lane = ((y - rulerHeight + scrollY) / laneHeight).toInt()
        val kind = when {
            y < rulerHeight -> HitKind.RULER
            lane in 0 until lanes -> HitKind.EMPTY_TRACK
            else -> HitKind.NONE
        }
        TimelineHit(kind, if (kind == HitKind.EMPTY_TRACK) lane else -1, 0L, ((x + scrollX) / pxPerFrame).toLong())
    }

    @Test
    fun `the lane under the finger honours the lane height and the vertical scroll`() {
        val tall = fakeTimeline(laneHeight = 100f, scrollY = 0f, scrollX = 0f, pxPerFrame = 2f, lanes = 5)
        // Finger at root y 790 is 190 px into the view: 150 px into the lanes: lane 1.
        assertEquals(1, sampleTimeline(300f, 790f, bounds, tall)?.hit?.trackIndex)
        // The same finger after the lanes scrolled by 120 px is lane 2; with the lanes half as tall, lane 3.
        val scrolled = fakeTimeline(laneHeight = 100f, scrollY = 120f, scrollX = 0f, pxPerFrame = 2f, lanes = 5)
        assertEquals(2, sampleTimeline(300f, 790f, bounds, scrolled)?.hit?.trackIndex)
        val small = fakeTimeline(laneHeight = 50f, scrollY = 0f, scrollX = 0f, pxPerFrame = 2f, lanes = 5)
        assertEquals(3, sampleTimeline(300f, 790f, bounds, small)?.hit?.trackIndex)
    }

    @Test
    fun `the frame under the finger honours the zoom and the horizontal scroll, relative to the view and not the screen`() {
        val timeline = fakeTimeline(laneHeight = 100f, scrollY = 0f, scrollX = 400f, pxPerFrame = 4f, lanes = 3)
        // Root x 300 is 100 px into the view; plus 400 scrolled is 500 px; at 4 px per frame, frame 125.
        assertEquals(125L, sampleTimeline(300f, 700f, bounds, timeline)?.hit?.frame)
    }

    @Test
    fun `a finger on the ruler is a hit on the ruler and off the view is null`() {
        val timeline = fakeTimeline(100f, 0f, 0f, 2f, 3)
        assertEquals(HitKind.RULER, sampleTimeline(300f, 620f, bounds, timeline)?.hit?.kind)
        assertNull(sampleTimeline(300f, 300f, bounds, timeline))
    }

    // endregion

    // region auto-scroll

    private val density = 2f
    private val width = 1600f
    private val height = 800f

    @Test
    fun `no auto-scroll in the middle of the timeline`() {
        val s = TrayAutoScroll(dwellMs = 0)
        assertEquals(0f to 0f, s.step(ViewPoint(800f, 400f), width, height, density, 0))
    }

    @Test
    fun `scrolls back near the left and top edges and forward near the right and bottom`() {
        val s = TrayAutoScroll(dwellMs = 0)
        val left = s.step(ViewPoint(10f, 400f), width, height, density, 0)
        assertTrue(left.first < 0f)
        assertEquals(0f, left.second, 0f)
        val right = s.step(ViewPoint(1590f, 400f), width, height, density, 1)
        assertTrue(right.first > 0f)
        val top = s.step(ViewPoint(800f, 5f), width, height, density, 2)
        assertTrue(top.second < 0f)
        assertEquals(0f, top.first, 0f)
        val bottom = s.step(ViewPoint(800f, 795f), width, height, density, 3)
        assertTrue(bottom.second > 0f)
    }

    @Test
    fun `speed grows towards the edge and is capped`() {
        val near = edgeScroll(1500f, 1600f, density, 56f, 14f)
        val at = edgeScroll(1600f, 1600f, density, 56f, 14f)
        val beyond = edgeScroll(2000f, 1600f, density, 56f, 14f)
        assertTrue(near > 0f && at > near)
        assertEquals(14f * density, at, 0.001f)
        assertEquals(at, beyond, 0f)
    }

    @Test
    fun `an axis too short for two zones never scrolls`() {
        assertEquals(0f, edgeScroll(5f, 100f, density, 36f, 10f), 0f)
    }

    @Test
    fun `an edge only scrolls after the finger dwelt in its zone, and leaving the zone starts the wait again`() {
        val s = TrayAutoScroll(dwellMs = 250)
        val inZone = ViewPoint(5f, 400f)
        assertEquals(0f, s.step(inZone, width, height, density, 1000).first, 0f)
        assertEquals(0f, s.step(inZone, width, height, density, 1200).first, 0f)
        assertTrue(s.step(inZone, width, height, density, 1260).first < 0f)
        // Out of the zone and back in: the wait restarts.
        s.step(ViewPoint(800f, 400f), width, height, density, 1300)
        assertEquals(0f, s.step(inZone, width, height, density, 1310).first, 0f)
        assertTrue(s.step(inZone, width, height, density, 1600).first < 0f)
    }

    @Test
    fun `the axes dwell separately`() {
        val s = TrayAutoScroll(dwellMs = 250)
        s.step(ViewPoint(800f, 5f), width, height, density, 0)
        // Horizontal edge entered later: only the vertical axis is ready at 260.
        val r = s.step(ViewPoint(5f, 5f), width, height, density, 260)
        assertEquals(0f, r.first, 0f)
        assertTrue(r.second < 0f)
    }

    // endregion
}
