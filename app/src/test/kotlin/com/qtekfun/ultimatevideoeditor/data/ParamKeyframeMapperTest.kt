package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.EffectDto
import com.qtekfun.ultimatevideoeditor.data.model.HandleDto
import com.qtekfun.ultimatevideoeditor.data.model.KeyframeDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamKeyDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamTrackDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.TransformDto
import com.qtekfun.ultimatevideoeditor.domain.BezierHandle
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.PoseParams
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamKeyframeMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(clip: ClipDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(TrackDto("v", "video", 0, listOf(clip))),
    )

    private val tracks = listOf(
        ParamTrackDto(
            "fx.c1.0",
            listOf(
                ParamKeyDto(0, 0.5, "bezier", out = HandleDto(0.3, 0.0), inn = null),
                ParamKeyDto(40, 1.5, "ease", inn = HandleDto(0.6, 0.1)),
                ParamKeyDto(80, 1.0, "hold"),
            ),
        ),
        ParamTrackDto("audio.gainDb", listOf(ParamKeyDto(10, -6.0))),
    )

    private val animated = ClipDto(
        id = "A",
        assetId = "a1",
        timelineStartFrame = 0,
        sourceInFrame = 0,
        sourceOutFrame = 100,
        effects = listOf(EffectDto("c1", "contrast", listOf(1.0))),
        params = tracks,
    )

    @Test
    fun `parameter tracks round trip with modes and handles`() {
        val dto = project(animated)
        val timeline = TimelineMapper.toTimeline(dto)
        val clip = timeline.track("v")!!.clip("A")!!

        assertEquals(listOf("fx.c1.0", "audio.gainDb"), clip.params.map { it.paramId })
        val keys = clip.paramKeys("fx.c1.0")
        assertEquals(listOf(0L, 40L, 80L), keys.map { it.frame })
        assertEquals(listOf(Interpolation.BEZIER, Interpolation.EASE, Interpolation.HOLD), keys.map { it.interpolation })
        assertEquals(BezierHandle(0.3, 0.0), keys[0].out)
        assertNull(keys[0].inn)
        assertEquals(BezierHandle(0.6, 0.1), keys[1].inn)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
        assertTrue(timeline.invariantViolations().isEmpty())
    }

    @Test
    fun `pose keyframes keep Bezier handles through the file`() {
        val clip = ClipDto(
            "A", "a1", 0, 0, 100,
            keyframes = listOf(
                KeyframeDto(0, TransformDto(position = listOf(0.0, 0.0)), "bezier", out = HandleDto(0.5, 0.2)),
                KeyframeDto(50, TransformDto(position = listOf(100.0, 0.0)), "linear", inn = HandleDto(0.7, 0.0)),
            ),
        )
        val dto = project(clip)
        val timeline = TimelineMapper.toTimeline(dto)
        val keys = timeline.track("v")!!.clip("A")!!.keyframes
        assertEquals(Interpolation.BEZIER, keys[0].interpolation)
        assertEquals(BezierHandle(0.5, 0.2), keys[0].out)
        assertEquals(BezierHandle(0.7, 0.0), keys[1].inn)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
        // The per-parameter view of the same joint keyframes.
        assertEquals(listOf(0.0, 100.0), timeline.track("v")!!.clip("A")!!.paramKeys(PoseParams.POSITION_X.id).map { it.value })
    }

    @Test
    fun `projects written before parameter tracks existed load without any`() {
        val old = """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
            |"tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"A","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":100,
            |"keyframes":[{"frame":0,"interpolation":"ease"},{"frame":50}]}]}]}""".trimMargin()
        val dto = ProjectJson.decode(old)
        val clip = TimelineMapper.toTimeline(dto).track("v")!!.clip("A")!!
        assertTrue(clip.params.isEmpty())
        assertEquals(listOf(Interpolation.EASE, Interpolation.LINEAR), clip.keyframes.map { it.interpolation })
        assertTrue(clip.keyframes.all { it.out == null && it.inn == null })
    }

    @Test
    fun `an unknown interpolation in a parameter track is a corrupt project`() {
        val bad = animated.copy(params = listOf(ParamTrackDto("fx.c1.0", listOf(ParamKeyDto(0, 1.0, "wobble")))))
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(bad)) }
    }

    @Test
    fun `a clip without tracks writes none`() {
        val plain = ClipDto("A", "a1", 0, 0, 100)
        val dto = project(plain)
        val written = TimelineMapper.toDto(dto, TimelineMapper.toTimeline(dto), dto.mediaLibrary)
        assertTrue(written.tracks.single().clips.single().params.isEmpty())
        assertEquals(ParamIds.GAIN_DB, "audio.gainDb")
    }
}
