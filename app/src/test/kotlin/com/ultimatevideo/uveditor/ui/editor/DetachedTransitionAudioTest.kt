package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.DetachAudio
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.UnlinkClip
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.audio.AudioClipSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transition crossfades the sound of the clips around the cut. The sound of a detached, linked video clip is a clip on an
 * audio lane, and it must crossfade exactly like an embedded sound does (SPECS 5.38): the mixer reads one list built from
 * the render plan, so equal specs mean an equal mix.
 */
class DetachedTransitionAudioTest {

    private val asset = MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    private fun withTransition(): Timeline {
        val base = timeline(
            track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50)),
            track("a1", type = TrackType.AUDIO),
            track("a2", type = TrackType.AUDIO),
        )
        return TimelineOps.addTransition(base, Transition("t", "A", "B", 10)).getOrFail()
    }

    private fun specs(tl: Timeline): List<AudioClipSpec> {
        val keys = KeyRegistry()
        val assets = KeyRegistry()
        return audioSnapshotOf(tl, listOf(asset), FrameRate(30, 1), keys::keyFor, assets::keyFor).clips.sortedBy { it.startFrame }
    }

    /** The parts of a spec that shape the sound at the cut (the clip key and the lane differ between the two cases). */
    private fun shape(s: AudioClipSpec) = listOf(
        s.startFrame, s.durationFrames, s.sourceInFrame, s.fadeInFrames, s.fadeOutFrames, s.userFadeInFrames, s.userFadeOutFrames,
    )

    private fun detach(history: EditHistory, video: String, lane: String) = history.execute(DetachAudio(video, "aud-$video", lane)).getOrFail()

    @Test
    fun `detached sound crossfades at a transition exactly like embedded sound`() {
        val embedded = specs(withTransition())
        assertEquals(2, embedded.size)

        val detached = specs(detach(detach(EditHistory(withTransition()), "A", "a1"), "B", "a2").timeline)

        assertEquals(embedded.map(::shape), detached.map(::shape))
        // The outgoing sound plays 5 frames past the cut and ramps down over 10 frames; the incoming one starts 5 early.
        assertEquals(listOf(0L, 105L, 0L, 10L), detached[0].let { listOf(it.startFrame, it.startFrame + it.durationFrames, it.fadeInFrames, it.fadeOutFrames) })
        assertEquals(listOf(95L, 45L, 10L, 0L), detached[1].let { listOf(it.startFrame, it.sourceInFrame, it.fadeInFrames, it.fadeOutFrames) })
    }

    @Test
    fun `the audio clips of both neighbours may share one lane`() {
        val detached = specs(detach(detach(EditHistory(withTransition()), "A", "a1"), "B", "a1").timeline)
        assertEquals(specs(withTransition()).map(::shape), detached.map(::shape))
    }

    @Test
    fun `the render plan extends the audio clips like the pictures`() {
        val plan = detach(detach(EditHistory(withTransition()), "A", "a1"), "B", "a2").timeline.renderClips().associateBy { it.clipId }
        assertEquals(0L to 105L, plan.getValue("aud-A").let { it.startFrame to it.endFrame })
        assertEquals(95L to 200L, plan.getValue("aud-B").let { it.startFrame to it.endFrame })
        assertEquals(RenderKind.AUDIO, plan.getValue("aud-B").kind)
        assertEquals(0L to 105L, plan.getValue("A").let { it.startFrame to it.endFrame })
        assertEquals(95L to 200L, plan.getValue("B").let { it.startFrame to it.endFrame })
    }

    @Test
    fun `one neighbour embedded and the other detached still crossfade together`() {
        val byStart = specs(detach(EditHistory(withTransition()), "B", "a1").timeline)
        assertEquals(0L, byStart[0].startFrame)
        assertEquals(10L, byStart[0].fadeOutFrames)
        assertEquals(95L, byStart[1].startFrame)
        assertEquals(10L, byStart[1].fadeInFrames)
    }

    @Test
    fun `an unlinked audio clip does not follow the transition`() {
        val h = detach(detach(EditHistory(withTransition()), "A", "a1"), "B", "a2")
            .execute(UnlinkClip("A")).getOrFail().execute(UnlinkClip("B")).getOrFail()
        val spec = specs(h.timeline)
        assertTrue(spec.all { it.fadeInFrames == 0L && it.fadeOutFrames == 0L })
        assertEquals(listOf(0L, 100L), spec.map { it.startFrame })
    }

    @Test
    fun `an audio clip whose edge is not at the cut keeps its own edge`() {
        // Linked audio that is 3 frames short at the end of A: it does not reach the cut, so it gets no outgoing ramp.
        val base = detach(detach(EditHistory(withTransition()), "A", "a1"), "B", "a2")
        val tl = base.timeline
        val a1 = checkNotNull(tl.track("a1"))
        val cropped = tl.withTrack(a1.withClips(a1.clips.map { it.copy(sourceOut = it.sourceOut - 3) }))
        val aud = specs(cropped).first()
        assertEquals(0L, aud.fadeOutFrames)
        assertEquals(97L, aud.durationFrames)
    }
}
