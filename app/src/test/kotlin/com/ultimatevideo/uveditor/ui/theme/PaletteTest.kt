package com.ultimatevideo.uveditor.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x checks over every foreground/background pair the app uses: 4.5:1 for text, 3:1 for graphics and controls. */
class PaletteTest {
    private val palettes = listOf("dark" to Palette.Dark, "amoled" to Palette.Amoled)

    private fun assertText(name: String, label: String, fg: Int, bg: Int) {
        val ratio = Contrast.ratio(fg, bg)
        assertTrue("$name: $label has contrast %.2f, needs 4.5".format(ratio), ratio >= 4.5)
    }

    private fun assertGraphic(name: String, label: String, fg: Int, bg: Int) {
        val ratio = Contrast.ratio(fg, bg)
        assertTrue("$name: $label has contrast %.2f, needs 3".format(ratio), ratio >= 3.0)
    }

    @Test
    fun `the contrast maths matches the WCAG reference values`() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(1.0, Contrast.ratio(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
        assertEquals(4.0, Contrast.ratio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.6) // #767676 on white is the 4.5 boundary
        assertEquals(Contrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-12)
    }

    @Test
    fun `text on every surface step is readable`() {
        for ((name, p) in palettes) {
            val surfaces = listOf(
                "background" to p.background, "surface" to p.surface, "surfaceLow" to p.surfaceLow,
                "surfaceContainer" to p.surfaceContainer, "surfaceHigh" to p.surfaceHigh, "surfaceHighest" to p.surfaceHighest,
            )
            for ((surfaceName, bg) in surfaces) {
                assertText(name, "onSurface on $surfaceName", p.onSurface, bg)
                assertText(name, "onSurfaceVariant on $surfaceName", p.onSurfaceVariant, bg)
                assertText(name, "primary on $surfaceName", p.primary, bg)
                assertText(name, "secondary on $surfaceName", p.secondary, bg)
                assertText(name, "tertiary on $surfaceName", p.tertiary, bg)
                assertText(name, "error on $surfaceName", p.error, bg)
                assertGraphic(name, "outline on $surfaceName", p.outline, bg)
            }
        }
    }

    @Test
    fun `text on accent and container colours is readable`() {
        for ((name, p) in palettes) {
            assertText(name, "onPrimary on primary", p.onPrimary, p.primary)
            assertText(name, "onPrimaryContainer on primaryContainer", p.onPrimaryContainer, p.primaryContainer)
            assertText(name, "onSecondary on secondary", p.onSecondary, p.secondary)
            assertText(name, "onSecondaryContainer on secondaryContainer", p.onSecondaryContainer, p.secondaryContainer)
            assertText(name, "onTertiary on tertiary", p.onTertiary, p.tertiary)
            assertText(name, "onTertiaryContainer on tertiaryContainer", p.onTertiaryContainer, p.tertiaryContainer)
            assertText(name, "onError on error", p.onError, p.error)
            assertText(name, "onErrorContainer on errorContainer", p.onErrorContainer, p.errorContainer)
        }
    }

    @Test
    fun `timeline text and marks are readable on their backgrounds`() {
        for ((name, p) in palettes) {
            assertText(name, "ruler text on the ruler", p.rulerText, p.ruler)
            assertGraphic(name, "ticks on the ruler", p.tick, p.ruler)
            assertText(name, "block text on video", p.onClip, p.clipVideo)
            assertText(name, "block text on audio", p.onClip, p.clipAudio)
            assertText(name, "block text on title", p.onClip, p.clipTitle)
            assertText(name, "block text on image", p.onClip, p.clipImage)
            assertText(name, "block text on sticker", p.onClip, p.clipSticker)
            assertText(name, "block text on multicam", p.onClip, p.clipMulticam)
            // The header strip of a block is darker than the block (scaled by 0.72 in the renderer): text must read there too.
            for ((kind, colour) in listOf(p.clipVideo, p.clipAudio, p.clipTitle, p.clipImage, p.clipSticker, p.clipMulticam).withIndex()) {
                assertText(name, "block text on the header strip $kind", p.onClip, scaled(colour, 0.72))
            }
            for ((kind, colour) in listOf(p.clipVideo, p.clipAudio, p.clipTitle, p.clipImage, p.clipSticker, p.clipMulticam).withIndex()) {
                assertGraphic(name, "block $kind on lane A", colour, p.laneA)
                assertGraphic(name, "block $kind on lane B", colour, p.laneB)
            }
            // The playhead and the selection outline stand out from the lanes and from every block colour.
            for ((label, fg) in listOf("playhead" to p.playhead, "selection" to p.selection, "keyframe" to p.keyframe, "marker" to p.marker)) {
                assertGraphic(name, "$label on lane A", fg, p.laneA)
                assertGraphic(name, "$label on the ruler", fg, p.ruler)
            }
            assertGraphic(name, "selection on video blocks", p.selection, p.clipVideo)
            // Text drawn on the playhead tag (the background colour) and on the lane header tab.
            assertText(name, "tag text on the playhead", p.background, p.playhead)
            assertText(name, "lane name on its header tab", p.onSurface, p.surfaceHigh)
        }
    }

    @Test
    fun `the amoled variant is pure black and keeps the surface steps ordered`() {
        val p = Palette.Amoled
        assertEquals(0xFF000000.toInt(), p.background)
        assertEquals(0xFF000000.toInt(), p.surface)
        val steps = listOf(p.surface, p.surfaceLow, p.surfaceContainer, p.surfaceHigh, p.surfaceHighest).map(Contrast::luminance)
        assertEquals(steps.sorted(), steps) // each step is lighter than the one before, so cards still separate from the black
        // The timeline lanes stay distinguishable from the black background.
        assertTrue(Contrast.ratio(p.laneB, p.background) > 1.05)
        assertTrue(Contrast.ratio(p.ruler, p.background) > 1.1)
    }

    @Test
    fun `the dark variant is not pure black and is a different palette`() {
        assertTrue(Palette.Dark.background != 0xFF000000.toInt())
        assertTrue(!Palette.Dark.amoled && Palette.Amoled.amoled)
        assertEquals(Palette.Dark, Palette.of(false))
        assertEquals(Palette.Amoled, Palette.of(true))
        // Only the backgrounds change: the accents and block colours are shared.
        assertEquals(Palette.Dark.primary, Palette.Amoled.primary)
        assertEquals(Palette.Dark.clipVideo, Palette.Amoled.clipVideo)
    }

    @Test
    fun `every colour is opaque`() {
        for ((name, p) in palettes) {
            for (colour in p.nativeColours()) assertEquals("$name has a translucent colour", 0xFF, (colour ushr 24) and 0xFF)
        }
    }

    @Test
    fun `the native list has the length and the order the renderer reads`() {
        val list = Palette.Dark.nativeColours()
        assertEquals(Palette.NATIVE_COLOUR_COUNT, list.size)
        // The first colours are what timeline_theme.h names background, laneA, laneB, ruler: a reorder here must be mirrored there.
        assertEquals(Palette.Dark.background, list[0])
        assertEquals(Palette.Dark.laneA, list[1])
        assertEquals(Palette.Dark.laneB, list[2])
        assertEquals(Palette.Dark.ruler, list[3])
        assertEquals(Palette.Dark.error, list.last())
        assertEquals(Palette.Amoled.background, Palette.Amoled.nativeColours()[0])
    }

    private fun scaled(argb: Int, k: Double): Int {
        fun ch(shift: Int) = (((argb shr shift) and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
