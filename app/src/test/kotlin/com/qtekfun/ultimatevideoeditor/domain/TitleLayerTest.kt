package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLayerTest {
    private val plain = TitleContent("Hello", sizeFraction = 0.1, colorArgb = 0xFF112233.toInt(), alignment = TitleAlignment.LEFT, bold = true, outline = true)

    @Test
    fun `a plain title is one text layer with the same look`() {
        val layers = TitleLayers.of(plain)
        val text = layers.single() as TextLayer
        assertEquals("Hello", text.text)
        assertEquals(0.1, text.sizeFraction, 0.0)
        assertEquals(0xFF112233.toInt(), text.colorArgb)
        assertEquals(TitleAlignment.LEFT, text.alignment)
        assertTrue(text.bold)
        assertNotNull(text.border)
    }

    @Test
    fun `converting keeps the text mirrored and refuses captions with word timing`() {
        val layered = TitleLayerEdit.toLayered(plain)
        assertTrue(layered.isLayered)
        assertEquals("Hello", layered.text)
        assertNull(layered.problem())
        // Converting twice changes nothing.
        assertSame(layered, TitleLayerEdit.toLayered(layered))

        val caption = plain.copy(words = listOf(TitleWord("Hello", 0, 10)), animation = TitleAnimation.KARAOKE)
        assertFalse(TitleLayerEdit.canConvert(caption))
        assertTrue(TitleLayerEdit.canConvert(plain))
    }

    @Test
    fun `layers are added on top, replaced, moved, duplicated and removed`() {
        var c = TitleLayerEdit.toLayered(TitleContent("A"))
        c = TitleLayerEdit.add(c, TitleLayerEdit.newShape(ShapeKind.ELLIPSE))
        c = TitleLayerEdit.add(c, TitleLayerEdit.newText("B"))
        assertEquals(listOf("Text: A", "Ellipse", "Text: B"), c.layers.map { it.label })
        assertEquals("A", c.text)

        // Moving the first text above the others changes the first text layer, so the mirror follows.
        c = TitleLayerEdit.move(c, 0, 10)
        assertEquals(listOf("Ellipse", "Text: B", "Text: A"), c.layers.map { it.label })
        assertEquals("B", c.text)
        c = TitleLayerEdit.move(c, 2, -1)
        assertEquals(listOf("Ellipse", "Text: A", "Text: B"), c.layers.map { it.label })

        c = TitleLayerEdit.replace(c, 1, (c.layers[1] as TextLayer).copy(text = "Z"))
        assertEquals("Z", c.text)

        c = TitleLayerEdit.duplicate(c, 0)
        assertEquals(4, c.layers.size)
        assertEquals(c.layers[0].placement.offsetX + 0.03, c.layers[1].placement.offsetX, 1e-12)

        c = TitleLayerEdit.remove(c, 0)
        assertEquals(3, c.layers.size)
        assertNull(c.problem())
    }

    @Test
    fun `the last layer cannot be removed and indexes out of range do nothing`() {
        val one = TitleLayerEdit.toLayered(TitleContent("A"))
        assertSame(one, TitleLayerEdit.remove(one, 0))
        assertSame(one, TitleLayerEdit.remove(one, 5))
        assertSame(one, TitleLayerEdit.move(one, 3, 1))
        assertSame(one, TitleLayerEdit.replace(one, -1, TitleLayerEdit.newText()))
        assertSame(one, TitleLayerEdit.move(one, 0, 1))
    }

    @Test
    fun `a title holds at most the maximum number of layers`() {
        var c = TitleLayerEdit.toLayered(TitleContent("A"))
        repeat(TitleLayers.MAX_LAYERS + 5) { c = TitleLayerEdit.add(c, TitleLayerEdit.newShape(ShapeKind.RECT)) }
        assertEquals(TitleLayers.MAX_LAYERS, c.layers.size)
        assertNull(c.problem())
        assertEquals(TitleLayers.MAX_LAYERS, TitleLayerEdit.duplicate(c, 0).layers.size)
        assertNotNull(c.copy(layers = c.layers + TitleLayerEdit.newText()).problem())
    }

    @Test
    fun `a layered title may have no text but every layer is validated`() {
        val shapes = TitleLayerEdit.of(listOf(TitleLayerEdit.newShape(ShapeKind.RECT)))
        assertEquals("", shapes.text)
        assertNull(shapes.problem())

        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", sizeFraction = 9.0))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", letterSpacing = 5.0))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", lineHeight = 0.0))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", border = LayerStroke(0, 0.9)))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", box = LayerBox(0, 0.9, 0.0)))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", shadow = LayerShadow(0, 0.9, 0.0, 0.0)))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(ShapeLayer(widthFraction = 0.0))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(ShapeLayer(cornerRadiusFraction = 0.9))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(ImageLayer(StillKind.STICKER, ""))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(ImageLayer(StillKind.STICKER, "x", sizeFraction = 0.0))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", placement = LayerPlacement(scale = 0.0)))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", placement = LayerPlacement(opacity = 2.0)))).problem())
        assertNotNull(TitleLayerEdit.of(listOf(TextLayer("x", placement = LayerPlacement(offsetX = Double.NaN)))).problem())
    }

    @Test
    fun `photo layers are resolved from the media library and stickers are left alone`() {
        val content = TitleLayerEdit.of(
            listOf(
                ImageLayer(StillKind.PHOTO, "asset-1"),
                ImageLayer(StillKind.STICKER, "emoji:1f600"),
                ImageLayer(StillKind.PHOTO, "gone"),
            ),
        )
        val resolved = TitleLayers.resolved(content) { id -> if (id == "asset-1") "content://x/1" else null }
        assertEquals("content://x/1", (resolved.layers[0] as ImageLayer).resolvedUri)
        assertNull((resolved.layers[1] as ImageLayer).resolvedUri)
        assertNull((resolved.layers[2] as ImageLayer).resolvedUri)
        // Nothing to resolve: the very same object comes back.
        val text = TitleLayerEdit.toLayered(TitleContent("x"))
        assertSame(text, TitleLayers.resolved(text) { "unused" })
    }

    @Test
    fun `fonts in use are listed and missing ones are reported`() {
        val c = TitleLayerEdit.of(listOf(TextLayer("a", fontId = "f1"), TextLayer("b", fontId = "f2"), TextLayer("c")))
        assertEquals(setOf("f1", "f2"), TitleLayers.fontIds(c))
        assertEquals(setOf("f2"), TitleLayerEdit.missingFonts(c, setOf("f1")))
        assertEquals(emptySet<String>(), TitleLayerEdit.missingFonts(TitleContent("plain"), emptySet()))
    }

    @Test
    fun `layered titles work as clip content and set title is validated`() {
        val layered = TitleLayerEdit.toLayered(TitleContent("Hello"))
        val base = timeline(
            track("t1", Clip("title", null, f(0), f(0), f(60), title = TitleContent("Hello")), type = TrackType.TITLE),
        )
        val result = TimelineOps.setTitle(base, "title", layered).getOrFail()
        assertTrue(result.invariantViolations().isEmpty())
        assertTrue(result.track("t1")!!.clips.single().title!!.isLayered)
        val broken = layered.copy(layers = listOf(TextLayer("x", sizeFraction = 9.0)))
        assertTrue(TimelineOps.setTitle(base, "title", broken).errorOrFail() is EditError.InvalidAppearance)
        // One undo step.
        val history = (EditHistory(base).execute(EditCommand.SetTitle("title", layered)) as EditResult.Success).value
        assertEquals(1, history.undoDepth)
        assertEquals(base, history.undo().timeline)
    }
}

class TitleMotionTest {
    private val rest = ClipTransform(positionX = 10.0, positionY = 20.0)

    @Test
    fun `no presets or a tiny clip give no keyframes`() {
        assertEquals(emptyList<Keyframe>(), TitleMotion.keyframes(rest, 100, 1920, 1080, MotionPreset.NONE, MotionPreset.NONE, 10))
        assertEquals(emptyList<Keyframe>(), TitleMotion.keyframes(rest, 3, 1920, 1080, MotionPreset.FADE, MotionPreset.FADE, 1))
    }

    @Test
    fun `an intro starts away from rest and settles after the edge`() {
        val keys = TitleMotion.keyframes(rest, 100, 1920, 1080, MotionPreset.SLIDE_LEFT, MotionPreset.NONE, 12)
        assertEquals(listOf(0L, 12L), keys.map { it.frame })
        assertEquals(10.0 - 0.6 * 1920, keys[0].transform.positionX, 1e-9)
        assertEquals(0.0, keys[0].transform.opacity, 0.0)
        assertEquals(rest, keys[1].transform)
        assertNull(Keyframes.problem(keys, 100))
    }

    @Test
    fun `an outro alone rests from the first frame`() {
        val keys = TitleMotion.keyframes(rest, 100, 1920, 1080, MotionPreset.NONE, MotionPreset.FADE, 10)
        assertEquals(listOf(0L, 89L, 99L), keys.map { it.frame })
        assertEquals(rest, keys[0].transform)
        assertEquals(0.0, keys.last().transform.opacity, 0.0)
    }

    @Test
    fun `the edge is shortened so the intro and outro never meet`() {
        for (duration in 4L..40L) {
            val keys = TitleMotion.keyframes(rest, duration, 1000, 1000, MotionPreset.POP, MotionPreset.SLIDE_UP, 1000)
            assertNull("duration $duration", Keyframes.problem(keys, duration))
            assertEquals(keys.map { it.frame }, keys.map { it.frame }.sorted().distinct())
        }
    }

    @Test
    fun `every preset starts somewhere visibly different from rest`() {
        for (preset in MotionPreset.entries) {
            val pose = TitleMotion.startPose(rest, preset, 1920, 1080)
            if (preset == MotionPreset.NONE) assertEquals(rest, pose) else assertTrue(preset.name, pose != rest)
        }
    }

    @Test
    fun `the command replaces keyframes in one undo step and refuses non titles`() {
        val base = timeline(
            track("t1", Clip("title", null, f(0), f(0), f(90), title = TitleContent("Hi")), type = TrackType.TITLE),
            track("v1", clip("a", 0, 100)),
        )
        val cmd = SetTitleMotion("title", MotionPreset.FADE, MotionPreset.FADE, 9, 1920, 1080)
        val history = (EditHistory(base).execute(cmd) as EditResult.Success).value
        assertEquals(4, history.timeline.track("t1")!!.clips.single().keyframes.size)
        assertEquals(base, history.undo().timeline)

        val none = SetTitleMotion("title", MotionPreset.NONE, MotionPreset.NONE, 9, 1920, 1080).apply(history.timeline).getOrFail()
        assertEquals(emptyList<Keyframe>(), none.track("t1")!!.clips.single().keyframes)

        assertTrue(SetTitleMotion("a", MotionPreset.FADE, MotionPreset.NONE, 9, 1920, 1080).apply(base).errorOrFail() is EditError.NotATitle)
        assertTrue(SetTitleMotion("zz", MotionPreset.FADE, MotionPreset.NONE, 9, 1920, 1080).apply(base).errorOrFail() is EditError.ClipNotFound)
    }
}
