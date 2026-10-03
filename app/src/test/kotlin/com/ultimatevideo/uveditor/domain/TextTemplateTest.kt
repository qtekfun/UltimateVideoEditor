package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.engine.still.StickerIds
import com.ultimatevideo.uveditor.engine.still.StillFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        ids: List<String> = (1..6).map { "id$it" },
        width: Int = 1920,
        height: Int = 1080,
    ) = AddTextTemplate(template, text, f(start), length, width, height, fps, ids)

    private fun lowerThird(ids: List<String> = listOf("bar", "txt", "title-lane", "overlay-lane")) =
        add("lower-third", ids = ids).apply(base).getOrFail()

    @Test
    fun `a lower third adds an animated bar on a new overlay lane and text on a new title lane`() {
        val result = lowerThird()
        assertTrue(result.invariantViolations().isEmpty())
        // Title lane first (top), then the new overlay lane above the base, then the base.
        assertEquals(listOf("title-lane", "overlay-lane", "v1"), result.tracks.map { it.id })
        val bar = result.track("overlay-lane")!!.clips.single()
        val text = result.track("title-lane")!!.clips.single()
        assertEquals(StillKind.STICKER, bar.still)
        assertEquals(TemplateBars.ACCENT, bar.assetId)
        assertEquals("Hello", text.title!!.text)
        assertEquals(30L to 150L, bar.timelineStart.value to bar.timelineEnd.value)
        assertEquals(30L to 150L, text.timelineStart.value to text.timelineEnd.value)
    }

    @Test
    fun `the bar is sized from the canvas and the text is placed at the template's centre`() {
        val result = lowerThird()
        val bar = result.track("overlay-lane")!!.clips.single()
        // A sticker is a 378 px square on a 1080 px canvas (35 %): the bar is stretched to 52 % of 1920 wide and 11 % of 1080 tall.
        assertEquals(0.52 * 1920 / 378, bar.transform.scaleX, 1e-9)
        assertEquals(0.11 * 1080 / 378, bar.transform.scaleY, 1e-9)
        assertEquals(-0.22 * 1920, bar.transform.positionX, 1e-9)
        assertEquals(0.30 * 1080, bar.transform.positionY, 1e-9)
    }

    @Test
    fun `keyframes slide in from the side and fade out at the end`() {
        val bar = lowerThird().track("overlay-lane")!!.clips.single()
        val keys = bar.keyframes
        assertEquals(0L, keys.first().frame)
        assertEquals(0.0, keys.first().transform.opacity, 0.0)
        assertEquals(bar.transform.positionX - 0.7 * 1920, keys.first().transform.positionX, 1e-9)
        assertEquals(119L, keys.last().frame)
        assertEquals(0.0, keys.last().transform.opacity, 0.0)
        // Held in the middle: at 0.4 s (frame 12) fully in place.
        val settled = keys.first { it.frame == 12L }
        assertEquals(bar.transform.positionX, settled.transform.positionX, 1e-9)
        assertEquals(1.0, settled.transform.opacity, 0.0)
        assertEquals(keys.map { it.frame }, keys.map { it.frame }.sorted().distinct())
    }

    @Test
    fun `a pop title only needs a title lane and scales in`() {
        val result = add("pop-title").apply(base).getOrFail()
        // One layer, so the title lane's id is the second id handed in.
        assertEquals(listOf("id2", "v1"), result.tracks.map { it.id })
        assertEquals(1, result.tracks.count { it.type == TrackType.TITLE })
        assertEquals(1, result.tracks.count { it.type == TrackType.VIDEO })
        val text = result.tracks.first { it.type == TrackType.TITLE }.clips.single()
        assertEquals(0.3, text.keyframes.first().transform.scaleX, 1e-9)
        assertEquals(1.18, text.keyframes.first { it.frame == 5L }.transform.scaleX, 1e-9)
    }

    @Test
    fun `lanes are reused when they are free and added when they are busy`() {
        val first = add("subtitle-bar", start = 0, length = 90, ids = listOf("b1", "t1", "tl1", "ol1")).apply(base).getOrFail()
        assertEquals(3, first.tracks.size)
        // Another template later in time reuses both lanes.
        val later = add("subtitle-bar", start = 120, length = 90, ids = listOf("b2", "t2", "tl2", "ol2")).apply(first).getOrFail()
        assertEquals(3, later.tracks.size)
        assertEquals(2, later.tracks.first { it.type == TrackType.TITLE }.clips.size)
        // One that overlaps the first needs fresh lanes, so the first one's clips are untouched.
        val clash = add("subtitle-bar", start = 30, length = 90, ids = listOf("b3", "t3", "tl3", "ol3")).apply(later).getOrFail()
        assertEquals(5, clash.tracks.size)
        assertEquals(later.track("tl1"), clash.track("tl1"))
        assertEquals(later.track("ol1"), clash.track("ol1"))
    }

    @Test
    fun `an existing overlay lane is used but the base never is`() {
        val t = timeline(track("v2"), track("v1", clip("a", 0, 600)))
        val result = add("subtitle-bar", ids = listOf("b", "t", "tl", "ol")).apply(t).getOrFail()
        assertEquals(listOf("tl", "v2", "v1"), result.tracks.map { it.id })
        assertEquals(1, result.track("v2")!!.clips.size)
        assertEquals(1, result.track("v1")!!.clips.size)
    }

    @Test
    fun `applying a template is one undo step`() {
        var history = EditHistory(base)
        history = (history.execute(add("lower-third", ids = listOf("a1", "a2", "a3", "a4"))) as EditResult.Success).value
        assertEquals(1, history.undoDepth)
        assertEquals(3, history.timeline.tracks.size)
        assertEquals(base, history.undo().timeline)
    }

    @Test
    fun `every template stays valid for any clip length and canvas`() {
        for (template in TextTemplates.all) {
            for (length in listOf(2L, 3L, 6L, 15L, 45L, 120L, 900L)) {
                for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1080)) {
                    val ids = (1..TextTemplates.idCount(template)).map { "x$it" }
                    val result = AddTextTemplate(template.id, "T", f(10), length, w, h, fps, ids).apply(base)
                    val timeline = result.getOrFail()
                    assertEquals("${template.id} $length ${w}x$h", emptyList<String>(), timeline.invariantViolations())
                }
            }
        }
    }

    @Test
    fun `bad requests are rejected with a reason`() {
        assertTrue(add("nope").apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("pop-title", length = 1).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("pop-title", width = 0).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("lower-third", ids = listOf("a", "b")).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertTrue(add("lower-third", ids = listOf("a", "a", "b", "c")).apply(base).errorOrFail() is EditError.InvalidTemplate)
        assertEquals(EditError.NegativeStart, add("pop-title", start = -5).apply(base).errorOrFail())
    }

    @Test
    fun `the bar scale uses the same sticker size as the still rasteriser`() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1080, 100 to 100)) {
            val expected = StillFit.stickerSide(w, h)
            val side = maxOf(TemplateBars.MIN_STICKER_SIDE, Math.round(minOf(w, h) * TemplateBars.STICKER_SIDE_FRACTION).toInt())
            assertEquals("${w}x$h", expected, side)
        }
        assertEquals(StillFit.STICKER_FRACTION, TemplateBars.STICKER_SIDE_FRACTION, 0.0)
    }

    @Test
    fun `template bars are known stickers`() {
        for (template in TextTemplates.all) {
            for (layer in template.layers.filter { it.kind == TemplateLayerKind.BAR }) {
                assertTrue(StickerIds.isKnown(layer.barSticker))
            }
        }
        assertNotNull(TextTemplates.find("lower-third"))
        assertEquals(TextTemplates.all.size, TextTemplates.all.map { it.id }.toSet().size)
    }
}
