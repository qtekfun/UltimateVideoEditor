package com.qtekfun.ultimatevideoeditor.domain.voice

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePlacementTest {
    private fun clip(id: String, start: Long, length: Long) = Clip(id, "asset", FrameIndex(start), FrameIndex.ZERO, FrameIndex(length))
    private fun audio(id: String, vararg clips: Clip) = Track(id, TrackType.AUDIO, clips.toList())

    @Test
    fun `an empty audio lane is used`() {
        val timeline = Timeline(tracks = listOf(Track("v1", TrackType.VIDEO), audio("a1")))
        val plan = VoicePlacement.plan(timeline, FrameIndex(100), 300, "track-a2")
        assertEquals("a1", plan.trackId)
        assertNull(plan.newTrack)
    }

    @Test
    fun `a lane with music under the take is skipped and the next free one is used`() {
        val timeline = Timeline(tracks = listOf(audio("a1", clip("m", 0, 1000)), audio("a2", clip("n", 0, 50))))
        val plan = VoicePlacement.plan(timeline, FrameIndex(100), 300, "track-a3")
        assertEquals("a2", plan.trackId)
        assertNull(plan.newTrack)
    }

    @Test
    fun `when every audio lane is busy a new one is added at the end and music is never overwritten`() {
        val timeline = Timeline(tracks = listOf(audio("a1", clip("m", 0, 1000))))
        val plan = VoicePlacement.plan(timeline, FrameIndex(100), 300, "track-a2")
        assertEquals("track-a2", plan.trackId)
        assertEquals(Track("track-a2", TrackType.AUDIO), plan.newTrack)
    }

    @Test
    fun `a take with no audio lane at all gets one`() {
        val plan = VoicePlacement.plan(Timeline(tracks = listOf(Track("v1", TrackType.VIDEO))), FrameIndex.ZERO, 10, "track-a1")
        assertNotNull(plan.newTrack)
    }

    @Test
    fun `touching is not overlapping, at either end`() {
        val timeline = Timeline(tracks = listOf(audio("a1", clip("before", 0, 100), clip("after", 400, 100))))
        // The take fills exactly the gap [100, 400).
        assertEquals("a1", VoicePlacement.plan(timeline, FrameIndex(100), 300, "x").trackId)
        // One frame longer runs into the next clip.
        assertEquals("x", VoicePlacement.plan(timeline, FrameIndex(100), 301, "x").trackId)
        // One frame earlier runs into the one before.
        assertEquals("x", VoicePlacement.plan(timeline, FrameIndex(99), 300, "x").trackId)
    }

    @Test
    fun `the trim covers the lead and the latency and always leaves something`() {
        assertEquals(3, VoicePlacement.trimFrames(leadMs = 100, latencyMs = 0, framesPerSecond = 30.0, lengthFrames = 300))
        assertEquals(6, VoicePlacement.trimFrames(leadMs = 100, latencyMs = 100, framesPerSecond = 30.0, lengthFrames = 300))
        assertEquals(0, VoicePlacement.trimFrames(leadMs = 0, latencyMs = 0, framesPerSecond = 30.0, lengthFrames = 300))
        assertEquals(0, VoicePlacement.trimFrames(leadMs = -50, latencyMs = 0, framesPerSecond = 30.0, lengthFrames = 300))
        assertEquals(4, VoicePlacement.trimFrames(leadMs = 10_000, latencyMs = 0, framesPerSecond = 30.0, lengthFrames = 5))
        assertEquals(0, VoicePlacement.trimFrames(leadMs = 500, latencyMs = 0, framesPerSecond = 30.0, lengthFrames = 1))
        // 29.97 and 60 fps round to the nearest frame.
        assertEquals(3, VoicePlacement.trimFrames(leadMs = 100, latencyMs = 0, framesPerSecond = 29.97, lengthFrames = 300))
        assertEquals(6, VoicePlacement.trimFrames(leadMs = 100, latencyMs = 0, framesPerSecond = 60.0, lengthFrames = 300))
    }
}
