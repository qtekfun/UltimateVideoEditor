package com.qtekfun.ultimatevideoeditor.engine.title

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.LayerPlacement
import com.qtekfun.ultimatevideoeditor.domain.LayerShadow
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drawing of multilayer titles on a device. Kept next to the plain-title tests; needs a real text stack and canvas. */
@RunWith(AndroidJUnit4::class)
class LayeredTitleRasterizerInstrumentedTest {

    /** A solid green square as the "photo" of every image layer, and a record of the sizes asked for. */
    private class SolidImages(val missing: Boolean = false) : LayerImages {
        val requested = mutableListOf<Int>()

        override fun load(layer: ImageLayer, targetLongSidePx: Int): Bitmap? {
            requested += targetLongSidePx
            if (missing) return null
            return Bitmap.createBitmap(targetLongSidePx, targetLongSidePx, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.GREEN) }
        }
    }

    private fun rasterizer(images: LayerImages = LayerImages.NONE) = AndroidTitleRasterizer(images, FontResolver.SYSTEM)

    private fun alphaAt(bitmap: TitleBitmap, x: Int, y: Int): Int = bitmap.pixels.get((y * bitmap.width + x) * 4 + 3).toInt() and 0xFF

    private fun opaqueBounds(bitmap: TitleBitmap): IntArray {
        var minX = bitmap.width
        var maxX = -1
        var minY = bitmap.height
        var maxY = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (alphaAt(bitmap, x, y) > 128) {
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }
        return intArrayOf(minX, maxX, minY, maxY)
    }

    @Test
    fun aCentredBarIsCroppedToItsSizeAndFilledWithItsColour() {
        val bar = ShapeLayer(ShapeKind.RECT, widthFraction = 0.5, heightFraction = 0.1, fillArgb = 0xFFFFB300.toInt(), cornerRadiusFraction = 0.0)
        val bitmap = rasterizer().rasterize(TitleLayerEdit.of(listOf(bar)), 1920, 1080)

        // 0.5 x 1920 = 960 wide, 0.1 x 1080 = 108 tall, plus a couple of pixels of antialiasing margin.
        assertTrue("width ${bitmap.width}", bitmap.width in 960..970)
        assertTrue("height ${bitmap.height}", bitmap.height in 108..118)
        val i = ((bitmap.height / 2) * bitmap.width + bitmap.width / 2) * 4
        assertEquals(0xFF, bitmap.pixels.get(i).toInt() and 0xFF)
        assertEquals(0xB3, bitmap.pixels.get(i + 1).toInt() and 0xFF)
        assertEquals(0x00, bitmap.pixels.get(i + 2).toInt() and 0xFF)
        assertEquals(255, alphaAt(bitmap, bitmap.width / 2, bitmap.height / 2))
    }

    @Test
    fun theBitmapStaysCentredOnTheCanvasWhateverTheOffset() {
        val bar = ShapeLayer(ShapeKind.RECT, widthFraction = 0.2, heightFraction = 0.1, placement = LayerPlacement(offsetX = -0.3, offsetY = 0.2))
        val bitmap = rasterizer().rasterize(TitleLayerEdit.of(listOf(bar)), 1920, 1080)
        val (minX, maxX) = opaqueBounds(bitmap).let { it[0] to it[1] }

        // Symmetric around the canvas centre, so the opaque part sits left of and below the middle.
        assertTrue("opaque spans $minX..$maxX of ${bitmap.width}", maxX < bitmap.width / 2)
        val (minY, _) = opaqueBounds(bitmap).let { it[2] to it[3] }
        assertTrue("top $minY of ${bitmap.height}", minY > bitmap.height / 2)
    }

    @Test
    fun layersDrawBottomToTopAndOpacityFadesOne() {
        val bottom = ShapeLayer(ShapeKind.RECT, 0.3, 0.3, fillArgb = 0xFFFF0000.toInt(), cornerRadiusFraction = 0.0)
        val top = ShapeLayer(ShapeKind.RECT, 0.2, 0.2, fillArgb = 0xFF0000FF.toInt(), cornerRadiusFraction = 0.0)
        val solid = rasterizer().rasterize(TitleLayerEdit.of(listOf(bottom, top)), 1000, 1000)
        val cx = solid.width / 2
        val cy = solid.height / 2
        val blue = (cy * solid.width + cx) * 4
        assertEquals(0, solid.pixels.get(blue).toInt() and 0xFF)
        assertEquals(255, solid.pixels.get(blue + 2).toInt() and 0xFF)

        val faded = rasterizer().rasterize(TitleLayerEdit.of(listOf(bottom, top.copy(placement = LayerPlacement(opacity = 0.5)))), 1000, 1000)
        val mixed = (cy * faded.width + cx) * 4
        // Half blue over red: both channels are present.
        assertTrue(faded.pixels.get(mixed).toInt() and 0xFF > 60)
        assertTrue(faded.pixels.get(mixed + 2).toInt() and 0xFF > 60)
    }

    @Test
    fun textInsideABoxIsDrawnOverItsBackground() {
        val layer = TextLayer("Hi", sizeFraction = 0.1, colorArgb = 0xFFFFFFFF.toInt(), box = com.qtekfun.ultimatevideoeditor.domain.LayerBox(0xFF202020.toInt(), 0.02, 0.01))
        val bitmap = rasterizer().rasterize(TitleLayerEdit.of(listOf(layer)), 1920, 1080)
        // A dark corner pixel of the box, so the box is drawn even where there is no text.
        assertEquals(255, alphaAt(bitmap, bitmap.width / 2, 8))
    }

    @Test
    fun aMissingPhotoIsSkippedAndTheRestStillDraws() {
        val title = TitleLayerEdit.of(
            listOf(ImageLayer(StillKind.PHOTO, "gone", resolvedUri = "content://x"), ShapeLayer(ShapeKind.RECT, 0.2, 0.1, cornerRadiusFraction = 0.0)),
        )
        val withMissing = rasterizer(SolidImages(missing = true)).rasterize(title, 1920, 1080)
        val onlyShape = rasterizer().rasterize(TitleLayerEdit.of(listOf(title.layers[1])), 1920, 1080)
        assertEquals(onlyShape.width, withMissing.width)
        assertEquals(onlyShape.height, withMissing.height)
    }

    @Test
    fun anImageLayerAsksForItsPictureAtTheDrawnSize() {
        val images = SolidImages()
        val title = TitleLayerEdit.of(listOf(ImageLayer(StillKind.STICKER, "shape:star", sizeFraction = 0.25)))
        val bitmap = rasterizer(images).rasterize(title, 1920, 1080)
        // 0.25 of the shorter side (1080) is 270 px.
        assertEquals(listOf(270), images.requested)
        assertTrue("width ${bitmap.width}", bitmap.width in 270..280)
        assertEquals(255, alphaAt(bitmap, bitmap.width / 2, bitmap.height / 2))
    }

    @Test
    fun aShadowEnlargesTheBitmapAndRotationTurnsTheLayer() {
        val plain = ShapeLayer(ShapeKind.RECT, 0.3, 0.1, cornerRadiusFraction = 0.0)
        val shadowed = plain.copy(shadow = LayerShadow.DEFAULT)
        val a = rasterizer().rasterize(TitleLayerEdit.of(listOf(plain)), 1920, 1080)
        val b = rasterizer().rasterize(TitleLayerEdit.of(listOf(shadowed)), 1920, 1080)
        assertTrue("shadow adds margin: ${a.width}x${a.height} vs ${b.width}x${b.height}", b.width > a.width && b.height > a.height)

        val turned = rasterizer().rasterize(TitleLayerEdit.of(listOf(plain.copy(placement = LayerPlacement(rotationDegrees = 90.0)))), 1920, 1080)
        // 576 x 108 turned a quarter: now tall and narrow.
        assertTrue("turned ${turned.width}x${turned.height}", turned.height > turned.width)
    }

    @Test
    fun theSameLayeredTitleRendersIdentically() {
        val title = TitleLayerEdit.of(
            listOf(
                ShapeLayer(ShapeKind.ROUNDED_RECT, 0.4, 0.1, shadow = LayerShadow.DEFAULT),
                TextLayer("Same", sizeFraction = 0.06, bold = true, italic = true, letterSpacing = 0.05, lineHeight = 1.2),
            ),
        )
        val first = rasterizer().rasterize(title, 1280, 720)
        val second = rasterizer().rasterize(title, 1280, 720)
        assertEquals(first.width, second.width)
        assertEquals(first.pixels, second.pixels)
        assertNotEquals(0, first.pixels.capacity())
    }
}
