package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryTest {

    @Test
    fun `a wide canvas in a tall view leaves bars above and below`() {
        val rect = PreviewGeometry.canvasRect(1920, 1080, 1440f, 1000f)

        assertEquals(1440f, rect.width, 1e-3f)
        assertEquals(810f, rect.height, 1e-3f)
        assertEquals(0f, rect.x, 1e-3f)
        assertEquals(95f, rect.y, 1e-3f)
        assertEquals(0.75f, PreviewGeometry.viewScale(rect, 1920), 1e-6f)
    }

    @Test
    fun `a portrait canvas in a wide view leaves bars at the sides`() {
        val rect = PreviewGeometry.canvasRect(1080, 1920, 1000f, 500f)

        assertEquals(281.25f, rect.width, 1e-2f)
        assertEquals(500f, rect.height, 1e-3f)
        assertEquals((1000f - 281.25f) / 2f, rect.x, 1e-2f)
    }

    @Test
    fun `degenerate sizes give an empty rect`() {
        assertTrue(PreviewGeometry.canvasRect(0, 1080, 100f, 100f).isEmpty)
        assertTrue(PreviewGeometry.canvasRect(1920, 1080, 0f, 100f).isEmpty)
        assertEquals(0f, PreviewGeometry.viewScale(CanvasRect(0f, 0f, 0f, 0f), 0), 0f)
    }

    @Test
    fun `gesture pans zooms and rotates`() {
        val result = PreviewGeometry.applyGesture(ClipTransform(positionX = 10.0), panX = 5.0, panY = -20.0, zoom = 2.0, rotationDegrees = 30.0)

        assertEquals(15.0, result.positionX, 1e-9)
        assertEquals(-20.0, result.positionY, 1e-9)
        assertEquals(2.0, result.scaleX, 1e-9)
        assertEquals(2.0, result.scaleY, 1e-9)
        assertEquals(30.0, result.rotationDegrees, 1e-9)
    }

    @Test
    fun `scale is kept within limits`() {
        val big = PreviewGeometry.applyGesture(ClipTransform(scaleX = 19.0, scaleY = 19.0), 0.0, 0.0, 10.0, 0.0)
        val small = PreviewGeometry.applyGesture(ClipTransform(scaleX = 0.06, scaleY = 0.06), 0.0, 0.0, 0.01, 0.0)

        assertEquals(ClipTransform.MAX_SCALE, big.scaleX, 0.0)
        assertEquals(ClipTransform.MIN_SCALE, small.scaleY, 0.0)
        assertEquals(null, big.problem())
        assertEquals(null, small.problem())
    }

    @Test
    fun `rotation wraps into minus 180 to 180`() {
        assertEquals(-170.0, PreviewGeometry.normalizeDegrees(190.0), 1e-9)
        assertEquals(180.0, PreviewGeometry.normalizeDegrees(-180.0), 1e-9)
        assertEquals(10.0, PreviewGeometry.normalizeDegrees(730.0), 1e-9)
        assertEquals(0.0, PreviewGeometry.normalizeDegrees(-360.0), 1e-9)
    }

    @Test
    fun `non finite gesture input is ignored`() {
        val base = ClipTransform(positionX = 3.0, scaleX = 2.0, scaleY = 2.0, rotationDegrees = 15.0)

        val result = PreviewGeometry.applyGesture(base, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.NaN)

        assertEquals(base, result)
    }

    @Test
    fun `layers come bottom first with the first video track on top`() {
        val timeline = Timeline(
            listOf(
                Track("v2", TrackType.VIDEO, listOf(clip("top", 0, 100))),
                Track("v1", TrackType.VIDEO, listOf(clip("bottom", 0, 100))),
                Track("a1", TrackType.AUDIO, listOf(clip("sound", 0, 100))),
            ),
        )

        val layers = previewLayersAt(timeline, FrameIndex(10))

        assertEquals(listOf("bottom", "top"), layers.map { it.clip.id })
        assertEquals("top", previewTargetAt(timeline, FrameIndex(10))!!.clip.id)
    }

    @Test
    fun `a gap on one track leaves the other layers`() {
        val timeline = Timeline(
            listOf(
                Track("v2", TrackType.VIDEO, listOf(clip("late", 50, 50))),
                Track("v1", TrackType.VIDEO, listOf(clip("base", 0, 100, srcIn = 20))),
            ),
        )

        val layers = previewLayersAt(timeline, FrameIndex(10))

        assertEquals(listOf("base"), layers.map { it.clip.id })
        assertEquals(30L, layers.single().sourceFrame)
        assertTrue(previewLayersAt(timeline, FrameIndex(100)).isEmpty())
    }

    @Test
    fun `clips without media are not layers`() {
        val timeline = Timeline(listOf(Track("v1", TrackType.VIDEO, listOf(clip("title", 0, 100, asset = null)))))

        assertTrue(previewLayersAt(timeline, FrameIndex(10)).isEmpty())
    }

    @Test
    fun `a quarter turn adds 90 degrees and wraps into minus 180 to 180`() {
        assertEquals(90.0, PreviewGeometry.turnBy(0.0, 90.0), 1e-9)
        assertEquals(-90.0, PreviewGeometry.turnBy(0.0, -90.0), 1e-9)
        assertEquals(180.0, PreviewGeometry.turnBy(90.0, 90.0), 1e-9)
        assertEquals(-90.0, PreviewGeometry.turnBy(180.0, 90.0), 1e-9)
        assertEquals(90.0, PreviewGeometry.turnBy(-180.0, -90.0), 1e-9)
        assertEquals(107.0, PreviewGeometry.turnBy(17.0, 90.0), 1e-9)
        assertEquals(0.0, PreviewGeometry.turnBy(PreviewGeometry.turnBy(PreviewGeometry.turnBy(PreviewGeometry.turnBy(0.0, 90.0), 90.0), 90.0), 90.0), 1e-9)
    }
}
