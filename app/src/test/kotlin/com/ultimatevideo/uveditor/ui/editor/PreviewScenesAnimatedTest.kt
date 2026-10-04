package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.still.StillRef
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewScenesAnimatedTest {
    private val fps = FrameRate(30, 1)
    private val animated = MediaAssetDto(
        "gif", "content://pic/anim", 18, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true,
        animationDelaysMs = listOf(100, 200, 300),
    )
    private val photo = MediaAssetDto("img", "content://pic/1", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)

    private fun clipOf(asset: String, start: Long, len: Long) =
        Clip("c", asset, FrameIndex(start), FrameIndex.ZERO, FrameIndex(len), still = StillKind.PHOTO)

    private fun frameShownAt(playhead: Long, start: Long = 0, asset: String = "gif"): Int? {
        val tl = timeline(track("v1", clipOf(asset, start, 100)))
        return previewRequestsAt(tl, listOf(animated, photo), fps, FrameIndex(playhead)) { 7 }.singleOrNull()?.still?.frame
    }

    @Test
    fun `the preview picture is the animation frame of the playhead and loops`() {
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 1, 1, 2), (0L..9L).map { frameShownAt(it) })
        assertEquals(0, frameShownAt(18))
        assertEquals(1, frameShownAt(21))
    }

    @Test
    fun `the animation starts at the clip's own first frame`() {
        assertEquals(0, frameShownAt(playhead = 100, start = 100))
        assertEquals(1, frameShownAt(playhead = 103, start = 100))
        assertEquals(2, frameShownAt(playhead = 109, start = 100))
    }

    @Test
    fun `the layer is keyed by the file and the frame so each picture is cached separately`() {
        val tl = timeline(track("v1", clipOf("gif", 0, 100)))
        val a = previewRequestsAt(tl, listOf(animated), fps, FrameIndex(0)) { 7 }.single().still
        val b = previewRequestsAt(tl, listOf(animated), fps, FrameIndex(3)) { 7 }.single().still
        assertEquals(StillRef(StillKind.PHOTO, "content://pic/anim", 0), a)
        assertEquals(StillRef(StillKind.PHOTO, "content://pic/anim", 1), b)
    }

    @Test
    fun `a plain photo and a sticker always show frame zero`() {
        assertEquals(0, frameShownAt(50, asset = "img"))
        val sticker = Clip("s", "shape:heart", FrameIndex(0), FrameIndex.ZERO, FrameIndex(100), still = StillKind.STICKER)
        val layers = previewRequestsAt(timeline(track("v1", sticker)), listOf(animated), fps, FrameIndex(50)) { 7 }
        assertEquals(0, layers.single().still?.frame)
    }
}
