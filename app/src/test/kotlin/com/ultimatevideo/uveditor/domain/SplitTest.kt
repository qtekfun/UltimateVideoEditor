package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTest {
    private val base = timeline(track("v1", clip("c1", start = 10, len = 100, srcIn = 50), clip("c2", start = 200, len = 50)))

    @Test
    fun `split inside a clip yields two adjacent clips with contiguous source`() {
        val result = TimelineOps.split(base, "v1", f(40), "c1b").getOrFail()

        assertLayout(result, "v1", at("c1", 10, 40), at("c1b", 40, 110), at("c2", 200, 250))
        val left = result.track("v1")!!.clip("c1")!!
        val right = result.track("v1")!!.clip("c1b")!!
        assertEquals(f(50), left.sourceIn)
        assertEquals(f(80), left.sourceOut)
        assertEquals(left.sourceOut, right.sourceIn)
        assertEquals(f(150), right.sourceOut)
    }

    @Test
    fun `split one frame from either edge is allowed`() {
        assertLayout(TimelineOps.split(base, "v1", f(11), "x").getOrFail(), "v1", at("c1", 10, 11), at("x", 11, 110), at("c2", 200, 250))
        assertLayout(TimelineOps.split(base, "v1", f(109), "x").getOrFail(), "v1", at("c1", 10, 109), at("x", 109, 110), at("c2", 200, 250))
    }

    @Test
    fun `split on the start edge, end edge or in a gap is rejected`() {
        assertEquals(EditError.SplitOutsideClip, TimelineOps.split(base, "v1", f(10), "x").errorOrFail())
        assertEquals(EditError.SplitOutsideClip, TimelineOps.split(base, "v1", f(110), "x").errorOrFail())
        assertEquals(EditError.SplitOutsideClip, TimelineOps.split(base, "v1", f(150), "x").errorOrFail())
        assertEquals(EditError.SplitOutsideClip, TimelineOps.split(base, "v1", f(0), "x").errorOrFail())
    }

    @Test
    fun `split reports unknown track and duplicate id`() {
        assertEquals(EditError.TrackNotFound("nope"), TimelineOps.split(base, "nope", f(40), "x").errorOrFail())
        assertEquals(EditError.DuplicateClipId("c2"), TimelineOps.split(base, "v1", f(40), "c2").errorOrFail())
    }

    @Test
    fun `split does not touch other tracks`() {
        val two = timeline(track("v1", clip("c1", 0, 100)), track("v2", clip("d1", 0, 100)))
        val result = TimelineOps.split(two, "v1", f(50), "c1b").getOrFail()
        assertEquals(two.track("v2"), result.track("v2"))
    }
}
