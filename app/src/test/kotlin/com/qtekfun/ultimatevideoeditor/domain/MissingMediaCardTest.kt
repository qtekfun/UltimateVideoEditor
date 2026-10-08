package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingMediaCardTest {

    private val shapes = listOf(1920 to 1080, 1080 to 1920, 1080 to 1080, 3840 to 2160, 2 to 2000)

    @Test
    fun `the card is a valid title on every canvas shape`() {
        for ((w, h) in shapes) {
            val card = MissingMediaCard.contentFor("clip.mp4", w, h)
            assertNull("${w}x$h", card.problem())
            assertTrue("${w}x$h", card.isLayered)
            assertTrue("${w}x$h", card.layers.size <= TitleLayers.MAX_LAYERS)
        }
    }

    @Test
    fun `the hatch bands reach across the canvas and none is hidden off it`() {
        for ((w, h) in shapes.filter { it.second.toDouble() / it.first <= 3.0 }) {
            val bands = MissingMediaCard.contentFor("x", w, h).layers.filterIsInstance<ShapeLayer>().filter { it.kind == ShapeKind.LINE }
            val reach = (1.0 + h.toDouble() / w) / 2.0
            assertEquals("${w}x$h", -reach, bands.first().placement.offsetX, 1e-9)
            assertEquals("${w}x$h", reach, bands.last().placement.offsetX, 1e-9)
            assertTrue(bands.all { it.placement.rotationDegrees == -45.0 })
        }
    }

    @Test
    fun `it names the file and says Media missing`() {
        val texts = MissingMediaCard.contentFor("Holiday 2026.mp4", 1920, 1080).layers.filterIsInstance<TextLayer>().map { it.text }

        assertEquals(listOf("Media missing", "Holiday 2026.mp4"), texts)
    }

    @Test
    fun `a very long or empty name is kept short and readable`() {
        val long = MissingMediaCard.contentFor("x".repeat(300), 1920, 1080).layers.filterIsInstance<TextLayer>().last().text
        val empty = MissingMediaCard.contentFor("  ", 1920, 1080).layers.filterIsInstance<TextLayer>().last().text
        val multi = MissingMediaCard.contentFor("a\nb", 1920, 1080).layers.filterIsInstance<TextLayer>().last().text

        assertEquals(48, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("(unnamed file)", empty)
        assertEquals("a b", multi)
    }

    @Test
    fun `only cards are recognised as cards`() {
        assertTrue(MissingMediaCard.isCard(MissingMediaCard.contentFor("a", 1920, 1080)))
        assertFalse(MissingMediaCard.isCard(TitleContent("Hello")))
        assertFalse(MissingMediaCard.isCard(TitleContent("Hello", layers = listOf(TextLayer("Hello")))))
    }

    @Test
    fun `the same inputs give an equal card so one picture is cached`() {
        assertEquals(MissingMediaCard.contentFor("a", 1920, 1080), MissingMediaCard.contentFor("a", 1920, 1080))
    }
}
