package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimTest {
    private val base = timeline(track("v1", clip("a", 0, 50), clip("b", 100, 100, srcIn = 500), clip("c", 250, 50)))

    @Test
    fun `trim start forward advances the source in`() {
        val result = TimelineOps.trim(base, "b", TrimEdge.START, f(120)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 50), at("b", 120, 200), at("c", 250, 300))
        assertEquals(f(520), result.track("v1")!!.clip("b")!!.sourceIn)
        assertEquals(f(600), result.track("v1")!!.clip("b")!!.sourceOut)
    }

    @Test
    fun `trim start backwards extends into the gap and rewinds the source`() {
        val result = TimelineOps.trim(base, "b", TrimEdge.START, f(60)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 50), at("b", 60, 200), at("c", 250, 300))
        assertEquals(f(460), result.track("v1")!!.clip("b")!!.sourceIn)
    }

    @Test
    fun `trim start flush with the previous clip is allowed and one frame into it is not`() {
        assertLayout(TimelineOps.trim(base, "b", TrimEdge.START, f(50)).getOrFail(), "v1", at("a", 0, 50), at("b", 50, 200), at("c", 250, 300))
        assertEquals(EditError.Overlap("a"), TimelineOps.trim(base, "b", TrimEdge.START, f(49)).errorOrFail())
    }

    @Test
    fun `trim end shortens or extends within the gap`() {
        val shorter = TimelineOps.trim(base, "b", TrimEdge.END, f(150)).getOrFail()
        assertLayout(shorter, "v1", at("a", 0, 50), at("b", 100, 150), at("c", 250, 300))
        assertEquals(f(550), shorter.track("v1")!!.clip("b")!!.sourceOut)
        assertLayout(TimelineOps.trim(base, "b", TrimEdge.END, f(250)).getOrFail(), "v1", at("a", 0, 50), at("b", 100, 250), at("c", 250, 300))
        assertEquals(EditError.Overlap("c"), TimelineOps.trim(base, "b", TrimEdge.END, f(251)).errorOrFail())
    }

    @Test
    fun `trim cannot empty or invert a clip`() {
        assertEquals(EditError.InvalidTrim("clip would be empty"), TimelineOps.trim(base, "b", TrimEdge.START, f(200)).errorOrFail())
        assertEquals(EditError.InvalidTrim("clip would be empty"), TimelineOps.trim(base, "b", TrimEdge.START, f(250)).errorOrFail())
        assertEquals(EditError.InvalidTrim("clip would be empty"), TimelineOps.trim(base, "b", TrimEdge.END, f(100)).errorOrFail())
        assertLayout(TimelineOps.trim(base, "b", TrimEdge.END, f(101)).getOrFail(), "v1", at("a", 0, 50), at("b", 100, 101), at("c", 250, 300))
    }

    @Test
    fun `trim is bounded by the source start and the source length`() {
        // The clip reads source frames [20, 120), so its start can move back at most 20 frames.
        val tl = timeline(track("v1", clip("b", 1000, 100, srcIn = 20)))
        assertEquals(EditError.SourceOutOfRange, TimelineOps.trim(tl, "b", TrimEdge.START, f(979)).errorOrFail())
        assertLayout(TimelineOps.trim(tl, "b", TrimEdge.START, f(980)).getOrFail(), "v1", at("b", 980, 1100))
        assertEquals(EditError.SourceOutOfRange, TimelineOps.trim(tl, "b", TrimEdge.END, f(1131), sourceLength = 150).errorOrFail())
        assertLayout(TimelineOps.trim(tl, "b", TrimEdge.END, f(1130), sourceLength = 150).getOrFail(), "v1", at("b", 1000, 1130))
        // Without a known source length the end is only bounded by neighbours.
        assertLayout(TimelineOps.trim(tl, "b", TrimEdge.END, f(5000)).getOrFail(), "v1", at("b", 1000, 5000))
    }

    @Test
    fun `trim start cannot pass frame zero`() {
        val tl = timeline(track("v1", clip("a", 5, 10, srcIn = 100)))
        assertEquals(EditError.NegativeStart, TimelineOps.trim(tl, "a", TrimEdge.START, f(-1)).errorOrFail())
        assertLayout(TimelineOps.trim(tl, "a", TrimEdge.START, f(0)).getOrFail(), "v1", at("a", 0, 15))
    }

    @Test
    fun `trim unknown clip fails`() {
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.trim(base, "zz", TrimEdge.END, f(10)).errorOrFail())
    }
}
