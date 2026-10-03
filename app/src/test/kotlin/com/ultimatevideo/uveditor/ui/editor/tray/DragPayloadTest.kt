package com.ultimatevideo.uveditor.ui.editor.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragPayloadTest {
    @Test
    fun `mime types map to the kinds the editor can place`() {
        assertEquals(AssetKind.VIDEO, kindForMime("video/mp4"))
        assertEquals(AssetKind.PHOTO, kindForMime("image/heic"))
        assertEquals(AssetKind.AUDIO, kindForMime("audio/mpeg"))
        assertNull(kindForMime("text/plain"))
        assertNull(kindForMime("application/pdf"))
        assertNull(kindForMime(null))
    }

    @Test
    fun `a drag of mixed types keeps the placeable kinds in order and a text drag has none`() {
        assertEquals(
            listOf(AssetKind.AUDIO, AssetKind.VIDEO),
            kindsOfMimes(listOf("text/uri-list", "audio/aac", "video/webm")),
        )
        assertEquals(emptyList<AssetKind>(), kindsOfMimes(listOf("text/plain")))
        assertEquals(emptyList<AssetKind>(), kindsOfMimes(emptyList()))
    }

    @Test
    fun `thumbnails scale down to fit and never up`() {
        assertEquals(256 to 144, AssetThumbnails.fit(3840, 2160, 256))
        assertEquals(144 to 256, AssetThumbnails.fit(2160, 3840, 256))
        assertEquals(100 to 60, AssetThumbnails.fit(100, 60, 256))
        assertEquals(1 to 1, AssetThumbnails.fit(0, 0, 256))
        assertEquals(256 to 1, AssetThumbnails.fit(100000, 10, 256))
    }

    @Test
    fun `duration labels are blank for photos`() {
        val photo = com.ultimatevideo.uveditor.data.model.MediaAssetDto(
            "p", "content://p", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true,
        )
        assertEquals("", durationLabel(photo))
        assertEquals("00:00:05:00", durationLabel(photo.copy(isImage = false, durationFrames = 150)))
    }
}
