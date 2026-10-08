package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.still.StillRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewScenesStillTest {

    private val fps = FrameRate(30, 1)
    private val image = MediaAssetDto("img", "content://pic/1", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
    private val video = MediaAssetDto("a1", "content://a1", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)

    private fun still(id: String, start: Long, len: Long, kind: StillKind, asset: String) =
        Clip(id, asset, FrameIndex(start), FrameIndex.ZERO, FrameIndex(len), still = kind)

    private fun requests(tl: Timeline, frame: Long) =
        previewRequestsAt(tl, listOf(image, video), fps, FrameIndex(frame)) { 7 }

    @Test
    fun `a photo is a picture layer from its file, with no decoder`() {
        val layers = requests(timeline(track("v1", still("p", 0, 100, StillKind.PHOTO, "img"))), 10)

        val layer = layers.single()
        assertEquals(StillRef(StillKind.PHOTO, "content://pic/1"), layer.still)
        assertNull(layer.title)
        assertEquals("", layer.uri)
        assertEquals(0, layer.assetKey)
    }

    @Test
    fun `a sticker is a picture layer from its built-in id and needs no library entry`() {
        val layers = requests(timeline(track("v1", still("s", 0, 100, StillKind.STICKER, "shape:heart"))), 10)

        assertEquals(StillRef(StillKind.STICKER, "shape:heart"), layers.single().still)
    }

    @Test
    fun `a photo whose library entry is gone is left out`() {
        val tl = timeline(track("v1", still("p", 0, 100, StillKind.PHOTO, "missing")))

        assertTrue(requests(tl, 10).isEmpty())
    }

    @Test
    fun `stills stack with video by track order and carry their transform`() {
        val tl = timeline(
            track("v2", still("s", 0, 100, StillKind.STICKER, "shape:star").copy(transform = ClipTransform(scaleX = 2.0, scaleY = 2.0))),
            track("v1", clip("c", 0, 200, asset = "a1")),
        )

        val layers = requests(tl, 20)

        assertEquals(2, layers.size)
        assertNull(layers[0].still) // the video is at the bottom
        assertEquals(2.0, layers[1].transform.scaleX, 0.0)
        assertEquals(StillKind.STICKER, layers[1].still?.kind)
    }

    @Test
    fun `a still ends where its clip ends`() {
        val tl = timeline(track("v1", still("p", 10, 20, StillKind.PHOTO, "img")))

        assertTrue(requests(tl, 9).isEmpty())
        assertEquals(1, requests(tl, 10).size)
        assertEquals(1, requests(tl, 29).size)
        assertTrue(requests(tl, 30).isEmpty())
    }
}
