package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.EffectDto
import com.ultimatevideo.uveditor.data.model.MaskDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.BlendMode
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FxMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(clip: ClipDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(TrackDto("v", "video", 0, listOf(clip))),
    )

    private val styled = ClipDto(
        id = "A",
        assetId = "a1",
        timelineStartFrame = 0,
        sourceInFrame = 0,
        sourceOutFrame = 100,
        effects = listOf(
            EffectDto("e1", "contrast", listOf(1.3)),
            EffectDto("e2", "chroma_key", listOf(0.0, 1.0, 0.0, 0.4, 0.1, 0.2)),
        ),
        blendMode = "screen",
        mask = MaskDto("ellipse", 0.1, -0.1, 0.5, 0.4, 0.05, true),
    )

    @Test
    fun `effects blend and mask round trip through the domain`() {
        val dto = project(styled)
        val timeline = TimelineMapper.toTimeline(dto)
        val fx = timeline.track("v")!!.clip("A")!!.fx

        assertEquals(listOf(EffectType.CONTRAST, EffectType.CHROMA_KEY), fx.effects.map { it.type })
        assertEquals(BlendMode.SCREEN, fx.blendMode)
        assertEquals(MaskShape.ELLIPSE, fx.mask!!.shape)
        assertTrue(fx.mask.invert)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
    }

    @Test
    fun `projects written before fx existed load as plain clips`() {
        val plain = styled.copy(effects = emptyList(), blendMode = "normal", mask = null)
        val clip = TimelineMapper.toTimeline(project(plain)).track("v")!!.clip("A")!!

        assertTrue(clip.fx.isNeutral)
    }

    @Test
    fun `fx survive the JSON text form`() {
        val decoded = ProjectJson.decode(ProjectJson.encode(project(styled)))
        val clip = decoded.tracks.single().clips.single()

        assertEquals(styled.effects, clip.effects)
        assertEquals(styled.mask, clip.mask)
        assertEquals("screen", clip.blendMode)
    }

    @Test
    fun `unknown names and invalid values are reported as corrupt`() {
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(styled.copy(blendMode = "dodge"))) }
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(styled.copy(effects = listOf(EffectDto("e", "glitter", listOf(1.0))))))
        }
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(styled.copy(mask = MaskDto(shape = "star"))))
        }
        // A value outside the effect's bounds fails the timeline invariants.
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(styled.copy(effects = listOf(EffectDto("e", "contrast", listOf(9.0))))))
        }
    }
}
