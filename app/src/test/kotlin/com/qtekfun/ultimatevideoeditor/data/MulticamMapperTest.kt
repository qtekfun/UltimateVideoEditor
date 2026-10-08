package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.AngleCutDto
import com.qtekfun.ultimatevideoeditor.data.model.MulticamAngleDto
import com.qtekfun.ultimatevideoeditor.data.model.MulticamDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleCut
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamAngle
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamOps
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MulticamMapperTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private fun project(multicams: List<MulticamDto> = emptyList()) =
        ProjectDto(id = "p", name = "P", settings = settings, multicams = multicams)

    private val group = MulticamClip(
        id = "g1", name = "Concert",
        angles = listOf(MulticamAngle("x", "Cam A", "asset-a", 0, 1000), MulticamAngle("y", "Cam B", "asset-b", 40, 1000), MulticamAngle("z", "Cam C", "asset-c", -25, 1000)),
        audioAngle = 0, videoTrackId = "v2", audioTrackId = "a1", startFrame = 100, inFrame = 100, lengthFrames = 600,
        cuts = listOf(AngleCut(0, 0)),
    )

    @Test
    fun `a multicam clip survives saving and loading`() {
        val start = timeline(track("v2"), track("v1", clip("base-1", 0, 100)), track("a1", type = TrackType.AUDIO))
        val made = MulticamOps.create(start, group).getOrFail()
            .let { MulticamOps.record(it, "g1", listOf(AngleCut(150, 1), AngleCut(420, 2))) }.getOrFail()

        // The tracks go through the DTOs too, so the realised clips are part of the stored project.
        val dto = TimelineMapper.toDto(project(), made, emptyList())
        val reloaded = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(json.encodeToString(dto)))

        assertEquals(made.multicams, reloaded.multicams)
        assertEquals(made.tracks.map { t -> t.clips.map { it.id } }, reloaded.tracks.map { t -> t.clips.map { it.id } })
        assertEquals(emptyList<String>(), reloaded.invariantViolations())
        assertEquals(3, dto.multicams.single().cuts.size)
        assertEquals("asset-b", dto.multicams.single().angles[1].assetId)
    }

    @Test
    fun `projects written before multicam existed load with none and write no multicam field`() {
        val old = """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"}}"""
        val loaded = json.decodeFromString<ProjectDto>(old)
        assertTrue(loaded.multicams.isEmpty())
        val timeline = TimelineMapper.toTimeline(loaded)
        assertTrue(timeline.multicams.isEmpty())
        assertFalse(json.encodeToString(TimelineMapper.toDto(loaded, timeline, emptyList())).contains("multicams"))
    }

    @Test
    fun `an invalid stored group is a corrupt project`() {
        val bad = MulticamDto(
            id = "g1", name = "x", angles = listOf(MulticamAngleDto("a", "A", "asset", 0, 100)), // one angle only
            audioAngle = 0, videoTrackId = "v1", startFrame = 0, inFrame = 0, lengthFrames = 50, cuts = listOf(AngleCutDto(0, 0)),
        )
        try {
            TimelineMapper.toTimeline(project(listOf(bad)))
            fail("expected a corrupt project")
        } catch (e: ProjectError.Corrupt) {
            assertTrue(e.message!!.contains("multicam"))
        }
    }

    @Test
    fun `a stored group whose clips are not in the file is rejected, not invented`() {
        val dto = MulticamDto(
            id = "g1", name = "x",
            angles = listOf(MulticamAngleDto("a", "A", "asset", 0, 100), MulticamAngleDto("b", "B", "asset2", 0, 100)),
            audioAngle = 0, videoTrackId = "v1", startFrame = 0, inFrame = 0, lengthFrames = 50, cuts = listOf(AngleCutDto(0, 0)),
        )
        try {
            val loaded = TimelineMapper.toTimeline(project(listOf(dto)))
            fail("expected a corrupt project but got ${loaded.multicams}")
        } catch (e: ProjectError.Corrupt) {
            assertTrue(e.message!!.contains("lost its clip"))
        }
    }
}
