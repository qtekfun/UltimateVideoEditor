package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OverwriteTest {
    private val base = timeline(track("v1", clip("a", 0, 100, srcIn = 1000), clip("b", 100, 100), clip("c", 300, 50)))

    @Test
    fun `overwrite into empty space just inserts`() {
        assertLayout(TimelineOps.overwrite(base, "v1", clip("n", 220, 30)).getOrFail(), "v1",
            at("a", 0, 100), at("b", 100, 200), at("n", 220, 250), at("c", 300, 350))
    }

    @Test
    fun `overwrite fully covering a clip removes it`() {
        assertLayout(TimelineOps.overwrite(base, "v1", clip("n", 90, 120)).getOrFail(), "v1",
            at("a", 0, 90), at("n", 90, 210), at("c", 300, 350))
    }

    @Test
    fun `overwrite exactly over a clip replaces it`() {
        assertLayout(TimelineOps.overwrite(base, "v1", clip("n", 100, 100)).getOrFail(), "v1",
            at("a", 0, 100), at("n", 100, 200), at("c", 300, 350))
    }

    @Test
    fun `overwrite inside one clip splits it in two and keeps the source contiguous`() {
        val result = TimelineOps.overwrite(base, "v1", clip("n", 30, 20)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 30), at("n", 30, 50), at("a~n", 50, 100), at("b", 100, 200), at("c", 300, 350))
        val right = result.track("v1")!!.clip("a~n")!!
        assertEquals(f(1050), right.sourceIn)
        assertEquals(f(1100), right.sourceOut)
    }

    @Test
    fun `overwrite trims the right side of the previous clip and the left side of the next`() {
        val result = TimelineOps.overwrite(base, "v1", clip("n", 80, 140)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 80), at("n", 80, 220), at("c", 300, 350))
        assertEquals(f(1080), result.track("v1")!!.clip("a")!!.sourceOut)
        val partial = TimelineOps.overwrite(base, "v1", clip("n", 50, 100)).getOrFail()
        assertLayout(partial, "v1", at("a", 0, 50), at("n", 50, 150), at("b~n", 150, 200), at("c", 300, 350))
        assertEquals(f(50), partial.track("v1")!!.clip("b~n")!!.sourceIn)
    }

    @Test
    fun `overwrite touching but not overlapping leaves neighbours alone`() {
        assertLayout(TimelineOps.overwrite(base, "v1", clip("n", 200, 100)).getOrFail(), "v1",
            at("a", 0, 100), at("b", 100, 200), at("n", 200, 300), at("c", 300, 350))
    }

    @Test
    fun `overwrite can cover several clips at once`() {
        assertLayout(TimelineOps.overwrite(base, "v1", clip("n", 0, 400)).getOrFail(), "v1", at("n", 0, 400))
    }

    @Test
    fun `overwrite rejects bad input`() {
        assertEquals(EditError.DuplicateClipId("a"), TimelineOps.overwrite(base, "v1", clip("a", 500, 10)).errorOrFail())
        assertEquals(EditError.TrackNotFound("zz"), TimelineOps.overwrite(base, "zz", clip("n", 500, 10)).errorOrFail())
        assertEquals(EditError.InvalidClip("non-positive duration"), TimelineOps.overwrite(base, "v1", clip("n", 500, 0)).errorOrFail())
        assertEquals(EditError.InvalidClip("negative start"), TimelineOps.overwrite(base, "v1", clip("n", -1, 10)).errorOrFail())
    }
}
