package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detached and linked audio (SPECS 5.38): the commands, and how an edit of one clip of a pair applies to the other. */
class ClipLinksTest {

    // Base lane with two clips, audio lanes below. Clip "a" of a source with sound, a source range that starts at 100.
    private fun scene() = timeline(
        track("v2"),
        track("v1", clip("A", 0, 60, srcIn = 100), clip("B", 60, 60, srcIn = 0, asset = "b")),
        track("a1", type = TrackType.AUDIO),
    )

    private fun EditHistory.run(command: EditCommand): EditHistory = execute(command).getOrFail()

    private fun detached(history: EditHistory = EditHistory(scene()), id: String = "A", lane: String = "a1"): EditHistory =
        history.run(DetachAudio(id, "aud-$id", lane))

    private fun EditHistory.clipOf(id: String): Clip = checkNotNull(timeline.trackOfClip(id)?.clip(id)) { "no clip $id" }

    private fun EditHistory.assertValid() = assertTrue(timeline.invariantViolations().toString(), timeline.invariantViolations().isEmpty())

    // region detach

    @Test
    fun `detaching puts the same media range at the same frames on the audio lane and silences the video clip`() {
        val h = detached()
        val video = h.clipOf("A")
        val audio = h.clipOf("aud-A")
        assertTrue(video.audioDetached)
        assertFalse(audio.audioDetached)
        assertEquals(listOf(video.timelineStart, video.sourceIn, video.sourceOut), listOf(audio.timelineStart, audio.sourceIn, audio.sourceOut))
        assertEquals("a1", h.timeline.trackOfClip("aud-A")?.id)
        assertEquals(video.linkId, audio.linkId)
        assertTrue(video.linkId != null)
        h.assertValid()
    }

    @Test
    fun `the audio clip takes the video clip's volume, pan, fades and retime`() {
        val base = scene()
        val loud = base.copy(tracks = base.tracks.map { t ->
            if (t.id != "v1") t else t.copy(clips = t.clips.map {
                if (it.id == "A") it.copy(gainDb = -4.0, audio = ClipAudio(pan = 0.5, fadeInFrames = 5), retimedFrames = 30, reverse = true) else it
            })
        })
        val h = EditHistory(loud).run(DetachAudio("A", "aud", "a1"))
        val audio = h.clipOf("aud")
        assertEquals(-4.0, audio.gainDb, 0.0)
        assertEquals(0.5, audio.audio.pan, 0.0)
        assertEquals(5L, audio.audio.fadeInFrames)
        assertEquals(30L, audio.durationFrames)
        assertTrue(audio.reverse)
        // The video clip keeps its own settings, inert, so a restore brings the same mix back.
        assertEquals(-4.0, h.clipOf("A").gainDb, 0.0)
        h.assertValid()
    }

    @Test
    fun `a missing audio lane is created at the bottom`() {
        val t = timeline(track("v1", clip("A", 0, 60)))
        val h = EditHistory(t).run(DetachAudio("A", "aud", "track-a1"))
        assertEquals(listOf("v1", "track-a1"), h.timeline.tracks.map { it.id })
        assertEquals(TrackType.AUDIO, h.timeline.track("track-a1")?.type)
        h.assertValid()
    }

    @Test
    fun `the first audio lane with room is chosen`() {
        val t = timeline(
            track("v1", clip("A", 0, 60), clip("B", 60, 60)),
            track("a1", clip("m", 10, 20, asset = "m"), type = TrackType.AUDIO),
            track("a2", type = TrackType.AUDIO),
        )
        val a = t.trackOfClip("A")!!.clip("A")!!
        assertEquals("a2", ClipLinks.audioTrackFor(t, a))
        val b = t.trackOfClip("B")!!.clip("B")!!
        assertEquals("a1", ClipLinks.audioTrackFor(t, b))
        assertNull(ClipLinks.audioTrackFor(timeline(track("v1", clip("A", 0, 60))), a))
    }

    @Test
    fun `detach is refused for audio clips, titles-free lanes that overlap, and clips already detached`() {
        val h = detached()
        assertTrue(DetachAudio("A", "again", "a1").apply(h.timeline) is EditResult.Failure)
        assertTrue(DetachAudio("aud-A", "x", "a1").apply(h.timeline) is EditResult.Failure)
        val busy = timeline(
            track("v1", clip("A", 0, 60)),
            track("a1", clip("m", 30, 60, asset = "m"), type = TrackType.AUDIO),
        )
        assertEquals(EditError.Overlap("m"), DetachAudio("A", "aud", "a1").apply(busy).errorOrFail())
        assertEquals(EditError.TrackTypeMismatch("aud", "v1"), DetachAudio("A", "aud", "v1").apply(busy).errorOrFail())
        assertEquals(EditError.ClipNotFound("zz"), DetachAudio("zz", "aud", "a1").apply(busy).errorOrFail())
    }

    @Test
    fun `detach and undo are one step each`() {
        val start = EditHistory(scene())
        val h = detached(start)
        assertEquals(1, h.undoDepth)
        assertEquals(scene(), h.undo().timeline)
        assertEquals(h.timeline, h.undo().redo().timeline)
    }

    // endregion

    // region linked edits

    @Test
    fun `moving the video clip moves its audio, as one undo step`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val moved = h.run(EditCommand.Move("A", f(100)))
        assertEquals(100L, moved.clipOf("A").timelineStart.value)
        assertEquals(100L, moved.clipOf("aud-A").timelineStart.value)
        moved.assertValid()
        assertEquals(h.timeline, moved.undo().timeline)
    }

    @Test
    fun `moving the audio clip moves the video clip`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val moved = h.run(EditCommand.Move("aud-A", f(25)))
        assertEquals(25L, moved.clipOf("A").timelineStart.value)
        assertEquals(25L, moved.clipOf("aud-A").timelineStart.value)
    }

    @Test
    fun `a move whose audio would land on another audio clip fails and changes nothing`() {
        val t = timeline(
            track("v1", clip("A", 0, 60)),
            track("a1", clip("m", 100, 30, asset = "m"), type = TrackType.AUDIO),
            track("a2", type = TrackType.AUDIO),
        )
        val h = EditHistory(t).run(DetachAudio("A", "aud", "a1"))
        val failure = h.execute(EditCommand.Move("A", f(90)))
        assertEquals(EditError.Overlap("m"), failure.errorOrFail())
        assertEquals(0L, h.clipOf("A").timelineStart.value)
    }

    @Test
    fun `a base reorder moves each clip's audio with it`() {
        val t = timeline(
            track("v1", clip("A", 0, 60), clip("B", 60, 60, asset = "b")),
            track("a1", type = TrackType.AUDIO),
        )
        val h = EditHistory(t).run(DetachAudio("A", "aud-A", "a1")).run(DetachAudio("B", "aud-B", "a1"))
        val swapped = h.run(EditCommand.MoveClip("A", f(100)))
        swapped.assertValid()
        assertEquals(listOf("B" to 0L, "A" to 60L), swapped.timeline.track("v1")!!.clips.map { it.id to it.timelineStart.value })
        assertEquals(swapped.clipOf("A").timelineStart, swapped.clipOf("aud-A").timelineStart)
        assertEquals(swapped.clipOf("B").timelineStart, swapped.clipOf("aud-B").timelineStart)
        assertEquals(h.timeline, swapped.undo().timeline)
    }

    @Test
    fun `trimming either edge of the video clip trims the audio the same way`() {
        val lanes = timeline(track("v2", clip("A", 10, 60, srcIn = 100)), track("v1", clip("base", 0, 300, asset = "b")), track("a1", type = TrackType.AUDIO))
        val h = detached(EditHistory(lanes))
        fun EditHistory.shape(id: String) = clipOf(id).let { listOf(it.timelineStart.value, it.timelineEnd.value, it.sourceIn.value, it.sourceOut.value) }
        val start = h.run(EditCommand.TrimClip("A", TrimEdge.START, f(25)))
        assertEquals(listOf(25L, 70L, 115L, 160L), start.shape("A"))
        assertEquals(listOf(25L, 70L, 115L, 160L), start.shape("aud-A"))
        val end = start.run(EditCommand.TrimClip("A", TrimEdge.END, f(60)))
        assertEquals(listOf(25L, 60L, 115L, 150L), end.shape("aud-A"))
        end.assertValid()
    }

    @Test
    fun `a base trim moves the clip back to its neighbour and the audio does the same`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60, srcIn = 100), clip("B", 60, 60, asset = "b")), track("a1", type = TrackType.AUDIO))))
        val trimmed = h.run(EditCommand.TrimClip("A", TrimEdge.START, f(20)))
        trimmed.assertValid()
        val video = trimmed.clipOf("A")
        val audio = trimmed.clipOf("aud-A")
        assertEquals(listOf(video.timelineStart, video.timelineEnd, video.sourceIn, video.sourceOut), listOf(audio.timelineStart, audio.timelineEnd, audio.sourceIn, audio.sourceOut))
        assertEquals(40L, video.durationFrames)
        assertEquals(120L, video.sourceIn.value)
    }

    @Test
    fun `trimming the audio clip trims the video clip and extending works too`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 10, 60, srcIn = 100)), track("a1", type = TrackType.AUDIO))))
        val shorter = h.run(EditCommand.Trim("aud-A", TrimEdge.END, f(40)))
        assertEquals(40L, shorter.clipOf("A").timelineEnd.value)
        val longer = shorter.run(EditCommand.Trim("aud-A", TrimEdge.START, f(5)))
        assertEquals(5L, longer.clipOf("A").timelineStart.value)
        assertEquals(95L, longer.clipOf("A").sourceIn.value)
        longer.assertValid()
    }

    @Test
    fun `a trim that the audio lane cannot follow fails`() {
        val t = timeline(
            track("v1", clip("A", 0, 60)),
            track("a1", clip("m", 70, 30, asset = "m"), type = TrackType.AUDIO),
        )
        val h = EditHistory(t).run(DetachAudio("A", "aud", "a1"))
        assertEquals(EditError.Overlap("m"), h.execute(EditCommand.Trim("A", TrimEdge.END, f(80))).errorOrFail())
    }

    @Test
    fun `splitting the video clip splits the audio at the same frame and links each half to its own audio`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val cut = h.run(EditCommand.Split("v1", f(25), "A~x"))
        cut.assertValid()
        assertEquals(listOf("aud-A" to 0L, "aud-A~x" to 25L), cut.timeline.track("a1")!!.clips.map { it.id to it.timelineStart.value })
        assertEquals(cut.clipOf("A").linkId, cut.clipOf("aud-A").linkId)
        assertEquals(cut.clipOf("A~x").linkId, cut.clipOf("aud-A~x").linkId)
        assertTrue(cut.clipOf("A").linkId != cut.clipOf("A~x").linkId)
        assertTrue(cut.clipOf("A~x").audioDetached)
        assertEquals(h.timeline, cut.undo().timeline)
    }

    @Test
    fun `splitting the audio clip splits the video clip`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val cut = h.run(EditCommand.Split("a1", f(40), "aud-A~y"))
        cut.assertValid()
        assertEquals(listOf(0L to 40L, 40L to 60L), cut.timeline.track("v1")!!.clips.map { it.timelineStart.value to it.timelineEnd.value })
        assertEquals(cut.clipOf("aud-A~y").linkId, cut.timeline.track("v1")!!.clips.last().linkId)
    }

    @Test
    fun `splitting both clips of a pair in one batch does not cut anything twice`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val cut = h.run(EditCommand.Batch(listOf(EditCommand.Split("v1", f(30), "A~x"), EditCommand.Split("a1", f(30), "aud-A~x"))))
        cut.assertValid()
        assertEquals(2, cut.timeline.track("v1")!!.clips.size)
        assertEquals(2, cut.timeline.track("a1")!!.clips.size)
        assertEquals(cut.clipOf("A~x").linkId, cut.clipOf("aud-A~x").linkId)
        assertTrue(cut.clipOf("A").linkId != cut.clipOf("A~x").linkId)
    }

    @Test
    fun `a cut at the first or last frame is refused for the pair as for any clip`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        assertEquals(EditError.SplitOutsideClip, h.execute(EditCommand.Split("v1", f(0), "x")).errorOrFail())
        assertEquals(EditError.SplitOutsideClip, h.execute(EditCommand.Split("v1", f(60), "x")).errorOrFail())
    }

    @Test
    fun `deleting a base video clip deletes its audio and closes the gap on both lanes`() {
        val t = timeline(track("v1", clip("A", 0, 60), clip("B", 60, 60, asset = "b")), track("a1", type = TrackType.AUDIO))
        val h = EditHistory(t).run(DetachAudio("A", "aud-A", "a1")).run(DetachAudio("B", "aud-B", "a1"))
        val gone = h.run(EditCommand.DeleteClip("A"))
        gone.assertValid()
        assertEquals(listOf("B" to 0L), gone.timeline.track("v1")!!.clips.map { it.id to it.timelineStart.value })
        assertEquals(listOf("aud-B" to 0L), gone.timeline.track("a1")!!.clips.map { it.id to it.timelineStart.value })
        assertEquals(h.timeline, gone.undo().timeline)
    }

    @Test
    fun `deleting an overlay video clip deletes its audio and leaves a gap`() {
        val t = timeline(
            track("v2", clip("O", 10, 30, asset = "o"), clip("P", 60, 20, asset = "p")),
            track("v1", clip("A", 0, 100)),
            track("a1", clip("m", 70, 10, asset = "m"), type = TrackType.AUDIO),
        )
        val h = EditHistory(t).run(DetachAudio("O", "aud-O", "a1"))
        val gone = h.run(EditCommand.DeleteClip("O"))
        gone.assertValid()
        assertEquals(listOf("P"), gone.timeline.track("v2")!!.clips.map { it.id })
        assertEquals(listOf("m"), gone.timeline.track("a1")!!.clips.map { it.id })
    }

    @Test
    fun `deleting the linked audio keeps the video, silent and on its own`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val gone = h.run(EditCommand.DeleteClip("aud-A"))
        gone.assertValid()
        assertNull(gone.timeline.trackOfClip("aud-A"))
        val video = gone.clipOf("A")
        assertTrue(video.audioDetached)
        assertNull(video.linkId)
        assertTrue(gone.timeline.renderClips().single().soundDetached)
        assertEquals(h.timeline, gone.undo().timeline)
    }

    @Test
    fun `rippling an earlier base clip shifts the later clips' audio with them`() {
        val t = timeline(
            track("v1", clip("A", 0, 40), clip("B", 40, 40, asset = "b"), clip("C", 80, 40, asset = "c")),
            track("a1", type = TrackType.AUDIO),
        )
        val h = EditHistory(t).run(DetachAudio("C", "aud-C", "a1"))
        val rippled = h.run(EditCommand.TrimClip("A", TrimEdge.END, f(25)))
        rippled.assertValid()
        assertEquals(65L, rippled.clipOf("C").timelineStart.value)
        assertEquals(65L, rippled.clipOf("aud-C").timelineStart.value)
        val lengthened = h.run(EditCommand.TrimClip("A", TrimEdge.END, f(55), sourceLength = 500))
        assertEquals(95L, lengthened.clipOf("aud-C").timelineStart.value)
        assertEquals(lengthened.clipOf("C").timelineStart, lengthened.clipOf("aud-C").timelineStart)
    }

    @Test
    fun `a speed change of the video clip retimes its audio`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val fast = h.run(EditCommand.SetSpeed("A", 2, 1))
        fast.assertValid()
        assertEquals(30L, fast.clipOf("A").durationFrames)
        assertEquals(30L, fast.clipOf("aud-A").durationFrames)
        assertEquals(fast.clipOf("A").sourceOut, fast.clipOf("aud-A").sourceOut)
    }

    @Test
    fun `group moves of one clip of a pair carry the other, and of both clips leave them as they are`() {
        val overlay = timeline(track("v2", clip("A", 0, 60)), track("v1", clip("base", 0, 200, asset = "b")), track("a1", type = TrackType.AUDIO))
        val h = detached(EditHistory(overlay))
        val one = h.run(GroupMove(listOf("aud-A"), 15))
        assertEquals(15L, one.clipOf("A").timelineStart.value)
        val both = h.run(GroupMove(listOf("aud-A", "A"), 15))
        assertEquals(15L, both.clipOf("A").timelineStart.value)
        assertEquals(15L, both.clipOf("aud-A").timelineStart.value)
        both.assertValid()
    }

    @Test
    fun `duplicating a pair makes a new pair, duplicating the video alone leaves a silent unlinked copy`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val pair = h.run(GroupDuplicate(listOf("A", "aud-A")))
        pair.assertValid()
        val links = pair.timeline.tracks.flatMap { it.clips }.mapNotNull { it.linkId }
        assertEquals(4, links.size)
        assertEquals(2, links.toSet().size)
        val alone = h.run(GroupDuplicate(listOf("A")))
        alone.assertValid()
        assertEquals(1, alone.timeline.tracks.flatMap { it.clips }.mapNotNull { it.linkId }.toSet().size)
        assertTrue(alone.timeline.track("v1")!!.clips.all { it.audioDetached })
    }

    // endregion

    // region unlink, relink, restore

    @Test
    fun `unlinked clips are independent`() {
        val linked = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val h = linked.run(UnlinkClip("aud-A"))
        assertNull(h.clipOf("A").linkId)
        assertNull(h.clipOf("aud-A").linkId)
        assertTrue(h.clipOf("A").audioDetached)
        val moved = h.run(EditCommand.Move("aud-A", f(30)))
        assertEquals(0L, moved.clipOf("A").timelineStart.value)
        val cut = moved.run(EditCommand.Split("a1", f(50), "aud-A~p"))
        assertEquals(1, cut.timeline.track("v1")!!.clips.size)
        val piece = cut.run(EditCommand.DeleteClip("aud-A"))
        assertEquals(listOf("aud-A~p"), piece.timeline.track("a1")!!.clips.map { it.id })
        assertTrue(piece.clipOf("A").audioDetached)
        piece.assertValid()
        assertTrue(UnlinkClip("A").apply(h.timeline) is EditResult.Failure)
    }

    @Test
    fun `relinking joins a video clip with an audio clip of the same media`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO)))).run(UnlinkClip("A"))
        val again = h.run(RelinkClips("A", "aud-A", realign = false))
        assertTrue(again.clipOf("A").linkId != null)
        assertEquals(again.clipOf("A").linkId, again.clipOf("aud-A").linkId)
        val moved = again.run(EditCommand.Move("A", f(10)))
        assertEquals(10L, moved.clipOf("aud-A").timelineStart.value)
        again.assertValid()
    }

    @Test
    fun `relink refuses different media and the wrong kinds of clip`() {
        val t = timeline(
            track("v1", clip("A", 0, 60)),
            track("a1", clip("m", 0, 60, asset = "other"), type = TrackType.AUDIO),
        )
        assertTrue(RelinkClips("A", "m", false).apply(t) is EditResult.Failure)
        assertTrue(RelinkClips("m", "A", false).apply(t) is EditResult.Failure)
        assertEquals(EditError.ClipNotFound("zz"), RelinkClips("A", "zz", false).apply(t).errorOrFail())
    }

    @Test
    fun `the sync offset is how late the audio plays the same source frame`() {
        val video = clip("A", 100, 60, srcIn = 20)
        assertEquals(0L, ClipLinks.syncOffset(video, clip("m", 100, 60, srcIn = 20)))
        assertEquals(7L, ClipLinks.syncOffset(video, clip("m", 107, 60, srcIn = 20)))
        // A piece cut from the audio plays the same source frames at the same time: still in sync.
        assertEquals(0L, ClipLinks.syncOffset(video, clip("m", 130, 20, srcIn = 50)))
        assertEquals(-5L, ClipLinks.syncOffset(video, clip("m", 125, 20, srcIn = 50)))
        assertNull(ClipLinks.syncOffset(video, clip("m", 100, 60, srcIn = 20, asset = "zz")))
        assertNull(ClipLinks.syncOffset(video, clip("m", 100, 60, srcIn = 20).copy(reverse = true)))
        val slow = video.copy(retimedFrames = 120)
        assertEquals(0L, ClipLinks.syncOffset(slow, clip("m", 100, 30, srcIn = 20).copy(retimedFrames = 60)))
    }

    @Test
    fun `relink with realign moves the audio back into sync, without it the offset stays`() {
        val t = timeline(
            track("v1", clip("A", 0, 60, srcIn = 100)),
            track("a1", clip("m", 12, 60, srcIn = 100), type = TrackType.AUDIO),
        )
        val info = ClipLinks.infoFor(t, "A", assetHasAudio = true)!!
        assertEquals("m", info.relinkCandidateId)
        assertEquals(12L, info.offsetFrames)
        assertTrue(info.canRelink)
        val kept = EditHistory(t).run(RelinkClips("A", "m", realign = false))
        assertEquals(12L, kept.clipOf("m").timelineStart.value)
        assertEquals(12L, ClipLinks.infoFor(kept.timeline, "m", true)!!.offsetFrames)
        val aligned = EditHistory(t).run(RelinkClips("A", "m", realign = true))
        assertEquals(0L, aligned.clipOf("m").timelineStart.value)
        assertEquals(0L, ClipLinks.infoFor(aligned.timeline, "A", true)!!.offsetFrames)
        assertTrue(aligned.clipOf("A").audioDetached)
        aligned.assertValid()
        // The audio cannot move before frame 0 or onto another clip.
        val early = timeline(track("v1", clip("A", 0, 60, srcIn = 100)), track("a1", clip("m", 0, 60, srcIn = 90), type = TrackType.AUDIO))
        assertEquals(EditError.NegativeStart, RelinkClips("A", "m", true).apply(early).errorOrFail())
    }

    @Test
    fun `a linked pair moved apart by an unlinked move shows its offset`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO)))).run(UnlinkClip("A"))
        val off = h.run(EditCommand.Move("aud-A", f(9)))
        val info = ClipLinks.infoFor(off.timeline, "aud-A", true)!!
        assertEquals(9L, info.offsetFrames)
        assertEquals("A", info.relinkCandidateId)
    }

    @Test
    fun `restoring the embedded audio removes the linked audio clip and unmutes the video`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val back = h.run(RestoreEmbeddedAudio("A"))
        back.assertValid()
        assertFalse(back.clipOf("A").audioDetached)
        assertNull(back.clipOf("A").linkId)
        assertNull(back.timeline.trackOfClip("aud-A"))
        assertFalse(back.timeline.renderClips().single().soundDetached)
        assertEquals(h.timeline, back.undo().timeline)
    }

    @Test
    fun `restoring after the audio was deleted just unmutes, and an unlinked audio clip stays`() {
        val deleted = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO)))).run(EditCommand.DeleteClip("aud-A"))
        assertFalse(deleted.run(RestoreEmbeddedAudio("A")).clipOf("A").audioDetached)
        val unlinked = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO)))).run(UnlinkClip("A"))
        val back = unlinked.run(RestoreEmbeddedAudio("A"))
        assertFalse(back.clipOf("A").audioDetached)
        assertTrue(back.timeline.trackOfClip("aud-A") != null)
        assertTrue(RestoreEmbeddedAudio("A").apply(back.timeline) is EditResult.Failure)
    }

    @Test
    fun `restoring and detaching again works`() {
        val h = detached(EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", type = TrackType.AUDIO))))
        val again = h.run(RestoreEmbeddedAudio("A")).run(DetachAudio("A", "aud-2", "a1"))
        again.assertValid()
        assertTrue(again.clipOf("A").audioDetached)
        assertEquals("aud-2", ClipLinks.partnerOf(again.timeline, again.clipOf("A"))?.second?.id)
    }

    // endregion

    @Test
    fun `info offers detach only for a video clip with sound that is not detached`() {
        val t = scene()
        assertTrue(ClipLinks.infoFor(t, "A", assetHasAudio = true)!!.canDetach)
        assertFalse(ClipLinks.infoFor(t, "A", assetHasAudio = false)!!.canDetach)
        assertFalse(ClipLinks.infoFor(t, "A", false)!!.relevant)
        val h = detached()
        val info = ClipLinks.infoFor(h.timeline, "A", true)!!
        assertFalse(info.canDetach)
        assertTrue(info.canRestore)
        assertTrue(info.linked)
        assertEquals("aud-A", info.partnerId)
        assertFalse(ClipLinks.infoFor(h.timeline, "aud-A", true)!!.isVideo)
        assertNull(ClipLinks.infoFor(h.timeline, "nope", true))
    }

    @Test
    fun `the plan keeps a detached video clip out of the mix and the audio clip in it`() {
        val h = detached()
        val plan = h.timeline.renderClips()
        assertTrue(plan.first { it.clipId == "A" }.soundDetached)
        assertFalse(plan.first { it.clipId == "B" }.soundDetached)
        assertFalse(plan.first { it.clipId == "aud-A" }.soundDetached)
    }

    @Test
    fun `the invariants reject a link that does not join a video and an audio clip of one media`() {
        val base = detached().timeline
        val bad = base.copy(tracks = base.tracks.map { t ->
            if (t.id == "v1") t.copy(clips = t.clips.map { if (it.id == "B") it.copy(linkId = it.linkId ?: base.trackOfClip("A")!!.clip("A")!!.linkId) else it }) else t
        })
        assertTrue(bad.invariantViolations().isNotEmpty())
        val orphan = base.copy(tracks = base.tracks.map { t -> t.copy(clips = t.clips.filter { it.id != "aud-A" }) })
        assertTrue(orphan.invariantViolations().any { it.contains("joins 1 clips") })
    }
}
