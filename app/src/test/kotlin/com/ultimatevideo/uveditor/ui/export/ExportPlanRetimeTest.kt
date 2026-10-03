package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.SpeedRamps
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanRetimeTest {

    private val fps = FrameRate(30, 1)

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    private fun plan(tl: Timeline) = buildExportPlan(tl, listOf(asset("a")), fps)!!

    @Test
    fun `a plain clip carries no source table`() {
        val spec = plan(timeline(track("v1", clip("c", 0, 100, asset = "a")))).videoClips.single()

        assertNull(spec.sourceFrames)
        assertFalse(spec.reverse)
    }

    @Test
    fun `a fast clip lists the source frame of every project frame`() {
        val tl = TimelineOps.setSpeed(timeline(track("v1", clip("c", 0, 100, srcIn = 20, asset = "a"))), "c", 2, 1).getOrFail()

        val spec = plan(tl).videoClips.single()

        assertEquals(50L, spec.durationFrames)
        assertArrayEquals(LongArray(50) { 20L + 2 * it }, spec.sourceFrames)
        assertFalse(spec.reverse)
    }

    @Test
    fun `a reversed clip runs its table downwards and says so`() {
        val tl = TimelineOps.setReverse(timeline(track("v1", clip("c", 0, 10, srcIn = 5, asset = "a"))), "c", true).getOrFail()

        val spec = plan(tl).videoClips.single()

        assertArrayEquals(longArrayOf(14, 13, 12, 11, 10, 9, 8, 7, 6, 5), spec.sourceFrames)
        assertTrue(spec.reverse)
    }

    @Test
    fun `a freeze frame is one source frame held`() {
        val tl = TimelineOps.freezeFrame(timeline(track("v1", clip("c", 0, 100, asset = "a"))), "v1", FrameIndex(40), 5, "fz", "c2").getOrFail()

        val specs = plan(tl).videoClips.sortedBy { it.startFrame }

        assertEquals(listOf(0L, 40L, 45L), specs.map { it.startFrame })
        assertArrayEquals(LongArray(5) { 40L }, specs[1].sourceFrames)
        assertNull(specs[0].sourceFrames)
        assertNull(specs[2].sourceFrames)
    }

    @Test
    fun `a ramped clip covers its source range from the first frame to near the last`() {
        val base = TimelineOps.setSpeed(timeline(track("v1", clip("c", 0, 120, srcIn = 30, asset = "a"))), "c", 2, 1).getOrFail()
        val tl = TimelineOps.setSpeedRamp(base, "c", SpeedRamps.easeIn(60)).getOrFail()

        val table = plan(tl).videoClips.single().sourceFrames!!

        assertEquals(60, table.size)
        assertEquals(30L, table.first())
        assertTrue(table.last() in 140L..149L)
        assertEquals(table.sorted(), table.toList())
    }

    @Test
    fun `a transition tail of a retimed clip continues the table`() {
        val base = timeline(track("v1", clip("a", 0, 100, asset = "a"), clip("b", 100, 100, srcIn = 40, asset = "a")))
        val fast = TimelineOps.setSpeed(base, "a", 2, 1, ripple = true).getOrFail()
        val tl = TimelineOps.addTransition(fast, Transition("t", "a", "b", 10), outgoingSourceLength = 400).getOrFail()

        val outgoing = plan(tl).videoClips.first { it.startFrame == 0L }

        assertEquals(55L, outgoing.durationFrames)
        assertEquals(LongArray(55) { 2L * it }.toList(), outgoing.sourceFrames!!.toList())
    }
}
