package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.EffectDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.engine.fx.FxWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LutEffectTest {
    private fun lutEffect(key: Int, intensity: Double = 1.0) = Effect("e$key", EffectType.LUT, listOf(key.toDouble(), intensity))

    @Test
    fun `a LUT effect validates its key and intensity`() {
        assertNull(lutEffect(5).problem())
        assertNull(lutEffect(MAX_LUT_KEY.toInt()).problem())
        assertNotNull(lutEffect(0).problem())
        assertNotNull(Effect("e", EffectType.LUT, listOf(MAX_LUT_KEY + 1, 1.0)).problem())
        assertNotNull(lutEffect(5, intensity = 1.5).problem())
        assertNotNull(Effect("e", EffectType.LUT, listOf(5.0)).problem())
    }

    @Test
    fun `adding a LUT is one undo step and the keys are listed`() {
        val t = timeline(track("v1", clip("a", 0, 100)))
        val history = EditHistory(t).execute(EditCommand.AddEffect("a", lutEffect(12345, 0.5))).getOrFail()
        assertEquals(setOf(12345), history.timeline.lutKeys())
        assertEquals(emptySet<Int>(), history.undo().timeline.lutKeys())
        assertEquals(emptySet<Int>(), t.lutKeys())
    }

    @Test
    fun `the wire form carries the LUT as effect type 13 with its key and intensity`() {
        val fx = ClipFx(effects = listOf(lutEffect(7, 0.25)))
        val wire = FxWire.encode(listOf(fx))
        // blend, mask (6 values), effectCount, then type, valueCount, key, intensity.
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 13.0, 2.0, 7.0, 0.25), wire, 0.0)
    }

    @Test
    fun `a LUT survives the project file`() {
        val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings,
            tracks = listOf(TrackDto("v", "video", 0, listOf(ClipDto("c", "a1", 0, 0, 100, effects = listOf(EffectDto("e1", "lut", listOf(42.0, 0.8))))))),
        )
        val timeline = TimelineMapper.toTimeline(dto)
        val effect = timeline.track("v")!!.clip("c")!!.fx.effects.single()
        assertEquals(EffectType.LUT, effect.type)
        assertEquals(listOf(42.0, 0.8), effect.values)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
        assertEquals(setOf(42), timeline.lutKeys())
    }

    @Test
    fun `split keeps the LUT on both halves`() {
        val t = TimelineOps.addEffect(timeline(track("v1", clip("a", 0, 100))), "a", lutEffect(9)).getOrFail()
        val split = TimelineOps.split(t, "v1", f(40), "b").getOrFail()
        assertTrue(split.track("v1")!!.clips.all { clip -> clip.fx.effects.any { it.type == EffectType.LUT } })
    }
}
