package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTemplateTest {
    private val fps = FrameRate(30, 1)
    private val base = timeline(track("v1", clip("a", 0, 600)))

    private fun add(
        template: String,
        start: Long = 30,
        length: Long = 120,
        text: String = "Hello",
        ids: List<String> = listOf("clip", "lane"),
        width: Int = 1920,
        height: Int = 1080,
    ) = AddTextTemplate(template, text, f(start), length, width, height, fps, ids)

    @Test
    fun `a lower third is one multilayer title clip on a new title lane`() {
        val result = add("lower-third").apply(base).getOrFail()
        assertTrue(result.invariantViolations().isEmpty())
        assertEquals(listOf("lane", "v1"), result.tracks.map { it.id })
        val clip = result.track("lane")!!.clips.single()
        assertEquals(30L to 150L, clip.timelineStart.value to clip.timelineEnd.value)
        val title = clip.title!!
        assertTrue(title.isLayered)
        assertEquals(2, title.layers.size)
        // The bar is the bottom layer and the typed text is in the text layer above it.
        assertTrue(title.layers[0] is ShapeLayer)
        assertEquals("Hello", (title.layers[1] as TextLayer).text)
        assertEquals("Hello", title.text)
        assertNull(title.problem())
    }

    @Test
    fun `the bar keeps the template's size and place as fractions of the canvas`() {
        val title = add("lower-third").apply(base).getOrFail().track("lane")!!.clips.single().title!!
        val bar = title.layers[0] as ShapeLayer
        assertEquals(0.52, bar.widthFraction, 0.0)
        assertEquals(0.11, bar.heightFraction, 0.0)
        assertEquals(-0.22, bar.placement.offsetX, 0.0)
        assertEquals(0.30, bar.placement.offsetY, 0.0)
    }

    @Test
    fun `the intro and outro become keyframes that slide in and fade out`() {
        val clip = add("lower-third").apply(base).getOrFail().track("lane")!!.clips.single()
        val keys = clip.keyframes
        assertEquals(0L, keys.first().frame)
        assertEquals(0.0, keys.first().transform.opacity, 0.0)
        assertEquals(-0.6 * 1920, keys.first().transform.positionX, 1e-9)
        assertEquals(119L, keys.last().frame)
        assertEquals(0.0, keys.last().transform.opacity, 0.0)
        // Fully in place after the 0.5 s edge (frame 15), held until the outro starts.
        val settled = keys.first { it.frame == 15L }
        assertEquals(0.0, settled.transform.positionX, 0.0)
        assertEquals(1.0, settled.transform.opacity, 0.0)
        assertEquals(keys.map { it.frame }, keys.map { it.frame }.sorted().distinct())
    }

    @Test
    fun `a pop title scales in`() {
        val clip = add("pop-title").apply(base).getOrFail().track("lane")!!.clips.single()
        assertEquals(0.3, clip.keyframes.first().transform.scaleX, 1e-9)
        assertEquals(1.0, clip.keyframes.first { it.frame == 9L }.transform.scaleX, 1e-9)
    }

    @Test
    fun `lanes are reused when they are free and added when they are busy`() {
        val first = add("subtitle-bar", start = 0, length = 90, ids = listOf("t1", "tl1")).apply(base).getOrFail()
        assertEquals(2, first.tracks.size)
        // Another template later in time reuses the lane.
        val later = add("subtitle-bar", start = 120, length = 90, ids = listOf("t2", "tl2")).apply(first).getOrFail()
        assertEquals(2, later.tracks.size)
        assertEquals(2, later.tracks.first { it.type == TrackType.TITLE }.clips.size)
        // One that overlaps the first needs a fresh lane, so the first one's clips are untouched.
        val clash = add("subtitle-bar", start = 30, length = 90, ids = listOf("t3", "tl3")).apply(later).getOrFail()
        assertEquals(3, clash.tracks.size)
        assertEquals(later.track("tl1"), clash.track("tl1"))
    }

    @Test
    fun `the base is never used as a template lane`() {
        val t = timeline(track("t0", type = TrackType.TITLE), track("v1", clip("a", 0, 600)))
        val result = add("subtitle-bar", ids = listOf("c", "l")).apply(t).getOrFail()
        assertEquals(listOf("t0", "v1"), result.tracks.map { it.id })
        assertEquals(1, result.track("t0")!!.clips.size)
        assertEquals(1, result.track("v1")!!.clips.size)
    }

    @Test
    fun `applying a template is one undo step`() {
        var history = EditHistory(base)
        history = (history.execute(add("lower-third", ids = listOf("a1", "a2"))) as EditResult.Success).value
        assertEquals(1, history.undoDepth)
        assertEquals(2, history.timeline.tracks.size)
        assertEquals(base, history.undo().timeline)
    }

    @Test
    fun `every template stays valid for any clip length and canvas`() {
        for (template in TextTemplates.all) {
            assertNull(template.id, template.problem())
            for (length in listOf(2L, 3L, 6L, 15L, 45L, 120L, 900L)) {
                for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1080)) {
                    val ids = (1..TextTemplates.idCount(template)).map { "x$it" }
                    val timeline = AddTextTemplate(template.id, "T", f(10), length, w, h, fps, ids).apply(base).getOrFail()
                    assertEquals("${template.id} $length ${w}x$h", emptyList<String>(), timeline.invariantViolations())
                }
            }
        }
    }

    @Test
    fun `a saved preset can be applied by object and an empty text keeps its own`() {
        val preset = TextTemplates.all.first().copy(id = "mine", name = "Mine", builtIn = false)
        val cmd = AddTextTemplate("mine", preset, "  ", f(0), 90, 1920, 1080, fps, listOf("c", "l"))
        val title = cmd.apply(base).getOrFail().track("l")!!.clips.single().title!!
        assertEquals(preset.defaultText, title.text)
    }

    @Test
    fun `bad requests are rejected with a reason`() {
        assertTrue(add("nope").apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("pop-title", length = 1).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("pop-title", width = 0).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("lower-third", ids = listOf("a")).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("lower-third", ids = listOf("a", "a")).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertEquals(EditError.NegativeStart, add("pop-title", start = -5).apply(base).errorOrFail())
    }

    @Test
    fun `templates have unique ids and are findable`() {
        assertNotNull(TextTemplates.find("lower-third"))
        assertEquals(TextTemplates.all.size, TextTemplates.all.map { it.id }.toSet().size)
    }
}
