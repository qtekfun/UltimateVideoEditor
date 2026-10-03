package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTargetTest {

    @Test
    fun `maps the playhead to the source frame inside the clip`() {
        val tl = timeline(track("v1", clip("c1", start = 100, len = 50, srcIn = 30)))

        val target = previewTargetAt(tl, FrameIndex(110))

        assertEquals("c1", target?.clip?.id)
        assertEquals(40L, target?.sourceFrame)
    }

    @Test
    fun `clip start is included and clip end is excluded`() {
        val tl = timeline(track("v1", clip("c1", 100, 50)))

        assertEquals(0L, previewTargetAt(tl, FrameIndex(100))?.sourceFrame)
        assertEquals(49L, previewTargetAt(tl, FrameIndex(149))?.sourceFrame)
        assertNull(previewTargetAt(tl, FrameIndex(150)))
    }

    @Test
    fun `gaps and the area before the first clip have no target`() {
        val tl = timeline(track("v1", clip("c1", 100, 50), clip("c2", 200, 50)))

        assertNull(previewTargetAt(tl, FrameIndex(0)))
        assertNull(previewTargetAt(tl, FrameIndex(170)))
        assertEquals("c2", previewTargetAt(tl, FrameIndex(200))?.clip?.id)
    }

    @Test
    fun `the topmost video track wins and lower tracks show through its gaps`() {
        val tl = timeline(
            track("v2", clip("top", 50, 50)),
            track("v1", clip("bottom", 0, 200)),
        )

        assertEquals("top", previewTargetAt(tl, FrameIndex(60))?.clip?.id)
        assertEquals("bottom", previewTargetAt(tl, FrameIndex(10))?.clip?.id)
        assertEquals("bottom", previewTargetAt(tl, FrameIndex(150))?.clip?.id)
    }

    @Test
    fun `audio tracks and clips without media are never shown`() {
        val tl = timeline(
            track("a1", clip("sound", 0, 100), type = TrackType.AUDIO),
            track("v1", clip("title", 0, 100, asset = null)),
        )

        assertNull(previewTargetAt(tl, FrameIndex(10)))
    }
}
