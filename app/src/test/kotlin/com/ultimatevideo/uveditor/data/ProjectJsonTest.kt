package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransitionDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectJsonTest {

    private val sample = ProjectDto(
        id = "p-1",
        name = "Tech_Review_01",
        settings = ProjectSettingsDto(3840, 2160, 60000, 1001, "Rec709-SDR"),
        mediaLibrary = listOf(
            MediaAssetDto("asset-1", "content://media/external/video/media/105", 1800, 60000, 1001, "Rec2020-HLG"),
        ),
        tracks = listOf(
            TrackDto("track-v1", "video", 0, listOf(ClipDto("clip-101", "asset-1", 0, 120, 420))),
        ),
        transitions = listOf(TransitionDto("t-1", "crossfade", "clip-101", "clip-102", 15)),
    )

    @Test
    fun `round trip keeps every field`() {
        assertEquals(sample, ProjectJson.decode(ProjectJson.encode(sample)))
    }

    @Test
    fun `fps is stored as a rational and frames as integers`() {
        val root = ProjectJson.parseObject(ProjectJson.encode(sample))
        val settings = root["settings"]!!.jsonObject
        assertEquals("60000", settings["fpsNum"]!!.jsonPrimitive.content)
        assertEquals("1001", settings["fpsDen"]!!.jsonPrimitive.content)
        val clip = root["tracks"]!!.jsonArray[0].jsonObject["clips"]!!.jsonArray[0].jsonObject
        assertEquals("120", clip["sourceInFrame"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, clip["colorOverride"])
    }

    @Test
    fun `unknown fields survive load and save at every level`() {
        val onDisk = """
            {
              "version": 1, "id": "p-1", "name": "N", "futureTop": {"a": [1, 2]},
              "settings": {"width": 1920, "height": 1080, "fpsNum": 30, "fpsDen": 1,
                           "colorSpace": "Rec709-SDR", "futureSetting": true},
              "tracks": [{"id": "t1", "type": "video", "order": 0, "futureTrack": "x",
                "clips": [{"id": "c1", "timelineStartFrame": 0, "sourceInFrame": 0,
                           "sourceOutFrame": 10, "futureClip": 7}]}]
            }
        """.trimIndent()

        val loaded = ProjectJson.decode(onDisk)
        val edited = loaded.copy(name = "Renamed")
        val saved = ProjectJson.parseObject(ProjectJson.encode(edited, ProjectJson.parseObject(onDisk)))

        assertEquals("Renamed", saved["name"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), saved["settings"]!!.jsonObject["futureSetting"])
        assertEquals(
            "x",
            saved["tracks"]!!.jsonArray[0].jsonObject["futureTrack"]!!.jsonPrimitive.content,
        )
        val clip = saved["tracks"]!!.jsonArray[0].jsonObject["clips"]!!.jsonArray[0].jsonObject
        assertEquals("7", clip["futureClip"]!!.jsonPrimitive.content)
        assertEquals(ProjectJson.parseObject(onDisk)["futureTop"], saved["futureTop"])
    }

    @Test
    fun `removed clips do not resurrect from the previous file`() {
        val withClip = ProjectJson.encode(sample)
        val withoutClip = sample.copy(tracks = listOf(sample.tracks[0].copy(clips = emptyList())))

        val saved = ProjectJson.parseObject(ProjectJson.encode(withoutClip, ProjectJson.parseObject(withClip)))

        assertEquals(0, saved["tracks"]!!.jsonArray[0].jsonObject["clips"]!!.jsonArray.size)
    }

    @Test
    fun `garbage is reported as corrupt`() {
        assertThrows(ProjectError.Corrupt::class.java) { ProjectJson.decode("not json") }
        assertThrows(ProjectError.Corrupt::class.java) { ProjectJson.decode("""{"id": "x"}""") }
    }

    @Test
    fun `newer schema versions are refused`() {
        val text = ProjectJson.encode(sample.copy(version = 99))
        val error = assertThrows(ProjectError.UnsupportedVersion::class.java) { ProjectJson.decode(text) }
        assertEquals(99, error.found)
    }

    @Test
    fun `fractional fps floats are rejected as corrupt`() {
        val text = ProjectJson.encode(sample).replace("\"fpsNum\": 60000", "\"fpsNum\": 59.94")
        assertFalse(text.contains("\"fpsNum\": 60000"))
        assertThrows(ProjectError.Corrupt::class.java) { ProjectJson.decode(text) }
    }
}
