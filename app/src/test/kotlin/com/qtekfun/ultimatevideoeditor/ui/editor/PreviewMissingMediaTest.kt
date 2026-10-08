package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.MissingMediaCard
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.proxy.ResolvedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clip whose file cannot be read shows a "Media missing" card in the preview, as a layer like any other (SPECS 5.40). */
class PreviewMissingMediaTest {

    private val fps = FrameRate(30, 1)
    private val readable = MediaAssetDto("ok", "content://usb/ok.mp4", 300, 30, 1, "Rec709-SDR", displayName = "ok.mp4")
    private val gone = MediaAssetDto("gone", "content://usb/gone.mp4", 300, 30, 1, "Rec709-SDR", displayName = "gone.mp4")
    private val goneAudio = MediaAssetDto("music", "content://usb/music.mp3", 300, 30, 1, "Rec709-SDR", hasVideo = false, displayName = "music.mp3")
    private val gonePhoto = MediaAssetDto("pic", "content://usb/pic.jpg", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true, displayName = "pic.jpg")

    private fun requests(
        tl: com.qtekfun.ultimatevideoeditor.domain.Timeline,
        playable: List<MediaAssetDto>,
        unreadable: List<MediaAssetDto>,
        frame: Long = 10,
        width: Int = 1920,
        height: Int = 1080,
    ) = previewRequestsOnCanvas(
        tl, playable, fps, FrameIndex(frame), width, height,
        sourceOf = { ResolvedSource(it.uri) },
        unreadable = unreadable,
    ) { it.hashCode() }

    @Test
    fun `a clip of an unreadable file becomes a card with the file name`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "gone")))

        val layer = requests(tl, emptyList(), listOf(gone)).single()

        val card = layer.title!!
        assertTrue(MissingMediaCard.isCard(card))
        val texts = card.layers.filterIsInstance<TextLayer>().map { it.text }
        assertEquals(listOf("Media missing", "gone.mp4"), texts)
        // Not a decoder layer: nothing is opened for it.
        assertEquals("", layer.uri)
        assertNull(layer.still)
    }

    @Test
    fun `without the unreadable list a missing clip shows nothing, as before`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "gone")))

        assertTrue(requests(tl, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `the card sits in the lane order with the readable layers`() {
        val tl = timeline(
            track("v2", clip("top", 0, 100, asset = "ok")),
            track("v1", clip("base", 0, 100, asset = "gone")),
        )

        val layers = requests(tl, listOf(readable), listOf(gone))

        // Bottom first: the base track's card, then the overlay's video.
        assertEquals(2, layers.size)
        assertNotNull(layers[0].title)
        assertEquals("content://usb/ok.mp4", layers[1].uri)
    }

    @Test
    fun `the card follows the clip's transform and opacity`() {
        val moved = clip("c1", 0, 100, asset = "gone").copy(transform = ClipTransform(positionX = 0.25, scaleX = 0.5, scaleY = 0.5, opacity = 0.4))
        val tl = timeline(track("v1", moved))

        val layer = requests(tl, emptyList(), listOf(gone)).single()

        assertEquals(0.25, layer.transform.positionX, 1e-9)
        assertEquals(0.5, layer.transform.scaleX, 1e-9)
        assertEquals(0.4, layer.transform.opacity, 1e-9)
    }

    @Test
    fun `nothing is shown outside the clip`() {
        val tl = timeline(track("v1", clip("c1", 50, 100, asset = "gone")))

        assertTrue(requests(tl, emptyList(), listOf(gone), frame = 10).isEmpty())
        assertEquals(1, requests(tl, emptyList(), listOf(gone), frame = 60).size)
    }

    @Test
    fun `an audio-only file has no picture to stand in for`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "music")))

        assertTrue(requests(tl, emptyList(), listOf(goneAudio)).isEmpty())
    }

    @Test
    fun `a missing photo shows the card too`() {
        val photo = Clip("p", "pic", FrameIndex(0), FrameIndex.ZERO, FrameIndex(100), still = StillKind.PHOTO)
        val tl = timeline(track("v1", photo))

        val layer = requests(tl, emptyList(), listOf(gonePhoto)).single()

        assertTrue(MissingMediaCard.isCard(layer.title!!))
        assertNull(layer.still)
    }

    @Test
    fun `a readable file is never replaced by a card`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "ok")))

        val layer = requests(tl, listOf(readable), listOf(gone)).single()

        assertNull(layer.title)
        assertEquals("content://usb/ok.mp4", layer.uri)
    }

    @Test
    fun `the same card is produced for the same file and canvas so its picture is cached`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "gone")))

        val a = requests(tl, emptyList(), listOf(gone), frame = 5).single().title
        val b = requests(tl, emptyList(), listOf(gone), frame = 80).single().title

        assertEquals(a, b)
    }
}
