package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillClipTest {

    /** A photo or sticker of [len] frames at [start]; its range is only its length. */
    private fun still(id: String, start: Long, len: Long, kind: StillKind = StillKind.PHOTO, asset: String = "img") =
        Clip(id, asset, FrameIndex(start), FrameIndex.ZERO, FrameIndex(len), still = kind)

    private fun photoOnBase() = timeline(
        track("v2"),
        track("v1", clip("a", 0, 100), still("p", 100, 150), clip("c", 250, 50)),
    )

    @Test
    fun `a still is not media and has no source length`() {
        val p = still("p", 0, 10)
        assertTrue(!p.hasMedia)
        assertTrue(clip("x", 0, 10).hasMedia)
        assertEquals(10L, p.durationFrames)
        assertTrue(!p.isFreeze)
    }

    @Test
    fun `invariants accept stills on video tracks and reject them elsewhere`() {
        assertEquals(emptyList<String>(), photoOnBase().invariantViolations())
        val onAudio = timeline(track("a1", still("p", 0, 10), type = TrackType.AUDIO))
        assertTrue(onAudio.invariantViolations().any { "not on a video track" in it })
        val noPicture = timeline(track("v1", still("p", 0, 10).copy(assetId = null)))
        assertTrue(noPicture.invariantViolations().any { "has no picture" in it })
        val retimed = timeline(track("v1", still("p", 0, 10).copy(retimedFrames = 20)))
        assertTrue(retimed.invariantViolations().any { "retimed" in it })
        val gain = timeline(track("v1", still("p", 0, 10).copy(gainDb = 3.0)))
        assertTrue(gain.invariantViolations().any { "gain" in it })
    }

    @Test
    fun `split gives both halves a range that starts at zero`() {
        val result = TimelineOps.split(photoOnBase(), "v1", f(160), "p2").getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("p", 100, 160), at("p2", 160, 250), at("c", 250, 300))
        val left = result.track("v1")!!.clip("p")!!
        val right = result.track("v1")!!.clip("p2")!!
        assertEquals(f(0) to f(60), left.sourceIn to left.sourceOut)
        assertEquals(f(0) to f(90), right.sourceIn to right.sourceOut)
        assertEquals(StillKind.PHOTO, right.still)
        assertEquals("img", right.assetId)
    }

    @Test
    fun `a still can be stretched past any source length on both edges`() {
        val t = timeline(track("v1", still("p", 100, 50)))
        val longer = TimelineOps.trim(t, "p", TrimEdge.END, f(1000), sourceLength = 30).getOrFail()
        assertLayout(longer, "v1", at("p", 100, 1000))
        assertEquals(f(900), longer.track("v1")!!.clip("p")!!.sourceOut)
        val earlier = TimelineOps.trim(longer, "p", TrimEdge.START, f(20)).getOrFail()
        assertLayout(earlier, "v1", at("p", 20, 1000))
        val clip = earlier.track("v1")!!.clip("p")!!
        assertEquals(f(0) to f(980), clip.sourceIn to clip.sourceOut)
        assertEquals(emptyList<String>(), earlier.invariantViolations())
    }

    @Test
    fun `a still cannot be stretched before frame 0 or into a neighbour`() {
        val t = timeline(track("v1", still("p", 5, 50), clip("n", 60, 20)))
        assertEquals(EditError.NegativeStart, TimelineOps.trim(t, "p", TrimEdge.START, f(-1)).errorOrFail())
        assertTrue(TimelineOps.trim(t, "p", TrimEdge.END, f(70)).errorOrFail() is EditError.Overlap)
    }

    @Test
    fun `trimming a still shorter is exact`() {
        val t = timeline(track("v1", still("p", 100, 50)))
        val shorter = TimelineOps.trim(t, "p", TrimEdge.START, f(130)).getOrFail()
        assertLayout(shorter, "v1", at("p", 130, 150))
        val clip = shorter.track("v1")!!.clip("p")!!
        assertEquals(f(0) to f(20), clip.sourceIn to clip.sourceOut)
        assertTrue(TimelineOps.trim(t, "p", TrimEdge.END, f(100)).errorOrFail() is EditError.InvalidTrim)
    }

    @Test
    fun `speed, reverse, ramp and freeze are refused for stills`() {
        val t = photoOnBase()
        assertTrue(TimelineOps.setSpeed(t, "p", 2, 1).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setReverse(t, "p", true).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeedRamp(t, "p", listOf(SpeedKey(0, 500), SpeedKey(10, 1500))).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.freezeFrame(t, "v1", f(120), 30, "frz", "right").errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `moving a still within and between video tracks`() {
        val t = photoOnBase()
        val up = TimelineOps.move(t, "p", f(100), toTrackId = "v2").getOrFail()
        assertLayout(up, "v2", at("p", 100, 250))
        val ok = TimelineOps.move(timeline(track("v2", still("s", 0, 30, StillKind.STICKER, "sticker:heart")), track("v1", clip("a", 0, 100))), "s", f(40)).getOrFail()
        assertLayout(ok, "v2", at("s", 40, 70))
        val audio = timeline(track("v1", still("p", 0, 10)), track("a1", type = TrackType.AUDIO))
        assertTrue(TimelineOps.move(audio, "p", f(0), toTrackId = "a1").errorOrFail() is EditError.TrackTypeMismatch)
    }

    @Test
    fun `overwriting a still with video and video with a still`() {
        val t = photoOnBase()
        val over = TimelineOps.overwrite(t, "v1", clip("n", 150, 30)).getOrFail()
        assertLayout(over, "v1", at("a", 0, 100), at("p", 100, 150), at("n", 150, 180), at("p~n", 180, 250), at("c", 250, 300))
        val rest = over.track("v1")!!.clip("p~n")!!
        assertEquals(f(0) to f(70), rest.sourceIn to rest.sourceOut)
        assertEquals(StillKind.PHOTO, rest.still)
        assertEquals(emptyList<String>(), over.invariantViolations())

        val sticker = still("s", 20, 40, StillKind.STICKER, "sticker:star")
        val covered = TimelineOps.overwrite(t, "v1", sticker).getOrFail()
        assertLayout(covered, "v1", at("a", 0, 20), at("s", 20, 60), at("a~s", 60, 100), at("p", 100, 250), at("c", 250, 300))
        assertEquals(emptyList<String>(), covered.invariantViolations())
    }

    @Test
    fun `a still cannot be placed on an audio or title track`() {
        val t = timeline(track("a1", type = TrackType.AUDIO), track("t1", type = TrackType.TITLE), track("v1"))
        assertTrue(TimelineOps.overwrite(t, "a1", still("p", 0, 10)).errorOrFail() is EditError.InvalidClip)
        assertTrue(TimelineOps.overwrite(t, "t1", still("p", 0, 10)).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `deleting a photo from the base closes the gap and overlays follow`() {
        val t = timeline(
            track("v2", clip("over", 120, 30), clip("late", 260, 20)),
            track("v1", clip("a", 0, 100), still("p", 100, 150), clip("c", 250, 50)),
        )
        val result = ClipDeletion.delete(t, "p").getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("c", 100, 150))
        assertLayout(result, "v2", at("late", 110, 130))
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
    }

    @Test
    fun `inserting a photo on the base ripples what follows`() {
        val t = timeline(track("v1", clip("a", 0, 100), clip("c", 100, 50)))
        val result = MagneticBase.insert(t, still("p", 0, 150), f(100)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("p", 100, 250), at("c", 250, 300))
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
    }

    @Test
    fun `reordering the base with a photo keeps it gap free`() {
        // The short clip jumps in front of the photo and the photo shifts right without a gap.
        val result = MagneticBase.move(photoOnBase(), "c", f(0), null, null).getOrFail()
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(listOf("c", "a", "p"), result.track("v1")!!.clips.map { it.id })
        val photo = result.track("v1")!!.clip("p")!!
        assertEquals(StillKind.PHOTO, photo.still)
        assertEquals(150L, photo.durationFrames)
    }

    @Test
    fun `trimming a photo on the base ripples the rest`() {
        val result = MagneticBase.trim(photoOnBase(), "p", TrimEdge.END, f(300)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("p", 100, 300), at("c", 300, 350))
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
    }

    @Test
    fun `a transition between a photo and a video needs no handle on the photo`() {
        val t = timeline(track("v1", still("p", 0, 100), clip("b", 100, 100, srcIn = 50)))
        val withFade = TimelineOps.addTransition(t, Transition("t", "p", "b", 10), outgoingSourceLength = 10).getOrFail()
        assertEquals(1, withFade.transitions.size)
        assertEquals(emptyList<String>(), withFade.invariantViolations())
        // And into a photo: the incoming still has no in point to run out of.
        val into = timeline(track("v1", clip("a", 0, 100), still("p", 100, 100)))
        assertEquals(1, TimelineOps.addTransition(into, Transition("t", "a", "p", 10), outgoingSourceLength = 200).getOrFail().transitions.size)
    }

    @Test
    fun `render plan carries the still and ignores retiming`() {
        val rc = photoOnBase().renderClips().first { it.clipId == "p" }
        assertEquals(StillKind.PHOTO, rc.still)
        assertEquals("img", rc.assetId)
        assertEquals(0L, rc.sourceInFrame)
        assertNull(rc.retime)
        assertEquals(RenderKind.VIDEO, rc.kind)
        assertTrue(visualClipsAt(photoOnBase().renderClips(), 120).any { it.clipId == "p" })
    }

    @Test
    fun `cropping a still always gives a zero based range`() {
        val c = still("p", 40, 100).cropped(-20, 70)
        assertEquals(f(0) to f(90), c.sourceIn to c.sourceOut)
        assertEquals(90L, c.durationFrames)
    }
}
