package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.CurvePointDto
import com.ultimatevideo.uveditor.data.model.EffectDto
import com.ultimatevideo.uveditor.data.model.GradeCurvesDto
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
    fun `a colour grade keeps its values and curves through the mapper and the JSON text`() {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[11] = 0.4; it[17] = 1.3 }
        val curves = GradeCurvesDto(
            master = listOf(CurvePointDto(0.0, 0.0), CurvePointDto(0.4, 0.3), CurvePointDto(1.0, 1.0)),
            blue = listOf(CurvePointDto(0.0, 0.1), CurvePointDto(1.0, 1.0)),
        )
        val graded = styled.copy(effects = listOf(EffectDto("g", "color_grade", values, curves)))
        val decoded = ProjectJson.decode(ProjectJson.encode(project(graded)))
        assertEquals(curves, decoded.tracks.single().clips.single().effects.single().curves)

        val timeline = TimelineMapper.toTimeline(decoded)
        val effect = timeline.track("v")!!.clip("A")!!.fx.effects.single()
        assertEquals(EffectType.COLOR_GRADE, effect.type)
        assertEquals(values, effect.values)
        assertEquals(0.3, effect.curves!!.master.points[1].y, 0.0)
        assertTrue(effect.curves.red.isIdentity) // an empty list is the identity curve

        val back = TimelineMapper.toDto(project(graded), timeline, emptyList()).tracks.single().clips.single().effects.single()
        assertEquals(curves, back.curves)
    }

    @Test
    fun `a grade without curves writes no curve points and old files without them load`() {
        val plainGrade = styled.copy(effects = listOf(EffectDto("g", "color_grade", EffectType.COLOR_GRADE.defaults)))
        val text = ProjectJson.encode(project(plainGrade))
        assertTrue(!text.contains("\"master\""))
        val effect = TimelineMapper.toTimeline(ProjectJson.decode(text)).track("v")!!.clip("A")!!.fx.effects.single()
        assertEquals(null, effect.curves)
        val identityDto = GradeCurvesDto()
        val withIdentity = styled.copy(effects = listOf(EffectDto("g", "color_grade", EffectType.COLOR_GRADE.defaults, identityDto)))
        val back = TimelineMapper.toDto(project(withIdentity), TimelineMapper.toTimeline(project(withIdentity)), emptyList())
        assertEquals(null, back.tracks.single().clips.single().effects.single().curves)
    }

    @Test
    fun `invalid curves are reported as corrupt`() {
        val bad = GradeCurvesDto(red = listOf(CurvePointDto(0.5, 0.5), CurvePointDto(0.2, 0.9)))
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(
                project(styled.copy(effects = listOf(EffectDto("g", "color_grade", EffectType.COLOR_GRADE.defaults, bad)))),
            )
        }
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
