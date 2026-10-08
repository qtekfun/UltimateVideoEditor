package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlanTest {
    private val fps = FrameRate(30, 1)
    private val assets = listOf(
        MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR"),
        MediaAssetDto("b", "content://b", 600, 30, 1, "Rec709-SDR"),
    )

    private fun plan(tl: com.qtekfun.ultimatevideoeditor.domain.Timeline, frame: Long) = buildFramePlan(tl, assets, fps, 1920, 1080, frame)

    @Test
    fun `only the clips that cover the frame and their media are kept`() {
        val tl = timeline(track("v1", clip("c1", 0, 30, asset = "a"), clip("c2", 60, 30, asset = "b")))

        val first = plan(tl, 0)!!
        assertEquals(1, first.clips.size)
        assertEquals(setOf("a"), first.assetKeys.keys)

        val second = plan(tl, 89)!!
        assertEquals(setOf("b"), second.assetKeys.keys)
        assertEquals(60L, second.clips.single().startFrame)
    }

    @Test
    fun `a frame in a gap or past the end keeps no clip and opens no media`() {
        val tl = timeline(track("v1", clip("c1", 0, 30, asset = "a"), clip("c2", 60, 30, asset = "b")))

        for (frame in listOf(30L, 59L, 90L, 100_000L)) {
            val p = plan(tl, frame)!!
            assertTrue("frame $frame", p.clips.isEmpty())
            assertTrue(p.assetKeys.isEmpty())
        }
    }

    @Test
    fun `the first and the last frame of a clip are covered, the one after is not`() {
        val tl = timeline(track("v1", clip("c1", 10, 20, asset = "a")))

        assertEquals(1, plan(tl, 10)!!.clips.size)
        assertEquals(1, plan(tl, 29)!!.clips.size)
        assertTrue(plan(tl, 30)!!.clips.isEmpty())
        assertTrue(plan(tl, 9)!!.clips.isEmpty())
    }

    @Test
    fun `two layers at the same frame both stay`() {
        val tl = timeline(track("v2", clip("o", 0, 30, asset = "b")), track("v1", clip("c1", 0, 60, asset = "a")))

        val p = plan(tl, 10)!!

        assertEquals(2, p.clips.size)
        assertEquals(setOf("a", "b"), p.assetKeys.keys)
        assertEquals(setOf(0, 1), p.clips.map { it.layer }.toSet())
    }

    @Test
    fun `an empty timeline has no plan`() {
        assertNull(plan(timeline(), 0))
    }
}
