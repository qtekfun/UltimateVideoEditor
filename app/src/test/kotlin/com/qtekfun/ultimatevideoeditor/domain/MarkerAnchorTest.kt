package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markers that stick to a clip: creation rule, and what every kind of edit does to them (SPECS 5.41). */
class MarkerAnchorTest {
    // Base v1: c1 0..100, c2 100..200. Overlay v2: o1 120..220 (over c2). Audio a1: music 0..300.
    private val scene = timeline(
        track("v2", clip("o1", 120, 100)),
        track("v1", clip("c1", 0, 100), clip("c2", 100, 100)),
        track("a1", clip("m1", 0, 300, asset = "m"), type = TrackType.AUDIO),
    )

    private fun history(t: Timeline = scene) = EditHistory(t)

    private fun EditHistory.run(command: EditCommand): EditHistory = execute(command).getOrFail()

    private fun EditHistory.marker(id: String) = checkNotNull(timeline.markers.firstOrNull { it.id == id }) { "no marker $id in ${timeline.markers}" }

    private fun EditHistory.sticky(id: String, frame: Long, kind: MarkerKind = MarkerKind.MANUAL) =
        run(AddMarker(Marker(id, f(frame), kind), stick = true))

    private fun EditHistory.free(id: String, frame: Long) = run(AddMarker(Marker(id, f(frame))))

    private fun EditHistory.assertValid(): EditHistory {
        assertTrue(timeline.invariantViolations().toString(), timeline.invariantViolations().isEmpty())
        return this
    }

    // region creation

    @Test
    fun `a new marker sticks to the base clip under it with its offset`() {
        val h = history().sticky("m", 130)
        assertEquals(Marker("m", f(130), anchorClipId = "c2", offsetFrames = 30), h.marker("m"))
    }

    @Test
    fun `base wins over an overlay at the same frame`() {
        // Frame 130 is under both c2 and o1.
        assertEquals("c2", history().sticky("m", 130).marker("m").anchorClipId)
    }

    @Test
    fun `an overlay clip is used where the base has nothing`() {
        val t = timeline(track("v2", clip("o1", 120, 100)), track("v1", clip("c1", 0, 100)))
        val m = history(t).sticky("m", 150).marker("m")
        assertEquals("o1", m.anchorClipId)
        assertEquals(30, m.offsetFrames)
    }

    @Test
    fun `the topmost overlay is used when several overlays span the frame`() {
        val t = timeline(track("v3", clip("top", 0, 50)), track("v2", clip("mid", 0, 50)), track("v1", clip("c1", 100, 50)))
        assertEquals("top", history(t).sticky("m", 10).marker("m").anchorClipId)
    }

    @Test
    fun `a gap or an audio-only frame leaves the marker free`() {
        assertNull(history().sticky("g", 250).marker("g").anchorClipId) // nothing but the music there
        val t = timeline(track("v1", clip("c1", 0, 100)), track("a1", clip("m1", 0, 300, asset = "m"), type = TrackType.AUDIO))
        assertFalse(history(t).sticky("g", 200).marker("g").isAnchored)
    }

    @Test
    fun `a clip's end frame is exclusive, its first and last frames belong to it`() {
        val h = history().sticky("first", 100).sticky("last", 99)
        assertEquals("c2", h.marker("first").anchorClipId)
        assertEquals(0, h.marker("first").offsetFrames)
        assertEquals("c1", h.marker("last").anchorClipId)
        assertEquals(99, h.marker("last").offsetFrames)
        // 220 is o1's exclusive end, c2 ended at 200: nothing is there.
        assertFalse(history().sticky("past", 220).marker("past").isAnchored)
    }

    @Test
    fun `beat markers are never anchored`() {
        val h = history().sticky("b", 30, MarkerKind.BEAT)
        assertFalse(h.marker("b").isAnchored)
        val anchored = Marker("x", f(30), MarkerKind.BEAT, anchorClipId = "c1", offsetFrames = 30)
        val set = history().run(SetBeatMarkers(listOf(anchored)))
        assertFalse(set.marker("x").isAnchored)
        assertEquals(0, set.marker("x").offsetFrames)
    }

    @Test
    fun `an inconsistent anchor is refused`() {
        assertTrue(AddMarker(Marker("m", f(30), anchorClipId = "nope", offsetFrames = 30)).apply(scene).errorOrFail() is EditError.InvalidMarker)
        assertTrue(AddMarker(Marker("m", f(30), anchorClipId = "c1", offsetFrames = 31)).apply(scene).errorOrFail() is EditError.InvalidMarker)
        assertTrue(AddMarker(Marker("m", f(130), anchorClipId = "c1", offsetFrames = 130)).apply(scene).errorOrFail() is EditError.InvalidMarker)
    }

    // endregion

    // region moves, inserts, ripple

    @Test
    fun `inserting a clip in front moves the marker with its clip`() {
        val h = history().sticky("m", 130).run(EditCommand.InsertBase(clip("n", 0, 50, asset = "b"), f(0))).assertValid()
        assertEquals(f(180), h.marker("m").frame)
        assertEquals(30, h.marker("m").offsetFrames)
        assertEquals(f(150), h.timeline.trackOfClip("c2")!!.clip("c2")!!.timelineStart)
    }

    @Test
    fun `the insert and its marker move are one undo step`() {
        val h = history().sticky("m", 130)
        val inserted = h.run(EditCommand.InsertBase(clip("n", 0, 50, asset = "b"), f(0)))
        assertEquals(h.undoDepth + 1, inserted.undoDepth)
        assertEquals(f(130), inserted.undo().marker("m").frame)
        assertEquals(f(180), inserted.undo().redo().marker("m").frame)
    }

    @Test
    fun `a free marker does not move when a clip is inserted before it`() {
        val h = history().free("m", 130).run(EditCommand.InsertBase(clip("n", 0, 50, asset = "b"), f(0)))
        assertEquals(f(130), h.marker("m").frame)
        assertFalse(h.marker("m").isAnchored)
    }

    @Test
    fun `ripple delete of an earlier clip pulls the marker left and a free marker stays`() {
        val h = history().sticky("a", 130).free("fr", 150).run(EditCommand.RippleDelete("c1")).assertValid()
        assertEquals(f(30), h.marker("a").frame)
        assertEquals("c2", h.marker("a").anchorClipId)
        assertEquals(f(150), h.marker("fr").frame)
    }

    @Test
    fun `moving the clip carries the marker, also onto another lane`() {
        val t = timeline(track("v3"), track("v2", clip("o1", 120, 100)), track("v1", clip("c1", 0, 100)))
        var h = history(t).sticky("m", 150)
        assertEquals("o1", h.marker("m").anchorClipId)
        h = h.run(EditCommand.Move("o1", f(300))).assertValid()
        assertEquals(f(330), h.marker("m").frame)
        h = h.run(EditCommand.Move("o1", f(310), toTrackId = "v3")).assertValid()
        assertEquals(f(340), h.marker("m").frame)
        assertEquals(30, h.marker("m").offsetFrames)
    }

    @Test
    fun `reordering the base moves each marker with its clip`() {
        val h = history().sticky("m1", 10).sticky("m2", 130).run(EditCommand.MoveClip("c2", f(0))).assertValid()
        assertEquals(f(30), h.marker("m2").frame)
        assertEquals(f(110), h.marker("m1").frame)
    }

    @Test
    fun `a marker on a clip dragged from an overlay into the base insert keeps its place on the clip`() {
        val t = timeline(track("v2", clip("o1", 300, 100)), track("v1", clip("c1", 0, 100)))
        val h = history(t).sticky("m", 340).run(EditCommand.MoveClip("o1", f(0))).assertValid()
        assertEquals("o1", h.marker("m").anchorClipId)
        assertEquals(40, h.marker("m").offsetFrames)
        assertEquals(h.timeline.trackOfClip("o1")!!.clip("o1")!!.timelineStart + 40, h.marker("m").frame)
    }

    @Test
    fun `linked detached audio moves the video clip and its marker`() {
        val t = timeline(
            track("v1", clip("v", 0, 100), clip("w", 200, 100)),
            Track("a1", TrackType.AUDIO, emptyList()),
        )
        var h = history(t).sticky("m", 40)
        h = h.run(DetachAudio("v", "aud", "a1")).assertValid()
        assertTrue(h.timeline.trackOfClip("aud") != null)
        h = h.run(EditCommand.Move("aud", f(60))).assertValid()
        assertEquals(f(60), h.timeline.trackOfClip("v")!!.clip("v")!!.timelineStart)
        assertEquals(f(100), h.marker("m").frame)
        assertEquals("v", h.marker("m").anchorClipId)
    }

    @Test
    fun `duplicating a clip does not copy its markers`() {
        val h = history().sticky("m", 10).run(GroupDuplicate(listOf("c1"))).assertValid()
        assertEquals(listOf("m"), h.timeline.markers.map { it.id })
        assertEquals("c1", h.marker("m").anchorClipId)
    }

    // endregion

    // region trim

    private val overlayOnly = timeline(track("v2", clip("o1", 50, 100)), track("v1", clip("c1", 0, 10)))

    @Test
    fun `trimming the start keeps the marker on the same picture`() {
        val h = history(overlayOnly).sticky("m", 80).run(EditCommand.Trim("o1", TrimEdge.START, f(70))).assertValid()
        assertEquals(f(80), h.marker("m").frame)
        assertEquals(10, h.marker("m").offsetFrames)
    }

    @Test
    fun `trimming the start past the marker clamps it to the first frame`() {
        val h = history(overlayOnly).sticky("m", 80).run(EditCommand.Trim("o1", TrimEdge.START, f(100))).assertValid()
        assertEquals(f(100), h.marker("m").frame)
        assertEquals(0, h.marker("m").offsetFrames)
        assertEquals("o1", h.marker("m").anchorClipId)
    }

    @Test
    fun `trimming the end past the marker clamps it to the last frame, exactly at the marker too`() {
        var h = history(overlayOnly).sticky("m", 80).run(EditCommand.Trim("o1", TrimEdge.END, f(100))).assertValid()
        assertEquals(f(80), h.marker("m").frame) // marker still inside: stays
        h = h.run(EditCommand.Trim("o1", TrimEdge.END, f(80))).assertValid() // the end is exclusive: 80 is now outside
        assertEquals(f(79), h.marker("m").frame)
        assertEquals(29, h.marker("m").offsetFrames)
    }

    @Test
    fun `extending the start gives the marker a longer offset and the end leaves it alone`() {
        val t = timeline(track("v2", clip("o1", 50, 100, srcIn = 40)), track("v1", clip("c1", 0, 10)))
        var h = history(t).sticky("m", 80).run(EditCommand.Trim("o1", TrimEdge.START, f(30))).assertValid()
        assertEquals(f(80), h.marker("m").frame)
        assertEquals(50, h.marker("m").offsetFrames)
        h = h.run(EditCommand.Trim("o1", TrimEdge.END, f(200))).assertValid()
        assertEquals(f(80), h.marker("m").frame)
    }

    @Test
    fun `a base trim of the start ripples later clips and keeps the marker on its picture`() {
        val h = history().sticky("m", 30).run(EditCommand.TrimClip("c1", TrimEdge.START, f(10))).assertValid()
        val clip = h.timeline.trackOfClip("c1")!!.clip("c1")!!
        // Picture frame 30 was source frame 30; the clip now starts at source 10.
        assertEquals(clip.timelineStart + 20, h.marker("m").frame)
        assertEquals(20, h.marker("m").offsetFrames)
    }

    // endregion

    // region split

    @Test
    fun `split gives each marker to the part that holds its frame`() {
        val h = history().sticky("a", 10).sticky("b", 60).sticky("c", 99)
            .run(EditCommand.Split("v1", f(50), "c1b")).assertValid()
        assertEquals("c1", h.marker("a").anchorClipId)
        assertEquals(10, h.marker("a").offsetFrames)
        assertEquals("c1b", h.marker("b").anchorClipId)
        assertEquals(10, h.marker("b").offsetFrames)
        assertEquals("c1b", h.marker("c").anchorClipId)
        assertEquals(49, h.marker("c").offsetFrames)
        assertEquals(listOf(f(10), f(60), f(99)), h.timeline.markers.map { it.frame })
    }

    @Test
    fun `a marker exactly on the cut goes with the right part, one frame before stays left`() {
        val h = history().sticky("on", 50).sticky("before", 49).run(EditCommand.Split("v1", f(50), "c1b")).assertValid()
        assertEquals("c1b", h.marker("on").anchorClipId)
        assertEquals(0, h.marker("on").offsetFrames)
        assertEquals("c1", h.marker("before").anchorClipId)
        assertEquals(49, h.marker("before").offsetFrames)
    }

    @Test
    fun `the first frame of a clip stays with the left part`() {
        val h = history().sticky("m", 0).run(EditCommand.Split("v1", f(1), "c1b")).assertValid()
        assertEquals("c1", h.marker("m").anchorClipId)
    }

    @Test
    fun `split then undo restores the original anchor`() {
        val h = history().sticky("m", 60)
        val split = h.run(EditCommand.Split("v1", f(50), "c1b"))
        assertEquals(h.marker("m"), split.undo().marker("m"))
    }

    // endregion

    // region overwrite

    @Test
    fun `overwrite frees a marker whose picture it covers and keeps the others`() {
        val over = clip("n", 50, 30, asset = "b")
        val h = history().sticky("covered", 60).sticky("left", 20).sticky("right", 90)
            .run(EditCommand.Overwrite("v1", over)).assertValid()
        assertFalse(h.marker("covered").isAnchored)
        assertEquals(f(60), h.marker("covered").frame)
        assertEquals("c1", h.marker("left").anchorClipId)
        assertEquals(20, h.marker("left").offsetFrames)
        // The right part is the clip "c1~n" starting at 80.
        assertEquals("c1~n", h.marker("right").anchorClipId)
        assertEquals(10, h.marker("right").offsetFrames)
        assertEquals(f(90), h.marker("right").frame)
    }

    @Test
    fun `overwrite that trims a clip end frees the marker in the covered part`() {
        val over = clip("n", 80, 60, asset = "b") // covers c1's end (80..100) and c2's start (100..140)
        val h = history().sticky("a", 90).sticky("b", 110).sticky("c", 170).run(EditCommand.Overwrite("v1", over)).assertValid()
        assertFalse(h.marker("a").isAnchored)
        assertFalse(h.marker("b").isAnchored)
        // c2's right part (140..200) is a new clip "c2~n": the marker follows it with the offset inside that part.
        assertEquals("c2~n", h.marker("c").anchorClipId)
        assertEquals(30, h.marker("c").offsetFrames)
        assertEquals(f(170), h.marker("c").frame)
    }

    // endregion

    // region speed

    @Test
    fun `speeding a clip up scales the offset down with floor rounding`() {
        val t = timeline(track("v2", clip("o1", 50, 100)), track("v1", clip("c1", 0, 10)))
        val h = history(t).sticky("m", 83) // offset 33
            .run(EditCommand.SetSpeed("o1", 2, 1)).assertValid()
        assertEquals(16, h.marker("m").offsetFrames) // 33 * 50 / 100 = 16.5 -> 16
        assertEquals(f(66), h.marker("m").frame)
    }

    @Test
    fun `slowing a clip down scales the offset up and keeps the last frame inside`() {
        val t = timeline(track("v2", clip("o1", 50, 100)), track("v1", clip("c1", 0, 10)))
        val h = history(t).sticky("m", 83).sticky("last", 149).sticky("first", 50).run(EditCommand.SetSpeed("o1", 1, 2)).assertValid()
        assertEquals(66, h.marker("m").offsetFrames)
        assertEquals(198, h.marker("last").offsetFrames)
        assertEquals(0, h.marker("first").offsetFrames)
    }

    @Test
    fun `a ripple speed change moves the markers of later clips`() {
        val h = history().sticky("m", 130).run(EditCommand.SetSpeed("c1", 2, 1, ripple = true)).assertValid()
        assertEquals(f(80), h.marker("m").frame)
        assertEquals(30, h.marker("m").offsetFrames)
    }

    // endregion

    // region delete

    @Test
    fun `deleting the clip frees the marker at its last frame, undo brings both back`() {
        val h = history().sticky("m", 150)
        val deleted = h.run(EditCommand.DeleteClip("c2")).assertValid()
        assertFalse(deleted.marker("m").isAnchored)
        assertEquals(f(150), deleted.marker("m").frame)
        assertEquals(h.marker("m"), deleted.undo().marker("m"))
        assertTrue(deleted.undo().timeline.trackOfClip("c2") != null)
    }

    @Test
    fun `ripple deleting the clip frees the marker and it stays put while later clips close the gap`() {
        val h = history().sticky("m", 50).run(EditCommand.RippleDelete("c1")).assertValid()
        assertFalse(h.marker("m").isAnchored)
        assertEquals(f(50), h.marker("m").frame)
    }

    // endregion

    // region collisions

    @Test
    fun `an anchored marker that lands on a free marker is nudged forward`() {
        val h = history().sticky("m", 10).free("fr", 60).run(EditCommand.InsertBase(clip("n", 0, 50, asset = "b"), f(0))).assertValid()
        assertEquals(f(60), h.marker("fr").frame)
        assertFalse(h.marker("fr").isAnchored)
        assertEquals(f(61), h.marker("m").frame)
        assertEquals(11, h.marker("m").offsetFrames)
    }

    @Test
    fun `two anchored markers that meet are separated in frame then id order`() {
        val t = timeline(track("v2", clip("o1", 120, 40)), track("v1", clip("c1", 0, 100)))
        val h = history(t).sticky("a", 50).sticky("b", 130).run(EditCommand.Move("o1", f(40))).assertValid()
        // o1 now starts at 40, so b would sit on 50 together with a: "a" keeps it (lower id), "b" moves to 51.
        assertEquals(f(50), h.marker("a").frame)
        assertEquals(f(51), h.marker("b").frame)
        assertEquals(11, h.marker("b").offsetFrames)
        assertEquals("o1", h.marker("b").anchorClipId)
    }

    @Test
    fun `a nudge that would leave the clip goes backwards instead`() {
        val t = timeline(track("v2", clip("o1", 120, 10)), track("v1", clip("c1", 0, 100)))
        // The marker is on o1's last frame (offset 9, where the base has nothing); o1 moves so that frame lands on a free marker.
        val h = history(t).sticky("m", 129).free("fr", 209).run(EditCommand.Move("o1", f(200))).assertValid()
        assertEquals(f(208), h.marker("m").frame)
        assertEquals(8, h.marker("m").offsetFrames)
        assertEquals(f(209), h.marker("fr").frame)
    }

    @Test
    fun `markers stay sorted with unique frames after an edit`() {
        val h = history().sticky("a", 10).sticky("b", 110).sticky("c", 190).free("d", 60)
            .run(EditCommand.MoveClip("c2", f(0))).assertValid()
        assertEquals(h.timeline.markers.sortedBy { it.frame }, h.timeline.markers)
        assertEquals(h.timeline.markers.size, h.timeline.markers.map { it.frame }.toSet().size)
    }

    // endregion

    // region marker commands

    @Test
    fun `dragging an anchored marker anchors it again where it lands`() {
        val h = history().sticky("m", 10).run(MoveMarker("m", f(150)))
        assertEquals("c2", h.marker("m").anchorClipId)
        assertEquals(50, h.marker("m").offsetFrames)
        val gap = h.run(MoveMarker("m", f(250)))
        assertFalse(gap.marker("m").isAnchored)
        assertEquals(f(250), gap.marker("m").frame)
    }

    @Test
    fun `dragging a free marker keeps it free`() {
        val h = history().free("m", 10).run(MoveMarker("m", f(150)))
        assertFalse(h.marker("m").isAnchored)
        assertEquals(f(150), h.marker("m").frame)
    }

    @Test
    fun `dragging onto another marker is refused as before`() {
        val h = history().sticky("a", 10).sticky("b", 20)
        assertTrue(MoveMarker("a", f(20)).apply(h.timeline).errorOrFail() is EditError.InvalidMarker)
    }

    @Test
    fun `the stick switch converts a marker both ways and is one undo step with the edit`() {
        var h = history().free("m", 130)
        h = h.run(EditMarker("m", "Name", null, null, stick = true)).assertValid()
        assertEquals("c2", h.marker("m").anchorClipId)
        assertEquals("Name", h.marker("m").name)
        val before = h.undoDepth
        h = h.run(EditMarker("m", "Name", null, null, stick = false))
        assertFalse(h.marker("m").isAnchored)
        assertEquals(before + 1, h.undoDepth)
        assertEquals("c2", h.undo().marker("m").anchorClipId)
        // Unchanged stick keeps whatever the marker has.
        assertFalse(h.run(EditMarker("m", "Other", null, null)).marker("m").isAnchored)
    }

    @Test
    fun `sticking needs a clip under the marker and a beat cannot stick`() {
        val h = history().free("gap", 250).run(AddMarker(Marker("b", f(30), MarkerKind.BEAT)))
        assertTrue(EditMarker("gap", null, null, null, stick = true).apply(h.timeline).errorOrFail() is EditError.InvalidMarker)
        assertTrue(EditMarker("b", null, null, null, stick = true).apply(h.timeline).errorOrFail() is EditError.InvalidMarker)
    }

    @Test
    fun `navigation and snapping read the resolved frames`() {
        val h = history().sticky("m", 130).run(EditCommand.InsertBase(clip("n", 0, 50, asset = "b"), f(0)))
        assertEquals("m", MarkerOps.next(h.timeline.markers, f(100))?.id)
        assertNull(MarkerOps.previous(h.timeline.markers, f(180)))
        assertEquals("m", MarkerOps.nearest(h.timeline.markers, f(178), 4)?.id)
        assertNull(MarkerOps.nearest(h.timeline.markers, f(130), 4))
    }

    // endregion
}
