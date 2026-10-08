package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveTest {
    private val base = timeline(
        track("v1", clip("a", 0, 100), clip("b", 150, 50)),
        track("v2", clip("c", 300, 40)),
        track("au", clip("m", 0, 10), type = TrackType.AUDIO),
    )

    @Test
    fun `move into a gap`() {
        assertLayout(TimelineOps.move(base, "a", f(40)).getOrFail(), "v1", at("a", 40, 140), at("b", 150, 200))
    }

    @Test
    fun `move exactly adjacent to a neighbour is not an overlap`() {
        val result = TimelineOps.move(base, "b", f(100)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 150))
    }

    @Test
    fun `move one frame into a neighbour fails with the blocking clip`() {
        assertEquals(EditError.Overlap("a"), TimelineOps.move(base, "b", f(99)).errorOrFail())
        assertEquals(EditError.Overlap("b"), TimelineOps.move(base, "a", f(51)).errorOrFail())
    }

    @Test
    fun `move to its own position or partly over itself is fine`() {
        assertLayout(TimelineOps.move(base, "a", f(0)).getOrFail(), "v1", at("a", 0, 100), at("b", 150, 200))
        assertLayout(TimelineOps.move(base, "a", f(10)).getOrFail(), "v1", at("a", 10, 110), at("b", 150, 200))
    }

    @Test
    fun `move reorders the track`() {
        assertLayout(TimelineOps.move(base, "a", f(300)).getOrFail(), "v1", at("b", 150, 200), at("a", 300, 400))
    }

    @Test
    fun `move before frame zero or of unknown clip fails`() {
        assertEquals(EditError.NegativeStart, TimelineOps.move(base, "a", f(-1)).errorOrFail())
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.move(base, "zz", f(5)).errorOrFail())
    }

    @Test
    fun `move across tracks of the same type`() {
        val result = TimelineOps.move(base, "a", f(0), toTrackId = "v2").getOrFail()
        assertLayout(result, "v1", at("b", 150, 200))
        assertLayout(result, "v2", at("a", 0, 100), at("c", 300, 340))
    }

    @Test
    fun `move across track types or to a missing track fails`() {
        assertEquals(EditError.TrackTypeMismatch("a", "au"), TimelineOps.move(base, "a", f(0), toTrackId = "au").errorOrFail())
        assertEquals(EditError.TrackNotFound("zz"), TimelineOps.move(base, "a", f(0), toTrackId = "zz").errorOrFail())
    }

    @Test
    fun `move across tracks respects collisions on the destination`() {
        assertEquals(EditError.Overlap("c"), TimelineOps.move(base, "a", f(250), toTrackId = "v2").errorOrFail())
    }

    @Test
    fun `snap pulls the start to a neighbour end within the threshold`() {
        val result = TimelineOps.move(base, "b", f(103), snap = Snap(null, 5)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 150))
    }

    @Test
    fun `snap pulls the end to a neighbour start`() {
        val result = TimelineOps.move(base, "b", f(228), snap = Snap(null, 5)).getOrFail()
        // end would be 278; the nearest target is c's start at 300 (outside 5), so nothing snaps.
        assertLayout(result, "v1", at("a", 0, 100), at("b", 228, 278))
        val snapped = TimelineOps.move(base, "b", f(247), snap = Snap(null, 5)).getOrFail()
        assertLayout(snapped, "v1", at("a", 0, 100), at("b", 250, 300))
    }

    @Test
    fun `snap to the playhead`() {
        assertLayout(TimelineOps.move(base, "b", f(497), snap = Snap(f(500), 5)).getOrFail(), "v1", at("a", 0, 100), at("b", 500, 550))
    }

    @Test
    fun `snap to frame zero`() {
        val tl = timeline(track("v1", clip("a", 200, 50)))
        assertLayout(TimelineOps.move(tl, "a", f(3), snap = Snap(null, 5)).getOrFail(), "v1", at("a", 0, 50))
    }

    @Test
    fun `snap can turn an overlapping request into a legal move`() {
        assertLayout(TimelineOps.move(base, "b", f(99), snap = Snap(null, 5)).getOrFail(), "v1", at("a", 0, 100), at("b", 100, 150))
    }

    @Test
    fun `snap beyond the threshold does nothing`() {
        assertLayout(TimelineOps.move(base, "b", f(110), snap = Snap(null, 5)).getOrFail(), "v1", at("a", 0, 100), at("b", 110, 160))
    }
}
