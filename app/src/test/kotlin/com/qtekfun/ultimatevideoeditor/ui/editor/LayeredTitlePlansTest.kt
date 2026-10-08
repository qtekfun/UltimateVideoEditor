package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.title.TitleKeyCache
import com.qtekfun.ultimatevideoeditor.ui.export.buildExportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multilayer titles in the plans the preview and the exporter build: photo layers point at library files, and both agree. */
class LayeredTitlePlansTest {
    private val fps = FrameRate(30, 1)
    private val photo = MediaAssetDto("img", "content://pic/1", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
    private val video = MediaAssetDto("a1", "content://a1", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)

    private val layered = TitleLayerEdit.of(
        listOf(
            TitleLayerEdit.newShape(ShapeKind.RECT),
            ImageLayer(StillKind.PHOTO, "img"),
            ImageLayer(StillKind.STICKER, "shape:heart"),
            TextLayer("Hello"),
        ),
    )

    private fun titled(content: com.qtekfun.ultimatevideoeditor.domain.TitleContent = layered) = timeline(
        track("t1", Clip("title", null, FrameIndex(0), FrameIndex.ZERO, FrameIndex(90), title = content), type = TrackType.TITLE),
        track("v1", clip("a", 0, 200)),
    )

    @Test
    fun `the preview points photo layers at the library file`() {
        val layers = previewRequestsAt(titled(), listOf(photo, video), fps, FrameIndex(10)) { 7 }
        val title = layers.single { it.title != null }.title!!
        assertEquals("content://pic/1", (title.layers[1] as ImageLayer).resolvedUri)
        // Stickers need no file.
        assertNull((title.layers[2] as ImageLayer).resolvedUri)
    }

    @Test
    fun `the exporter resolves them the same way so both draw the same picture`() {
        val plan = buildExportPlan(titled(), listOf(photo, video), fps)!!
        val title = plan.titles.values.single()
        assertEquals("content://pic/1", (title.layers[1] as ImageLayer).resolvedUri)
        val preview = previewRequestsAt(titled(), listOf(photo, video), fps, FrameIndex(10)) { 7 }.single { it.title != null }.title!!
        assertEquals(preview, title)
    }

    @Test
    fun `a photo gone from the library leaves the layer unresolved and the title still plans`() {
        val layers = previewRequestsAt(titled(), listOf(video), fps, FrameIndex(10)) { 7 }
        assertNull((layers.single { it.title != null }.title!!.layers[1] as ImageLayer).resolvedUri)
        assertTrue(buildExportPlan(titled(), listOf(video), fps)!!.titles.isNotEmpty())
    }

    @Test
    fun `relinking a photo to another file changes the cache key so the picture is drawn again`() {
        val a = previewRequestsAt(titled(), listOf(photo, video), fps, FrameIndex(10)) { 7 }.single { it.title != null }.title!!
        val moved = photo.copy(uri = "content://pic/2")
        val b = previewRequestsAt(titled(), listOf(moved, video), fps, FrameIndex(10)) { 7 }.single { it.title != null }.title!!
        val cache = TitleKeyCache()
        val (keyA, freshA) = cache.keyFor(a, 1920, 1080)
        val (keyB, freshB) = cache.keyFor(b, 1920, 1080)
        assertNotEquals(keyA, keyB)
        assertTrue(freshA && freshB)
        // The same title on the same canvas keeps its key.
        assertEquals(keyA to false, cache.keyFor(a, 1920, 1080))
    }

    @Test
    fun `clearing the cache hands back every key so the textures can be released`() {
        val cache = TitleKeyCache()
        val one = cache.keyFor(layered, 1920, 1080).first
        val two = cache.keyFor(TitleLayerEdit.of(listOf(TextLayer("x"))), 1920, 1080).first
        cache.clear()
        assertEquals(setOf(one, two), cache.drain().toSet())
        assertTrue(cache.keyFor(layered, 1920, 1080).second)
    }

    @Test
    fun `a title without photo layers is passed through untouched`() {
        val plain = TitleLayerEdit.of(listOf(TextLayer("x")))
        val content = previewRequestsAt(titled(plain), listOf(video), fps, FrameIndex(10)) { 7 }.single { it.title != null }.title!!
        assertEquals(plain, content)
    }
}
