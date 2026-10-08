package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.SourceMix
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.renderClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export source table carries the slow-motion mix in its high bits; `encode/export_math.h` decodes it. */
class SourcePackTest {

    private fun unpackFrame(entry: Long): Long = if (entry >= (1L shl SOURCE_MIX_SHIFT)) entry and ((1L shl SOURCE_MIX_SHIFT) - 1) else entry
    private fun unpackMix(entry: Long): Int = if (entry >= (1L shl SOURCE_MIX_SHIFT)) (entry shr SOURCE_MIX_SHIFT).toInt() else 0

    @Test
    fun `a plain or whole frame is stored as is, even a negative one`() {
        assertEquals(42L, packSource(SourceMix(42, 42, 0)))
        assertEquals(-3L, packSource(SourceMix(-3, -3, 0)))
        assertEquals(-3L, unpackFrame(-3L))
        assertEquals(0, unpackMix(-3L))
    }

    @Test
    fun `a blended frame packs its frame and permille`() {
        val entry = packSource(SourceMix(1_234_567, 1_234_568, 750))
        assertTrue(entry >= (1L shl SOURCE_MIX_SHIFT))
        assertEquals(1_234_567L, unpackFrame(entry))
        assertEquals(750, unpackMix(entry))
    }

    @Test
    fun `every table entry of a slowed clip decodes to the frame and mix of the plan`() {
        val clip = Clip("a", "x", FrameIndex(0), FrameIndex(10), FrameIndex(30), retimedFrames = 80, smoothSlowMo = true)
        val timeline = Timeline(listOf(Track("v1", TrackType.VIDEO, listOf(clip))))
        val render = timeline.renderClips().single()
        for (i in 0 until 80) {
            val shown = render.sourceMixAt(render.startFrame + i)
            val entry = packSource(shown)
            assertEquals("frame $i", shown.frame, unpackFrame(entry))
            assertEquals("mix $i", if (shown.blended) shown.mixPermille else 0, unpackMix(entry))
        }
        assertTrue((0 until 80).any { unpackMix(packSource(render.sourceMixAt(render.startFrame + it.toLong()))) > 0 })
    }

    @Test
    fun `a clip without the flag packs plain frames`() {
        val clip = Clip("a", "x", FrameIndex(0), FrameIndex(10), FrameIndex(30), retimedFrames = 80)
        val timeline = Timeline(listOf(Track("v1", TrackType.VIDEO, listOf(clip))))
        val render = timeline.renderClips().single()
        for (i in 0 until 80) {
            val entry = packSource(render.sourceMixAt(render.startFrame + i))
            assertEquals(0, unpackMix(entry))
            assertEquals(render.sourceFrameAt(render.startFrame + i), entry)
        }
        // Turning it on afterwards adds the mix.
        val on = (TimelineOps.setSmoothSlowMo(timeline, "a", true) as EditResult.Success).value.renderClips().single()
        assertTrue(unpackMix(packSource(on.sourceMixAt(on.startFrame + 1))) > 0)
    }
}
