package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.AddCaptions
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.errorOrFail
import com.ultimatevideo.uveditor.domain.f
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddCaptionsTest {

    private val base = timeline(track("v1", clip("c1", 0, 300)))
    private val cues = listOf(
        CaptionCue(f(0), f(30), "Hello there."),
        CaptionCue(f(40), f(80), "General Kenobi."),
    )

    private fun clips(style: CaptionStyle = CaptionStyle.CLASSIC): List<com.ultimatevideo.uveditor.domain.Clip> {
        var n = 0
        return style.clipsFor(cues, 1080) { "id${n++}" }
    }

    @Test
    fun `captions become title clips with the style applied`() {
        val result = clips(CaptionStyle.POP)

        assertEquals(listOf("caption-id0", "caption-id1"), result.map { it.id })
        val first = result[0]
        assertEquals("Hello there.", first.title?.text)
        assertTrue(first.title?.outline == true)
        assertTrue(first.title?.bold == true)
        assertEquals(0.27 * 1080, first.transform.positionY, 1e-9)
        assertEquals(0.0, first.transform.positionX, 0.0)
        assertEquals(30L, first.durationFrames)
        assertEquals(0L, first.sourceIn.value)
        assertEquals(null, first.assetId)
    }

    @Test
    fun `adding captions creates a title track on top holding every cue`() {
        val command = AddCaptions(Track("track-t1", TrackType.TITLE), 0, clips())
        val result = command.apply(base).getOrFail()

        assertEquals(listOf("track-t1", "v1"), result.tracks.map { it.id })
        assertEquals(2, result.track("track-t1")?.clips?.size)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `all captions are one undo step`() {
        val history = EditHistory(base)
        val after = history.execute(AddCaptions(Track("track-t1", TrackType.TITLE), 0, clips())).getOrFail()

        assertEquals(2, after.timeline.track("track-t1")?.clips?.size)
        val undone = after.undo()
        assertEquals(base, undone.timeline)
        assertFalse(undone.canUndo)
        assertEquals(after.timeline, undone.redo().timeline)
    }

    @Test
    fun `a failing caption leaves the timeline untouched`() {
        val duplicate = clips() + clips()[0]
        val result = AddCaptions(Track("track-t1", TrackType.TITLE), 0, duplicate).apply(base)

        assertTrue(result is EditResult.Failure)
        assertTrue(result.errorOrFail() is EditError.DuplicateClipId)
        val history = EditHistory(base)
        assertTrue(history.execute(AddCaptions(Track("track-t1", TrackType.TITLE), 0, duplicate)) is EditResult.Failure)
    }

    @Test
    fun `an existing track id is refused`() {
        val result = AddCaptions(Track("v1", TrackType.TITLE), 0, clips()).apply(base)
        assertTrue(result.errorOrFail() is EditError.DuplicateTrackId)
    }

    @Test
    fun `styles are looked up by id with a safe default`() {
        assertEquals(CaptionStyle.POP, CaptionStyle.byId("pop"))
        assertEquals(CaptionStyle.CLASSIC, CaptionStyle.byId("nope"))
        assertEquals(CaptionStyle.ALL.size, CaptionStyle.ALL.map { it.id }.toSet().size)
    }
}
