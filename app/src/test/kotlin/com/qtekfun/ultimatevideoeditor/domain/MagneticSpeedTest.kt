package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MagneticSpeedTest {
    // Base: a 0-100, b 100-150 (50 source frames), c 150-250. Overlay lane on top.
    private fun scene(vararg overlay: Clip) = timeline(
        track("v2", *overlay),
        track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
    )

    @Test
    fun `speeding up a base clip closes the freed frames on the base and on the overlays`() {
        val t = scene(clip("x", 160, 40), clip("y", 130, 15), clip("w", 10, 20))
        val result = MagneticBase.setSpeed(t, "b", 2, 1).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 125), at("c", 125, 225))
        // y sat inside the freed frames [125, 150) and goes; x follows the footage it was over; w is before.
        assertLayout(result, "v2", at("w", 10, 30), at("x", 135, 175))
    }

    @Test
    fun `slowing a base clip down opens room after it on the overlays`() {
        val t = scene(clip("x", 160, 40), clip("w", 10, 20), clip("z", 120, 60))
        val result = MagneticBase.setSpeed(t, "b", 1, 2).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 200), at("c", 200, 300))
        // x starts after the clip's old end and shifts by the 50 new frames; z crosses that point and stays.
        assertLayout(result, "v2", at("w", 10, 30), at("z", 120, 180), at("x", 210, 250))
    }

    @Test
    fun `a speed change on an overlay ripples only that lane`() {
        val t = scene(clip("x", 160, 40, srcIn = 0), clip("y", 200, 20))
        val result = MagneticBase.setSpeed(t, "x", 2, 1).getOrFail()
        assertLayout(result, "v2", at("x", 160, 180), at("y", 180, 200))
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `the command undoes exactly and an invalid speed is refused`() {
        val t = scene(clip("x", 160, 40))
        val done = EditHistory(t).execute(EditCommand.SetSpeed("b", 2, 1, ripple = true)).getOrFail()
        assertEquals(emptyList<String>(), done.timeline.invariantViolations())
        assertEquals(t, done.undo().timeline)
        assertEquals(EditError.ClipNotFound("zz"), MagneticBase.setSpeed(t, "zz", 2, 1).errorOrFail())
    }
}
