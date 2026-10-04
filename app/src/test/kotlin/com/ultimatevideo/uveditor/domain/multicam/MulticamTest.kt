package com.ultimatevideo.uveditor.domain.multicam

import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.errorOrFail
import com.ultimatevideo.uveditor.domain.f
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticamTest {
    // Three cameras, 1000 frames of media each. B started 40 frames after A, C 25 frames before A.
    private val a = MulticamAngle("ang-a", "Cam A", "asset-a", offsetFrames = 0, durationFrames = 1000)
    private val b = MulticamAngle("ang-b", "Cam B", "asset-b", offsetFrames = 40, durationFrames = 1000)
    private val c = MulticamAngle("ang-c", "Cam C", "asset-c", offsetFrames = -25, durationFrames = 1000)

    private fun group(
        videoTrack: String = "v2",
        audioTrack: String? = "a1",
        start: Long = 100,
        length: Long = 600,
        inFrame: Long = 100,
    ) = MulticamClip(
        id = "g1", name = "Concert", angles = listOf(a, b, c), audioAngle = 0, videoTrackId = videoTrack, audioTrackId = audioTrack,
        startFrame = start, inFrame = inFrame, lengthFrames = length, cuts = listOf(AngleCut(0, 0)),
    )

    // Overlay lane on top, base below, audio lane under it.
    private fun scene() = timeline(
        track("v2"),
        track("v1", clip("base-1", 0, 100), clip("base-2", 100, 200)),
        track("a1", type = TrackType.AUDIO),
    )

    private fun Timeline.videoClips(id: String = "v2") = checkNotNull(track(id)).clips.filter { it.id.startsWith("mc-g1-v") }

    // region creating

    @Test
    fun `creating on a free overlay lane lays down one clip of the first angle and the audio clip`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        assertEquals(emptyList<String>(), t.invariantViolations())
        val video = t.videoClips().single()
        assertEquals("asset-a", video.assetId)
        assertEquals(f(100), video.timelineStart)
        assertEquals(f(100), video.sourceIn) // inFrame 100 minus A's offset 0
        assertEquals(f(700), video.sourceOut)
        assertEquals(ClipGain.MIN_DB, video.gainDb, 0.0) // silent: the sound is its own clip
        val audio = checkNotNull(t.track("a1")).clip("mc-g1-a")
        assertNotNull(audio)
        assertEquals("asset-a", audio!!.assetId)
        assertEquals(f(100), audio.timelineStart)
        assertEquals(600L, audio.durationFrames)
        assertEquals(1, t.multicams.size)
    }

    @Test
    fun `without an audio lane the pictures keep their own sound`() {
        val t = MulticamOps.create(scene(), group(audioTrack = null)).getOrFail()
        assertEquals(0.0, t.videoClips().single().gainDb, 0.0)
        assertNull(t.trackOfClip("mc-g1-a"))
    }

    @Test
    fun `creating on the base inserts at the nearest cut and the group follows where it landed`() {
        val t = MulticamOps.create(scene(), group(videoTrack = "v1", audioTrack = null, start = 110)).getOrFail()
        assertEquals(emptyList<String>(), t.invariantViolations())
        val clips = checkNotNull(t.track("v1")).clips
        assertEquals(listOf("base-1", "mc-g1-v0", "base-2"), clips.map { it.id })
        assertEquals(f(100), clips[1].timelineStart)
        assertEquals(f(700), clips[2].timelineStart) // base-2 rippled right by the 600 frames inserted
        assertEquals(100L, t.multicams.single().startFrame)
    }

    @Test
    fun `an occupied stretch is refused`() {
        val busy = scene().let { TimelineOps.overwrite(it, "v2", clip("other", 300, 50)).getOrFail() }
        assertTrue(MulticamOps.create(busy, group()).errorOrFail() is EditError.Overlap)
        val busyAudio = scene().let { TimelineOps.overwrite(it, "a1", clip("music", 200, 50)).getOrFail() }
        assertTrue(MulticamOps.create(busyAudio, group()).errorOrFail() is EditError.Overlap)
    }

    @Test
    fun `bad groups are refused`() {
        assertTrue(MulticamOps.create(scene(), group().copy(angles = listOf(a))).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.create(scene(), group().copy(angles = List(7) { a.copy(id = "x$it") })).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.create(scene(), group().copy(angles = listOf(a, a))).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.create(scene(), group().copy(audioAngle = 5)).errorOrFail() is EditError.InvalidClip)
        // Only the angle on screen has to cover its stretch: B (media from shared frame 40) is fine until someone cuts to it too early.
        val early = MulticamOps.create(scene(), group().copy(inFrame = 0)).getOrFail()
        assertTrue(MulticamOps.cutAt(early, "g1", 10, 1).errorOrFail() is EditError.InvalidClip)
        assertEquals(2, MulticamOps.cutAt(early, "g1", 60, 1).getOrFail().videoClips().size)
        // The angle on screen must cover its stretch: A has 1000 frames, the clip runs shared 500..1100.
        assertTrue(MulticamOps.create(scene(), group().copy(inFrame = 500)).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.create(scene(), group(videoTrack = "a1")).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.create(scene(), group(audioTrack = "v1")).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.TrackNotFound("zz"), MulticamOps.create(scene(), group(videoTrack = "zz")).errorOrFail())
    }

    // endregion

    // region cutting

    @Test
    fun `cutting to another angle splits the programme and maps source frames through the offsets`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", frame = 250, angle = 1).getOrFail()
        assertEquals(emptyList<String>(), cut.invariantViolations())
        val clips = cut.videoClips()
        assertEquals(2, clips.size)
        assertEquals(f(100), clips[0].timelineStart)
        assertEquals(f(350), clips[0].timelineEnd.let { FrameIndex(it.value) })
        val second = clips[1]
        assertEquals("asset-b", second.assetId)
        assertEquals(f(350), second.timelineStart) // 100 + 250
        assertEquals(f(310), second.sourceIn) // shared 100+250 minus B's offset 40
        assertEquals(f(310 + 350), second.sourceOut)
        assertEquals(listOf(AngleCut(0, 0), AngleCut(250, 1)), cut.multicam("g1")!!.cuts)
    }

    @Test
    fun `the audio clip does not change when the picture is cut`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", 250, 2).getOrFail()
        assertEquals(checkNotNull(t.track("a1")).clips, checkNotNull(cut.track("a1")).clips)
    }

    @Test
    fun `cutting to the angle already on screen changes nothing and a later cut overrides the frame`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        assertEquals(t, MulticamOps.cutAt(t, "g1", 200, 0).getOrFail())
        val one = MulticamOps.cutAt(t, "g1", 200, 1).getOrFail()
        val two = MulticamOps.cutAt(one, "g1", 200, 2).getOrFail()
        assertEquals(listOf(AngleCut(0, 0), AngleCut(200, 2)), two.multicam("g1")!!.cuts)
        // Switching back at a later frame creates a third stretch.
        val three = MulticamOps.cutAt(two, "g1", 400, 0).getOrFail()
        assertEquals(3, three.videoClips().size)
    }

    @Test
    fun `a cut that restores the previous angle merges the stretches`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val one = MulticamOps.cutAt(t, "g1", 200, 1).getOrFail()
        val back = MulticamOps.cutAt(one, "g1", 200, 0).getOrFail()
        assertEquals(1, back.videoClips().size)
        assertEquals(listOf(AngleCut(0, 0)), back.multicam("g1")!!.cuts)
    }

    @Test
    fun `cuts the angle cannot cover are refused and leave the timeline alone`() {
        val short = group().copy(angles = listOf(a, b.copy(durationFrames = 450), c))
        val t = MulticamOps.create(scene(), short).getOrFail()
        // B covers shared frames 40..490, the clip runs shared 100..700, so B can only be shown until group frame 390.
        assertTrue(MulticamOps.cutAt(t, "g1", 100, 1).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.cutAt(t, "g1", -1, 1).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.cutAt(t, "g1", 600, 1).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.cutAt(t, "g1", 10, 9).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `a whole recording is one undo step and the cuts land in order`() {
        val history = EditHistory(scene()).execute(EditCommand.CreateMulticam(group())).getOrFail()
        val recorded = history.execute(
            EditCommand.RecordMulticam("g1", listOf(AngleCut(300, 2), AngleCut(120, 1), AngleCut(500, 0))),
        ).getOrFail()
        assertEquals(
            listOf(AngleCut(0, 0), AngleCut(120, 1), AngleCut(300, 2), AngleCut(500, 0)),
            recorded.timeline.multicam("g1")!!.cuts,
        )
        assertEquals(4, recorded.timeline.videoClips().size)
        assertEquals(history.timeline, recorded.undo().timeline)
        assertEquals(recorded.timeline, recorded.undo().redo().timeline)
    }

    @Test
    fun `removing a cut hands the stretch to the angle before it`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.record(t, "g1", listOf(AngleCut(100, 1), AngleCut(300, 2))).getOrFail()
        val removed = MulticamOps.removeCut(cut, "g1", 300).getOrFail()
        assertEquals(listOf(AngleCut(0, 0), AngleCut(100, 1)), removed.multicam("g1")!!.cuts)
        assertTrue(MulticamOps.removeCut(cut, "g1", 0).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.removeCut(cut, "g1", 77).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `the angle on screen at any frame comes from the cuts`() {
        val g = group().copy(cuts = listOf(AngleCut(0, 0), AngleCut(100, 2), AngleCut(300, 1)))
        assertEquals(0, g.angleAt(0))
        assertEquals(0, g.angleAt(99))
        assertEquals(2, g.angleAt(100))
        assertEquals(1, g.angleAt(599))
    }

    // endregion

    // region nudging, audio angle and flatten

    @Test
    fun `nudging an angle shifts the source it reads`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", 250, 1).getOrFail()
        val nudged = MulticamOps.nudge(cut, "g1", angleIndex = 1, deltaFrames = 3).getOrFail()
        assertEquals(f(310 - 3), nudged.videoClips()[1].sourceIn)
        assertEquals(43L, nudged.multicam("g1")!!.angles[1].offsetFrames)
        // Nudging the audio angle moves the audio source too.
        val audioNudge = MulticamOps.nudge(cut, "g1", 0, 5).getOrFail()
        assertEquals(f(100 - 5), checkNotNull(audioNudge.track("a1")).clip("mc-g1-a")!!.sourceIn)
    }

    @Test
    fun `a nudge that uncovers part of a cut is refused`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", 250, 1).getOrFail()
        assertTrue(MulticamOps.nudge(cut, "g1", 1, 600).errorOrFail() is EditError.InvalidClip)
        assertTrue(MulticamOps.nudge(cut, "g1", 8, 1).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `applying sync offsets sets every angle at once`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val synced = MulticamOps.setOffsets(t, "g1", listOf(0, 41, -26)).getOrFail()
        assertEquals(listOf(0L, 41L, -26L), synced.multicam("g1")!!.angles.map { it.offsetFrames })
        assertTrue(MulticamOps.setOffsets(t, "g1", listOf(0, 1)).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `choosing another audio angle swaps the audio clip source only`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val swapped = MulticamOps.setAudioAngle(t, "g1", 2).getOrFail()
        val audio = checkNotNull(swapped.track("a1")).clip("mc-g1-a")!!
        assertEquals("asset-c", audio.assetId)
        assertEquals(f(100 + 25), audio.sourceIn) // inFrame minus C's offset of -25
        assertEquals(t.videoClips(), swapped.videoClips())
        assertTrue(MulticamOps.setAudioAngle(MulticamOps.create(scene(), group(audioTrack = null)).getOrFail(), "g1", 1).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `flatten keeps the clips as they are and forgets the group`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.record(t, "g1", listOf(AngleCut(100, 1), AngleCut(300, 2))).getOrFail()
        val flat = MulticamOps.flatten(cut, "g1").getOrFail()
        assertTrue(flat.multicams.isEmpty())
        assertEquals(cut.tracks, flat.tracks)
        assertEquals(emptyList<String>(), flat.invariantViolations())
        assertTrue(MulticamOps.flatten(flat, "g1").errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `flatten equivalence the realised clips play what the cuts say frame by frame`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.record(t, "g1", listOf(AngleCut(90, 1), AngleCut(333, 2), AngleCut(480, 0))).getOrFail()
        val g = cut.multicam("g1")!!
        val clips = cut.videoClips()
        for (frame in listOf(0L, 89, 90, 332, 333, 479, 480, 599)) {
            val timelineFrame = g.startFrame + frame
            val shown = clips.single { it.timelineStart.value <= timelineFrame && timelineFrame < it.timelineEnd.value }
            val angle = g.angles[g.angleAt(frame)]
            assertEquals(angle.assetId, shown.assetId)
            // The source frame it reads is the shared time minus the angle's offset.
            assertEquals(g.inFrame + frame - angle.offsetFrames, shown.sourceIn.value + (timelineFrame - shown.timelineStart.value))
        }
    }

    // endregion

    // region following edits

    @Test
    fun `moving the whole group keeps it and updates where it starts`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", 250, 1).getOrFail()
        // The user drags both realised pieces and the audio together by 200 frames.
        val shifted = cut.copy(
            tracks = cut.tracks.map { track ->
                track.copy(clips = track.clips.map { if (it.id.startsWith("mc-g1-")) it.copy(timelineStart = it.timelineStart + 200) else it })
            },
        ).pruned() // every real edit ends with this; the hand-built shift needs it too
        assertEquals(300L, shifted.multicam("g1")?.startFrame)
        // Cutting afterwards still works from the new place.
        val again = MulticamOps.cutAt(shifted, "g1", 400, 2).getOrFail()
        assertEquals(f(300 + 400), again.videoClips().last().timelineStart)
    }

    @Test
    fun `editing one realised clip on its own flattens the group instead of lying about it`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val cut = MulticamOps.cutAt(t, "g1", 250, 1).getOrFail()
        val split = TimelineOps.split(cut, "v2", f(200), "extra").getOrFail()
        assertTrue(split.multicams.isEmpty())
        assertEquals(emptyList<String>(), split.invariantViolations())
        // Deleting a piece does the same.
        val deleted = TimelineOps.rippleDelete(cut, "mc-g1-v1").getOrFail()
        assertTrue(deleted.multicams.isEmpty())
    }

    @Test
    fun `styling a realised clip keeps the group`() {
        val t = MulticamOps.create(scene(), group()).getOrFail()
        val styled = TimelineOps.setGain(t, "mc-g1-v0", -6.0).getOrFail()
        assertEquals(1, styled.multicams.size)
    }

    @Test
    fun `a base multicam stays gap free whatever the cuts`() {
        val t = MulticamOps.create(scene(), group(videoTrack = "v1", audioTrack = null, start = 100)).getOrFail()
        val cut = MulticamOps.record(t, "g1", listOf(AngleCut(100, 1), AngleCut(250, 2))).getOrFail()
        assertEquals(emptyList<String>(), cut.invariantViolations())
        val base = checkNotNull(cut.track("v1")).clips
        for ((left, right) in base.zipWithNext()) assertEquals(left.timelineEnd, right.timelineStart)
        // 100 + 200 frames of the original base plus the 600-frame programme, tiled without a hole.
        assertEquals(0L, base.first().timelineStart.value)
        assertEquals(900L, base.last().timelineEnd.value)
        assertEquals(600L, base.filter { it.id.startsWith("mc-g1-v") }.sumOf { it.durationFrames })
    }

    // endregion

    // region viewer budget

    @Test
    fun `only the active angle gets a full decoder and proxies fill the spare ones`() {
        val feeds = MulticamPlanner.plan(angleCount = 4, active = 1, proxyReady = setOf(0, 2, 3), maxDecoders = 3)
        assertEquals(listOf(AngleFeed.PROXY, AngleFeed.FULL, AngleFeed.PROXY, AngleFeed.STILL), feeds)
    }

    @Test
    fun `a device with one decoder shows every other angle as a still`() {
        val feeds = MulticamPlanner.plan(angleCount = 3, active = 0, proxyReady = setOf(1, 2), maxDecoders = 1)
        assertEquals(listOf(AngleFeed.FULL, AngleFeed.STILL, AngleFeed.STILL), feeds)
    }

    @Test
    fun `angles without a proxy are stills even when decoders are spare`() {
        val feeds = MulticamPlanner.plan(angleCount = 3, active = 2, proxyReady = emptySet(), maxDecoders = 4)
        assertEquals(listOf(AngleFeed.STILL, AngleFeed.STILL, AngleFeed.FULL), feeds)
    }

    @Test
    fun `the planner never gives out more decoders than the device has`() {
        for (decoders in 1..6) for (active in 0 until 6) {
            val feeds = MulticamPlanner.plan(6, active, proxyReady = (0 until 6).toSet(), maxDecoders = decoders)
            assertEquals(1, feeds.count { it == AngleFeed.FULL })
            assertTrue(feeds.count { it != AngleFeed.STILL } <= decoders)
        }
    }

    // endregion
}
