package com.qtekfun.ultimatevideoeditor.qa

import com.qtekfun.ultimatevideoeditor.data.interchange.MediaFileNames
import com.qtekfun.ultimatevideoeditor.data.isImageMime
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.kindForMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 43 photos in a project reported "Could not generate thumbnails (IO_ERROR)" on every editor open: they went through the video
 * thumbnail path. A photo is told from a video by its media type (and, in a media folder or an import, by its extension, which is
 * mapped to a media type first), so this pins that table: every picture type is an image, never a video, and nothing else is.
 * The native half (the extractor refuses a picture with "unsupported", which is not an I/O error and falls through to the image
 * decoder) is in `thumbnail_host_tests.cpp`; one message per asset is in `ThumbnailFailuresTest`; the device check, which opens a
 * project of photos and reads logcat (tag uv_thumb), is in `scripts/qa-smoke.sh`.
 */
class MediaKindGuardTest {
    private val photoExtensions = listOf("jpg", "jpeg", "JPG", "Jpeg", "png", "PNG", "heic", "heif", "gif", "webp")
    private val videoExtensions = listOf("mov", "MOV", "mp4", "m4v", "mkv", "webm", "3gp")

    @Test
    fun `every picture type is an image for the importer`() {
        for (type in listOf("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp", "image/gif", "image/avif", "image/bmp", "image/x-adobe-dng")) {
            assertTrue(type, isImageMime(type))
        }
    }

    @Test
    fun `nothing else is an image`() {
        for (type in listOf("video/mp4", "video/quicktime", "audio/mp4", "application/octet-stream", "", "IMAGE/JPEG-ish")) assertFalse(type, isImageMime(type))
        assertFalse(isImageMime(null))
    }

    @Test
    fun `a picture file name maps to an image type and a video name never does`() {
        for (extension in photoExtensions) {
            val mime = MediaFileNames.mimeOf("IMG_0001.$extension")
            assertTrue("$extension -> $mime", isImageMime(mime))
            assertEquals("$extension must be a photo for the tray", AssetKind.PHOTO, kindForMime(mime))
        }
        for (extension in videoExtensions) {
            val mime = MediaFileNames.mimeOf("clip.$extension")
            assertFalse("$extension -> $mime", isImageMime(mime))
            assertEquals(AssetKind.VIDEO, kindForMime(mime))
        }
    }

    @Test
    fun `the tray and the importer agree on pictures`() {
        for (type in listOf("image/jpeg", "image/png", "image/heic", "image/webp", "image/gif")) {
            assertEquals(type, AssetKind.PHOTO, kindForMime(type))
            assertTrue(isImageMime(type))
        }
        assertEquals(AssetKind.VIDEO, kindForMime("video/hevc"))
        assertEquals(AssetKind.AUDIO, kindForMime("audio/aac"))
        assertEquals(null, kindForMime("application/octet-stream"))
    }
}
