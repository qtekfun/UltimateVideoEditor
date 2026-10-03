package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipDeletionTest {
    // Display order: overlay on top, base last.
    private fun scene(vararg overlay: Clip) = timeline(
        track("v2", *overlay),
        track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
    )

    @Test
    fun `base track is the last video track`() {
        val t = timeline(track("a1", type = TrackType.AUDIO), track("v2"), track("v1"))
        assertEquals("v1", ClipDeletion.baseTrack(t)?.id)
        assertNull(ClipDeletion.baseTrack(timeline(track("a1", type = TrackType.AUDIO))))
    }

    @Test
    fun `deleting from an overlay leaves a gap and does not move neighbours`() {
        val t = scene(clip("x", 0, 40), clip("y", 40, 40), clip("z", 80, 40))
        val result = ClipDeletion.delete(t, "y").getOrFail()
        assertLayout(result, "v2", at("x", 0, 40), at("z", 80, 120))
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `deleting from the base closes the gap`() {
        val result = ClipDeletion.delete(scene(), "b").getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("c", 100, 200))
    }

    @Test
    fun `overlay inside the deleted range is removed and later overlays follow the base`() {
        val t = scene(clip("in", 110, 20), clip("after", 160, 30), clip("before", 10, 20))
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "v2", at("before", 10, 30), at("after", 110, 140))
    }

    @Test
    fun `overlay crossing the start of the range is trimmed`() {
        val result = ClipDeletion.delete(scene(clip("x", 80, 40)), "b").getOrFail()
        assertLayout(result, "v2", at("x", 80, 100))
        assertEquals(f(0), result.track("v2")!!.clips.single().sourceIn)
        assertEquals(f(20), result.track("v2")!!.clips.single().sourceOut)
    }

    @Test
    fun `overlay crossing the end of the range is trimmed and moved to the cut`() {
        val result = ClipDeletion.delete(scene(clip("x", 120, 60)), "b").getOrFail()
        // Frames 120..150 were removed with the base clip; 150..180 remain and slide to 100.
        assertLayout(result, "v2", at("x", 100, 130))
        assertEquals(f(30), result.track("v2")!!.clips.single().sourceIn)
    }

    @Test
    fun `overlay spanning the range is cut in two and rejoined without a gap`() {
        val result = ClipDeletion.delete(scene(clip("x", 50, 150)), "b").getOrFail()
        val clips = result.track("v2")!!.clips
        assertEquals(listOf(50L to 100L, 100L to 150L), clips.map { it.timelineStart.value to it.timelineEnd.value })
        assertEquals(f(0), clips[0].sourceIn)
        assertEquals(f(50), clips[0].sourceOut)
        assertEquals(f(100), clips[1].sourceIn)
        assertEquals(f(150), clips[1].sourceOut)
        assertEquals("x", clips[0].id)
    }

    @Test
    fun `overlay exactly covering the range is removed`() {
        val result = ClipDeletion.delete(scene(clip("x", 100, 50)), "b").getOrFail()
        assertLayout(result, "v2")
    }

    @Test
    fun `overlay touching the range edges is untouched or only shifted`() {
        val result = ClipDeletion.delete(scene(clip("l", 50, 50), clip("r", 150, 20)), "b").getOrFail()
        assertLayout(result, "v2", at("l", 50, 100), at("r", 100, 120))
    }

    @Test
    fun `audio and title tracks follow the base`() {
        val t = timeline(
            track("t1", clip("title", 160, 20, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE),
            track("a1", clip("music", 0, 250), type = TrackType.AUDIO),
            track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
        )
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "t1", at("title", 110, 130))
        assertLayout(result, "a1", at("music", 0, 100), at("music~rest", 100, 200))
    }

    @Test
    fun `deleting the only clip of the base keeps overlays outside the range`() {
        val t = timeline(track("v2", clip("x", 200, 20)), track("v1", clip("a", 0, 100)))
        val result = ClipDeletion.delete(t, "a").getOrFail()
        assertLayout(result, "v1")
        assertLayout(result, "v2", at("x", 100, 120))
    }

    @Test
    fun `unknown clip fails`() {
        assertEquals(EditError.ClipNotFound("nope"), ClipDeletion.delete(scene(), "nope").errorOrFail())
    }

    @Test
    fun `result always satisfies the timeline invariants`() {
        for (overlayStart in 0L..240L step 7) {
            for (overlayLen in listOf(1L, 10L, 55L, 140L)) {
                val t = scene(clip("x", overlayStart, overlayLen))
                for (victim in listOf("a", "b", "c")) {
                    val result = ClipDeletion.delete(t, victim).getOrFail()
                    assertEquals(emptyList<String>(), result.invariantViolations())
                }
            }
        }
    }
}
