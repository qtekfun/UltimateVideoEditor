package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Put video audio on an audio track" (SPECS 5.38): a placement and the detach are one command and one undo step. */
class PlaceWithDetachedAudioTest {

    private fun scene() = EditHistory(
        timeline(
            track("v1", clip("A", 0, 60)),
            track("a1", type = TrackType.AUDIO),
        ),
    )

    private fun EditHistory.run(command: EditCommand) = execute(command).getOrFail()

    private fun placed(place: EditCommand, id: String = "N") = PlaceWithDetachedAudio(place, id, "aud-$id", "track-a1")

    private fun EditHistory.assertValid() = assertTrue(timeline.invariantViolations().toString(), timeline.invariantViolations().isEmpty())

    private fun EditHistory.clipOf(id: String): Clip = checkNotNull(timeline.trackOfClip(id)?.clip(id)) { "no clip $id" }

    @Test
    fun `an inserted clip arrives detached and linked on the first audio lane`() {
        val h = scene().run(placed(EditCommand.InsertBase(clip("N", 0, 30), f(60))))
        val video = h.clipOf("N")
        val audio = h.clipOf("aud-N")
        assertTrue(video.audioDetached)
        assertEquals(video.linkId, audio.linkId)
        assertNotNull(video.linkId)
        assertEquals("a1", h.timeline.trackOfClip("aud-N")?.id)
        assertEquals(60L, audio.timelineStart.value)
        assertEquals(30L, audio.durationFrames)
        h.assertValid()
    }

    @Test
    fun `the whole placement is one undo step`() {
        val before = scene()
        val undone = before.run(placed(EditCommand.InsertBase(clip("N", 0, 30), f(60)))).undo()
        assertEquals(before.timeline, undone.timeline)
        assertFalse(undone.canUndo)
    }

    @Test
    fun `an overwrite on an upper lane works the same`() {
        val base = EditHistory(timeline(track("v2"), track("v1", clip("A", 0, 100)), track("a1", type = TrackType.AUDIO)))
        val h = base.run(placed(EditCommand.Overwrite("v2", clip("N", 20, 30))))
        assertEquals("v2", h.timeline.trackOfClip("N")?.id)
        assertEquals("a1", h.timeline.trackOfClip("aud-N")?.id)
        h.assertValid()
    }

    @Test
    fun `a busy audio lane sends the new audio to the next lane with room, else to a new lane`() {
        val busy = EditHistory(
            timeline(track("v1", clip("A", 0, 60)), track("a1", clip("M", 0, 200, asset = "m"), type = TrackType.AUDIO), track("a2", type = TrackType.AUDIO)),
        )
        assertEquals("a2", busy.run(placed(EditCommand.InsertBase(clip("N", 0, 30), f(60)))).timeline.trackOfClip("aud-N")?.id)

        val full = EditHistory(timeline(track("v1", clip("A", 0, 60)), track("a1", clip("M", 0, 200, asset = "m"), type = TrackType.AUDIO)))
        val grown = full.run(placed(EditCommand.InsertBase(clip("N", 0, 30), f(60))))
        assertEquals("track-a1", grown.timeline.trackOfClip("aud-N")?.id)
        assertEquals(TrackType.AUDIO, grown.timeline.track("track-a1")?.type)
        grown.assertValid()
    }

    @Test
    fun `inserting before a detached pair shifts its audio and the new audio reuses the lane`() {
        val start = scene().run(DetachAudio("A", "aud-A", "a1"))
        val h = start.run(placed(EditCommand.InsertBase(clip("N", 0, 40), f(0))))
        assertEquals(40L, h.clipOf("A").timelineStart.value)
        assertEquals(40L, h.clipOf("aud-A").timelineStart.value)
        // The audio of the new clip sits at 0..40 on the lane that the shifted pair freed, not on a new lane.
        assertEquals("a1", h.timeline.trackOfClip("aud-N")?.id)
        assertEquals(1, h.timeline.tracks.count { it.type == TrackType.AUDIO })
        h.assertValid()
    }

    @Test
    fun `a failing placement is a failure and changes nothing`() {
        val result = scene().execute(placed(EditCommand.Overwrite("nope", clip("N", 0, 30))))
        assertTrue(result is EditResult.Failure)
    }

    @Test
    fun `the new pair then moves together`() {
        val h = scene().run(placed(EditCommand.InsertBase(clip("N", 0, 30), f(60)))).run(EditCommand.Move("N", f(90), null))
        assertEquals(90L, h.clipOf("aud-N").timelineStart.value)
    }
}
