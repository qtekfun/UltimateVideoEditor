package com.ultimatevideo.uveditor.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPresetsTest {

    @Test
    fun `aspect ratios reduce`() {
        assertEquals("16:9", aspectLabelOf(1920, 1080))
        assertEquals("16:9", aspectLabelOf(1280, 720))
        assertEquals("16:9", aspectLabelOf(3840, 2160))
        assertEquals("9:16", aspectLabelOf(1080, 1920))
        assertEquals("1:1", aspectLabelOf(1080, 1080))
        assertEquals("4:5", aspectLabelOf(1080, 1350))
        assertThrows(IllegalArgumentException::class.java) { aspectLabelOf(0, 10) }
    }

    @Test
    fun `social formats are offered at their usual sizes`() {
        val sizes = ProjectPresets.resolutions.map { it.width to it.height }

        assertTrue(1080 to 1920 in sizes)  // TikTok, Shorts, Reels
        assertTrue(1080 to 1080 in sizes)
        assertTrue(1080 to 1350 in sizes)  // Instagram feed 4:5
        assertTrue(1920 to 1080 in sizes)
    }

    @Test
    fun `groups cover every preset once and keep their shape`() {
        val grouped = ProjectPresets.resolutionGroups.flatMap { it.presets }

        assertEquals(ProjectPresets.resolutions.toSet(), grouped.toSet())
        assertEquals(ProjectPresets.resolutions.size, grouped.size)
        for (group in ProjectPresets.resolutionGroups) {
            assertEquals(1, group.presets.map { it.aspectLabel }.toSet().size)
        }
        assertEquals(listOf("16:9", "9:16", "1:1", "4:5"), ProjectPresets.resolutionGroups.map { it.presets.first().aspectLabel })
    }

    @Test
    fun `all sizes are even so they can be encoded`() {
        assertTrue(ProjectPresets.resolutions.all { it.width % 2 == 0 && it.height % 2 == 0 })
    }

    @Test
    fun `the default stays 1080p`() {
        assertEquals(1920 to 1080, ProjectPresets.defaultResolution.width to ProjectPresets.defaultResolution.height)
    }
}
