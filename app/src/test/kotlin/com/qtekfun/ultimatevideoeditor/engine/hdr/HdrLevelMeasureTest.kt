package com.qtekfun.ultimatevideoeditor.engine.hdr

import com.qtekfun.ultimatevideoeditor.domain.HdrLevel
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.engine.verify.DecodedFrame
import com.qtekfun.ultimatevideoeditor.engine.verify.DecodedRange
import com.qtekfun.ultimatevideoeditor.engine.verify.FrameSignature
import com.qtekfun.ultimatevideoeditor.engine.verify.FrameSource
import com.qtekfun.ultimatevideoeditor.engine.verify.SignatureFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrLevelMeasureTest {
    private val scale = SignatureFormat.SCALE

    /** A frame of one grey: [luma] is the limited-range signal 0..1 of every cell. */
    private fun grey(frame: Long, luma: Double): FrameSignature {
        val y = IntArray(SignatureFormat.CELLS) { (luma * scale).toInt() }
        val mid = IntArray(SignatureFormat.CELLS) { scale / 2 + 1 }
        return FrameSignature(frame, 0, y, mid, mid.copyOf())
    }

    private class FakeSource(val frames: Map<Long, FrameSignature>, val failOn: Set<Long> = emptySet()) : FrameSource {
        val asked = ArrayList<Long>()

        override fun decode(from: Long, to: Long, cancel: () -> Boolean): DecodedRange {
            asked += from
            if (from in failOn) return DecodedRange(from, to, emptyList(), "decoder error")
            val hit = frames[from] ?: return DecodedRange(from, to, emptyList())
            return DecodedRange(from, to, listOf(DecodedFrame(from, hit)))
        }
    }

    @Test
    fun `twelve frames are spread over the file and none is the first or last`() {
        val frames = HdrLevelMeasure.sampleFrames(1200)
        assertEquals(HdrLevel.SAMPLE_FRAMES, frames.size)
        assertEquals(frames.sorted(), frames)
        assertEquals(50L, frames.first())
        assertTrue(frames.last() < 1199)
        assertEquals(frames.size, frames.distinct().size)
    }

    @Test
    fun `a short file asks for each of its frames once`() {
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), HdrLevelMeasure.sampleFrames(5))
        assertEquals(emptyList<Long>(), HdrLevelMeasure.sampleFrames(0))
    }

    @Test
    fun `the level is that of the pooled frames`() {
        val frames = HdrLevelMeasure.sampleFrames(1200)
        val source = FakeSource(frames.associateWith { grey(it, 0.75) }) // HLG signal 0.75 is 203 nit
        val level = HdrLevelMeasure.diffuseLevel(source, 1200, SourceColorSpace.HLG)!!
        assertEquals(1.0, level, 0.02)
        assertEquals(frames, source.asked)
    }

    @Test
    fun `a bright clip has a level above one and so a gain below one`() {
        val frames = HdrLevelMeasure.sampleFrames(600)
        val source = FakeSource(frames.associateWith { grey(it, 0.85) })
        val level = HdrLevelMeasure.diffuseLevel(source, 600, SourceColorSpace.HLG)!!
        assertTrue("level $level", level > 1.5)
        assertTrue(HdrLevel.gainFor(level) < 0.7)
    }

    @Test
    fun `too few readable frames give no level`() {
        val frames = HdrLevelMeasure.sampleFrames(600)
        val few = FakeSource(frames.take(2).associateWith { grey(it, 0.85) })
        assertNull(HdrLevelMeasure.diffuseLevel(few, 600, SourceColorSpace.HLG))
        assertNull(HdrLevelMeasure.diffuseLevel(FakeSource(emptyMap()), 600, SourceColorSpace.HLG))
    }

    @Test
    fun `a decoder failure on some frames still measures the rest`() {
        val frames = HdrLevelMeasure.sampleFrames(600)
        val source = FakeSource(frames.associateWith { grey(it, 0.75) }, failOn = setOf(frames[0], frames[1]))
        assertEquals(1.0, HdrLevelMeasure.diffuseLevel(source, 600, SourceColorSpace.HLG)!!, 0.02)
    }

    @Test
    fun `an SDR file is not measured and cancelling stops the reading`() {
        val source = FakeSource(emptyMap())
        assertNull(HdrLevelMeasure.diffuseLevel(source, 600, SourceColorSpace.SDR))
        assertTrue(source.asked.isEmpty())
        assertNull(HdrLevelMeasure.diffuseLevel(source, 600, SourceColorSpace.HLG, cancel = { true }))
        assertTrue(source.asked.isEmpty())
    }
}
