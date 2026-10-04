package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupOpsTest {
    // Display order: top overlay lane, overlay lane, base last (audio below).
    private fun scene() = timeline(
        track("v3", clip("s", 30, 10)),
        track("v2", clip("p", 10, 20), clip("q", 40, 20), clip("r", 100, 10)),
        track("v1", clip("a", 0, 60), clip("b", 60, 60), clip("c", 120, 60)),
        track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
    )

    private fun reason(result: EditResult<*>): String = (result.errorOrFail() as GroupEditUnavailable).reason

    // region move

    @Test
    fun `three overlay clips on two lanes move together and keep their offsets`() {
        val result = GroupOps.move(scene(), listOf("p", "q", "s"), 5).getOrFail()
        assertLayout(result, "v2", at("p", 15, 35), at("q", 45, 65), at("r", 100, 110))
        assertLayout(result, "v3", at("s", 35, 45))
        assertEquals(scene().track("v1"), result.track("v1"))
    }

    @Test
    fun `a move that would land on a clip that stays is refused and changes nothing`() {
        val t = scene()
        // q would land on 90..110 and hit r at 100..110.
        assertEquals(EditError.Overlap("r"), GroupOps.move(t, listOf("p", "q"), 50).errorOrFail())
        assertEquals(EditError.NegativeStart, GroupOps.move(t, listOf("p", "q"), -11).errorOrFail())
    }

    @Test
    fun `clips of the group never block each other's old places`() {
        val t = timeline(track("v2", clip("x", 0, 10), clip("y", 10, 10), clip("z", 20, 10)), track("v1", clip("a", 0, 100)))
        val result = GroupOps.move(t, listOf("x", "y", "z"), 10).getOrFail()
        assertLayout(result, "v2", at("x", 10, 20), at("y", 20, 30), at("z", 30, 40))
    }

    @Test
    fun `a group moves up a lane together and is refused where there is no lane`() {
        val t = scene()
        val up = GroupOps.move(t, listOf("p", "q"), 0, laneDelta = -1).getOrFail()
        assertLayout(up, "v3", at("p", 10, 30), at("s", 30, 40), at("q", 40, 60))
        assertLayout(up, "v2", at("r", 100, 110))
        assertTrue(reason(GroupOps.move(t, listOf("p", "q"), 0, laneDelta = 1)).contains("no lane"))
        assertTrue(reason(GroupOps.move(t, listOf("p", "m"), 0, laneDelta = 1)).contains("no lane"))
    }

    @Test
    fun `a transition between two moved clips travels with them`() {
        val t = timeline(track("v2", clip("p", 10, 20, srcIn = 50), clip("q", 30, 20, srcIn = 50)), track("v1", clip("a", 0, 100)))
            .let { TimelineOps.addTransition(it, Transition("t1", "p", "q", 4), 500).getOrFail() }
        val result = GroupOps.move(t, listOf("p", "q"), 7).getOrFail()
        assertEquals(listOf("t1"), result.transitions.map { it.id })
        assertLayout(result, "v2", at("p", 17, 37), at("q", 37, 57))
    }

    @Test
    fun `a run of base clips is reordered as a block and the overlays follow their footage`() {
        val t = scene().let { it.withTrack(checkNotNull(it.track("v2")).copy(clips = listOf(clip("o", 130, 20)))) }
        // a and b (120 frames) land past c: the order becomes c, a, b.
        val result = GroupOps.move(t, listOf("a", "b"), 120).getOrFail()
        assertLayout(result, "v1", at("c", 0, 60), at("a", 60, 120), at("b", 120, 180))
        assertLayout(result, "v2", at("o", 10, 30))
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
    }

    @Test
    fun `a base run that does not move to another slot stays as it is`() {
        val t = scene()
        assertEquals(t, GroupOps.move(t, listOf("b", "c"), 10).getOrFail())
    }

    @Test
    fun `base selections must touch and cannot mix with other lanes or change lane`() {
        val t = scene()
        assertTrue(reason(GroupOps.move(t, listOf("a", "c"), 30)).contains("touch each other"))
        assertTrue(reason(GroupOps.move(t, listOf("a", "p"), 30)).contains("only base-track clips or only clips on the other lanes"))
        assertTrue(reason(GroupOps.move(t, listOf("a", "b"), 0, laneDelta = -1)).contains("reordered along the base"))
    }

    @Test
    fun `an unknown clip fails and an empty selection changes nothing`() {
        assertEquals(EditError.ClipNotFound("zz"), GroupOps.move(scene(), listOf("p", "zz"), 5).errorOrFail())
        assertEquals(scene(), GroupOps.move(scene(), emptyList(), 5).getOrFail())
    }

    @Test
    fun `snapped delta pulls the group edge to a clip edge, the playhead and markers within the threshold`() {
        val t = scene()
        // p (10..30) asked to move 22 would sit at 32..52: its start is 2 frames from s's start at 30, so the delta becomes 20.
        assertEquals(20L, GroupOps.snappedDelta(t, listOf("p"), 22, Snap(f(500), 4)))
        // At 198..218 its start is 2 frames from the playhead at 200.
        assertEquals(190L, GroupOps.snappedDelta(t, listOf("p"), 188, Snap(playhead = f(200), thresholdFrames = 4)))
        // Nothing is near 310..330, and without a threshold hit the request is kept.
        assertEquals(300L, GroupOps.snappedDelta(t, listOf("p"), 300, Snap(null, 4)))
        // At 57..77 the end is 2 frames from a marker at 75.
        assertEquals(45L, GroupOps.snappedDelta(timeline(track("v2", clip("p", 10, 20)), track("v1", clip("a", 0, 500))), listOf("p"), 47, Snap(null, 4, listOf(f(75)))))
    }

    @Test
    fun `a snap can bring the start of the group exactly to frame zero`() {
        val t = timeline(track("v2", clip("x", 3, 10)), track("v1", clip("a", 0, 100)))
        assertEquals(-3L, GroupOps.snappedDelta(t, listOf("x"), -2, Snap(null, 4)))
        assertEquals(-2L, GroupOps.snappedDelta(t, listOf("x"), -2, null))
    }

    // endregion

    // region delete

    @Test
    fun `deleting overlay clips leaves their gaps and leaves the base alone`() {
        val t = scene()
        val result = GroupOps.delete(t, listOf("p", "r", "s")).getOrFail()
        assertLayout(result, "v2", at("q", 40, 60))
        assertLayout(result, "v3")
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `deleting base clips closes the gaps from the last to the first and overlays follow`() {
        val t = scene()
        val result = GroupOps.delete(t, listOf("a", "c")).getOrFail()
        assertLayout(result, "v1", at("b", 0, 60))
        // Everything over a (p, q and s) goes with it; r, over b, follows the base 60 frames left.
        assertLayout(result, "v2", at("r", 40, 50))
        assertLayout(result, "v3")
    }

    @Test
    fun `a group delete equals deleting the clips one at a time`() {
        val t = scene()
        val group = GroupOps.delete(t, listOf("p", "b", "s")).getOrFail()
        var oneByOne = t
        for (id in listOf("p", "s", "b")) oneByOne = ClipDeletion.delete(oneByOne, id).getOrFail()
        assertEquals(oneByOne, group)
    }

    @Test
    fun `an overlay selected together with the base clip under it is deleted once`() {
        val t = scene()
        val result = GroupOps.delete(t, listOf("b", "r")).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(listOf("a", "c"), result.track("v1")!!.clips.map { it.id })
    }

    // endregion

    // region align

    @Test
    fun `align start and end line the clips up across lanes`() {
        val t = scene()
        val start = GroupOps.align(t, listOf("s", "q"), AlignEdge.START).getOrFail()
        assertLayout(start, "v3", at("s", 30, 40))
        assertLayout(start, "v2", at("p", 10, 30), at("q", 30, 50), at("r", 100, 110))
        val end = GroupOps.align(t, listOf("s", "q"), AlignEdge.END).getOrFail()
        assertLayout(end, "v3", at("s", 50, 60))
        assertLayout(end, "v2", at("p", 10, 30), at("q", 40, 60), at("r", 100, 110))
    }

    @Test
    fun `align refuses clips that would land on each other, base clips and single clips`() {
        val t = scene()
        assertEquals(EditError.Overlap("q"), GroupOps.align(t, listOf("p", "q"), AlignEdge.START).errorOrFail())
        assertTrue(reason(GroupOps.align(t, listOf("a", "p"), AlignEdge.START)).contains("base track"))
        assertTrue(reason(GroupOps.align(t, listOf("p"), AlignEdge.START)).contains("at least two"))
    }

    // endregion

    // region attributes

    private fun styled(id: String, start: Long, len: Long) = clip(id, start, len).copy(
        transform = ClipTransform(positionX = 40.0, scaleX = 1.5, scaleY = 1.5, opacity = 0.5),
        fx = ClipFx(listOf(Effect("e1", EffectType.SEPIA)), BlendMode.SCREEN),
        gainDb = -6.0,
    )

    @Test
    fun `paste attributes puts transform, effects and gain on five clips in one undo step`() {
        val clips = (0 until 5).map { clip("t$it", it * 30L, 20) }
        val t = timeline(track("v2", *clips.toTypedArray()), track("v1", clip("a", 0, 200), styled("src", 200, 40)))
        val attributes = ClipAttributes.of(checkNotNull(t.track("v1")!!.clip("src")))
        val history = EditHistory(t).execute(GroupPasteAttributes(attributes, clips.map { it.id })).getOrFail()
        for (clip in history.timeline.track("v2")!!.clips) {
            assertEquals(attributes.transform, clip.transform)
            assertEquals(attributes.fx, clip.fx)
            assertEquals(-6.0, clip.gainDb, 0.0)
        }
        assertEquals(t, history.undo().timeline)
    }

    @Test
    fun `paste attributes skips what a clip cannot take and fails when nothing applies`() {
        val title = clip("ti", 0, 30, asset = null).copy(title = TitleContent("Hi"))
        val t = timeline(
            track("t1", title, type = TrackType.TITLE),
            track("a1", clip("m", 0, 100), type = TrackType.AUDIO),
            track("v1", clip("a", 0, 100)),
        )
        val attributes = ClipAttributes.of(styled("x", 0, 10))
        val result = GroupOps.pasteAttributes(t, attributes, listOf("ti", "m")).getOrFail()
        assertEquals(attributes.transform, result.track("t1")!!.clips.single().transform)
        assertEquals(ClipTransform.IDENTITY, result.track("a1")!!.clips.single().transform) // audio has no picture
        assertEquals(-6.0, result.track("a1")!!.clips.single().gainDb, 0.0)
        assertEquals(0.0, result.track("t1")!!.clips.single().gainDb, 0.0) // titles have no sound
        assertTrue(reason(GroupOps.pasteAttributes(t, attributes, listOf("ti"), setOf(AttributeKind.AUDIO, AttributeKind.SPEED))).contains("None of the selected"))
    }

    @Test
    fun `pasting speed carries the ratio and ripples the base like a speed change`() {
        val fast = clip("f", 0, 100).let { TimelineOps.setSpeed(timeline(track("v1", it)), "f", 2, 1).getOrFail().track("v1")!!.clip("f")!! }
        val t = timeline(track("v1", clip("a", 0, 60), clip("b", 60, 60)))
        val result = GroupOps.pasteAttributes(t, ClipAttributes.of(fast), listOf("a"), setOf(AttributeKind.SPEED)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 30), at("b", 30, 90))
    }

    @Test
    fun `group speed gain and opacity act on the clips that can take them`() {
        val t = scene()
        val speed = GroupOps.setSpeed(t, listOf("p", "q"), 2, 1).getOrFail()
        // Like a single speed change on an overlay lane, each one closes the gap it leaves on that lane.
        assertLayout(speed, "v2", at("p", 10, 20), at("q", 30, 40), at("r", 80, 90))
        val gain = GroupOps.setGain(t, listOf("p", "m"), -12.0).getOrFail()
        assertEquals(-12.0, gain.track("v2")!!.clip("p")!!.gainDb, 0.0)
        assertEquals(-12.0, gain.track("a1")!!.clip("m")!!.gainDb, 0.0)
        val opacity = GroupOps.setOpacity(t, listOf("p", "s", "m"), 0.4).getOrFail()
        assertEquals(0.4, opacity.track("v2")!!.clip("p")!!.transform.opacity, 0.0)
        assertEquals(0.4, opacity.track("v3")!!.clip("s")!!.transform.opacity, 0.0)
        assertEquals(1.0, opacity.track("a1")!!.clip("m")!!.transform.opacity, 0.0) // audio is untouched
        assertTrue(GroupOps.setSpeed(t, listOf("p"), 20, 1).errorOrFail() is EditError.InvalidSpeed)
    }

    @Test
    fun `group opacity also sets the keyframes of an animated clip`() {
        val animated = clip("x", 0, 40).copy(
            keyframes = listOf(Keyframe(0, ClipTransform(opacity = 0.0)), Keyframe(30, ClipTransform(opacity = 1.0))),
        )
        val t = timeline(track("v2", animated), track("v1", clip("a", 0, 100)))
        val result = GroupOps.setOpacity(t, listOf("x"), 0.5).getOrFail()
        assertEquals(listOf(0.5, 0.5), result.track("v2")!!.clip("x")!!.keyframes.map { it.transform.opacity })
    }

    // endregion

    // region transitions

    private fun chain() = timeline(
        track(
            "v2",
            clip("c1", 0, 40, srcIn = 50),
            clip("c2", 40, 40, srcIn = 50),
            clip("c3", 80, 40, srcIn = 50),
            clip("c4", 130, 40, srcIn = 50),
        ),
        track("v1", clip("a", 0, 300)),
    )

    @Test
    fun `a crossfade is added after each selected clip that touches the next one`() {
        val lengths = mapOf("c1" to 500L, "c2" to 500L, "c3" to 500L, "c4" to 500L)
        val result = GroupOps.applyTransitions(chain(), listOf("c1", "c2", "c3", "c4"), 10, GroupTransition.BETWEEN) { lengths[it.id] }.getOrFail()
        assertEquals(setOf("c1" to "c2", "c2" to "c3"), result.transitions.map { it.fromClipId to it.toClipId }.toSet())
        assertTrue(result.transitions.all { it.durationFrames == 10L })
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `a transition is shortened to what the clips and their media allow, and resized when it exists`() {
        val limited = GroupOps.applyTransitions(chain(), listOf("c1"), 500, GroupTransition.BETWEEN) { 500L }.getOrFail()
        assertTrue(limited.transitions.single().durationFrames in 2..80)
        val again = GroupOps.applyTransitions(limited, listOf("c1"), 6, GroupTransition.BETWEEN) { 500L }.getOrFail()
        assertEquals(6L, again.transitions.single().durationFrames)
        assertEquals(1, again.transitions.size)
    }

    @Test
    fun `without a touching neighbour or room nothing is added`() {
        val failed = GroupOps.applyTransitions(chain(), listOf("c4"), 10, GroupTransition.BETWEEN)
        assertTrue(reason(failed).contains("touches a next clip"))
        assertTrue(GroupOps.applyTransitions(chain(), listOf("c1"), 1, GroupTransition.BETWEEN).errorOrFail() is EditError.InvalidTransition)
    }

    @Test
    fun `head and tail dissolves fade each picture clip in and out and keep the animation between`() {
        val animated = clip("x", 0, 60).copy(
            keyframes = listOf(Keyframe(20, ClipTransform(scaleX = 2.0, scaleY = 2.0)), Keyframe(40, ClipTransform(scaleX = 3.0, scaleY = 3.0))),
        )
        val t = timeline(track("v2", animated, clip("y", 100, 40)), track("a1", clip("m", 0, 100), type = TrackType.AUDIO), track("v1", clip("a", 0, 300)))
        val result = GroupOps.applyTransitions(t, listOf("x", "y", "m"), 10, GroupTransition.HEAD_AND_TAIL).getOrFail()
        val x = result.track("v2")!!.clip("x")!!
        assertEquals(listOf(0L, 10L, 20L, 40L, 49L, 59L), x.keyframes.map { it.frame })
        assertEquals(0.0, x.keyframes.first().transform.opacity, 0.0)
        assertEquals(0.0, x.keyframes.last().transform.opacity, 0.0)
        assertEquals(1.0, x.keyframes[1].transform.opacity, 0.0)
        assertTrue(result.track("a1")!!.clip("m")!!.keyframes.isEmpty())
        assertEquals(emptyList<String>(), result.invariantViolations())
        // Doing it again replaces the fade instead of stacking keyframes.
        val twice = GroupOps.applyTransitions(result, listOf("x"), 10, GroupTransition.HEAD_AND_TAIL).getOrFail()
        assertEquals(x.keyframes, twice.track("v2")!!.clip("x")!!.keyframes)
    }

    // endregion

    @Test
    fun `every group command is one exact undo step`() {
        val t = scene()
        val commands = listOf(
            GroupMove(listOf("p", "q"), 3),
            GroupDelete(listOf("p", "b")),
            GroupDuplicate(listOf("s")),
            GroupAlign(listOf("s", "q"), AlignEdge.START),
            GroupSetSpeed(listOf("p", "q"), 2, 1),
            GroupSetGain(listOf("p", "m"), -3.0),
            GroupSetOpacity(listOf("p", "s"), 0.5),
        )
        for (command in commands) {
            val history = EditHistory(t).execute(command).getOrFail()
            assertNotEquals(command.toString(), t, history.timeline)
            assertEquals(command.toString(), emptyList<String>(), history.timeline.invariantViolations())
            assertEquals(command.toString(), t, history.undo().timeline)
            assertEquals(command.toString(), history.timeline, history.undo().redo().timeline)
        }
    }
}
