package com.qtekfun.ultimatevideoeditor.domain

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
    fun `overlays are anchored at their start - later ones follow the base, earlier ones stay`() {
        val t = scene(clip("before", 10, 20), clip("after", 160, 30), clip("touching", 150, 5).copy(id = "edge"))
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("c", 100, 200))
        // The deleted range is 100..150: whatever starts at 150 or later moves 50 frames left.
        assertLayout(result, "v2", at("before", 10, 30), at("edge", 100, 105), at("after", 110, 140))
    }

    @Test
    fun `an overlay spanning the deleted base clip is not cut and does not move`() {
        // The owner's case: a long clip on V2 over a short base clip that is deleted.
        val t = scene(clip("x", 50, 150))
        assertEquals(t.track("v2"), ClipDeletion.delete(t, "b").getOrFail().track("v2"))
    }

    @Test
    fun `overlays starting before or inside the range stay whole`() {
        for (overlay in listOf(clip("x", 80, 40), clip("x", 120, 60), clip("x", 100, 50), clip("x", 50, 50), clip("x", 110, 10))) {
            val t = scene(overlay)
            assertEquals(t.track("v2"), ClipDeletion.delete(t, "b").getOrFail().track("v2"))
        }
    }

    @Test
    fun `a staying overlay is trimmed where a shifted one now starts`() {
        // x stays at 50..200; y (200..230) moves to 150..180, so x ends at 150.
        val t = scene(clip("x", 50, 150), clip("y", 200, 30))
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "v2", at("x", 50, 150), at("y", 150, 180))
        assertEquals(f(0), result.track("v2")!!.clips.first().sourceIn)
    }

    @Test
    fun `a staying overlay whose start a shifted one covers is dropped`() {
        // in (120..130) stays; after (160..190) moves to 110..140 and covers it.
        val t = scene(clip("in", 120, 10), clip("after", 160, 30))
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "v2", at("after", 110, 140))
    }

    @Test
    fun `audio and title lanes are anchored the same way`() {
        val t = timeline(
            track("t1", clip("title", 160, 20, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE),
            track("a1", clip("music", 0, 250), type = TrackType.AUDIO),
            track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
        )
        val result = ClipDeletion.delete(t, "b").getOrFail()
        assertLayout(result, "t1", at("title", 110, 130))
        assertLayout(result, "a1", at("music", 0, 250))
    }

    @Test
    fun `deleting the first base clip shifts every overlay after it`() {
        val t = scene(clip("x", 0, 40), clip("y", 200, 50))
        val result = ClipDeletion.delete(t, "a").getOrFail()
        assertLayout(result, "v2", at("x", 0, 40), at("y", 100, 150))
    }

    @Test
    fun `deleting the only clip of the base shifts overlays after it`() {
        val t = timeline(track("v2", clip("x", 200, 20)), track("v1", clip("a", 0, 100)))
        val result = ClipDeletion.delete(t, "a").getOrFail()
        assertLayout(result, "v1")
        assertLayout(result, "v2", at("x", 100, 120))
    }

    // otherLanesFollow (auto cut, template slots): the frames go from every lane.

    @Test
    fun `following lanes lose the overlay inside the range and later overlays shift`() {
        val t = scene(clip("in", 110, 20), clip("after", 160, 30), clip("before", 10, 20))
        val result = ClipDeletion.delete(t, "b", otherLanesFollow = true).getOrFail()
        assertLayout(result, "v2", at("before", 10, 30), at("after", 110, 140))
    }

    @Test
    fun `following lanes trim an overlay crossing the start of the range`() {
        val result = ClipDeletion.delete(scene(clip("x", 80, 40)), "b", otherLanesFollow = true).getOrFail()
        assertLayout(result, "v2", at("x", 80, 100))
        assertEquals(f(0), result.track("v2")!!.clips.single().sourceIn)
        assertEquals(f(20), result.track("v2")!!.clips.single().sourceOut)
    }

    @Test
    fun `following lanes trim an overlay crossing the end of the range and move it to the cut`() {
        val result = ClipDeletion.delete(scene(clip("x", 120, 60)), "b", otherLanesFollow = true).getOrFail()
        assertLayout(result, "v2", at("x", 100, 130))
        assertEquals(f(30), result.track("v2")!!.clips.single().sourceIn)
    }

    @Test
    fun `following lanes cut a spanning overlay in two and rejoin it`() {
        val result = ClipDeletion.delete(scene(clip("x", 50, 150)), "b", otherLanesFollow = true).getOrFail()
        val clips = result.track("v2")!!.clips
        assertEquals(listOf(50L to 100L, 100L to 150L), clips.map { it.timelineStart.value to it.timelineEnd.value })
        assertEquals(f(50), clips[0].sourceOut)
        assertEquals(f(100), clips[1].sourceIn)
    }

    @Test
    fun `following lanes include audio and titles`() {
        val t = timeline(
            track("t1", clip("title", 160, 20, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE),
            track("a1", clip("music", 0, 250), type = TrackType.AUDIO),
            track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
        )
        val result = ClipDeletion.delete(t, "b", otherLanesFollow = true).getOrFail()
        assertLayout(result, "t1", at("title", 110, 130))
        assertLayout(result, "a1", at("music", 0, 100), at("music~rest", 100, 200))
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
                    for (follow in listOf(false, true)) {
                        val result = ClipDeletion.delete(t, victim, otherLanesFollow = follow).getOrFail()
                        assertEquals(emptyList<String>(), result.invariantViolations())
                    }
                }
            }
        }
    }
}
