package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RippleTest {
    private val base = timeline(
        track("v1", clip("a", 0, 100), clip("b", 120, 50), clip("c", 200, 30)),
        track("v2", clip("x", 130, 20)),
    )

    @Test
    fun `ripple delete shifts later clips left by the duration and keeps their gaps`() {
        assertLayout(TimelineOps.rippleDelete(base, "b").getOrFail(), "v1", at("a", 0, 100), at("c", 150, 180))
    }

    @Test
    fun `ripple delete of the first and last clip`() {
        assertLayout(TimelineOps.rippleDelete(base, "a").getOrFail(), "v1", at("b", 20, 70), at("c", 100, 130))
        assertLayout(TimelineOps.rippleDelete(base, "c").getOrFail(), "v1", at("a", 0, 100), at("b", 120, 170))
    }

    @Test
    fun `ripple delete of the only clip leaves an empty track and other tracks are untouched`() {
        val result = TimelineOps.rippleDelete(base, "x").getOrFail()
        assertEquals(emptyList<Triple<String, Long, Long>>(), result.layout("v2"))
        assertEquals(base.track("v1"), result.track("v1"))
    }

    @Test
    fun `ripple delete of unknown clip fails`() {
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.rippleDelete(base, "zz").errorOrFail())
    }

    @Test
    fun `ripple append closes the gap to the previous clip`() {
        assertLayout(TimelineOps.rippleAppend(base, "b").getOrFail(), "v1", at("a", 0, 100), at("b", 100, 150), at("c", 200, 230))
    }

    @Test
    fun `ripple append of the first clip snaps it to frame zero`() {
        val tl = timeline(track("v1", clip("a", 40, 10), clip("b", 60, 10)))
        assertLayout(TimelineOps.rippleAppend(tl, "a").getOrFail(), "v1", at("a", 0, 10), at("b", 60, 70))
    }

    @Test
    fun `ripple append of an already adjacent clip is a no-op`() {
        val tl = timeline(track("v1", clip("a", 0, 10), clip("b", 10, 10)))
        assertEquals(tl, TimelineOps.rippleAppend(tl, "b").getOrFail())
        assertEquals(tl, TimelineOps.rippleAppend(tl, "a").getOrFail())
    }

    @Test
    fun `ripple append of unknown clip fails`() {
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.rippleAppend(base, "zz").errorOrFail())
    }
}
