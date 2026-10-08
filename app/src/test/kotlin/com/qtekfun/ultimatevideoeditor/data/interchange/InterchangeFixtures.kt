package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.model.ClipAudioDto
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MarkerDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.TransformDto
import org.junit.Assert.assertEquals
import java.io.File

/** A small project that touches every part of the interchange exporters. Display order: title, overlay, base, audio. */
internal fun sampleProject(fpsNum: Int = 30, fpsDen: Int = 1, colorSpace: String = "Rec709-SDR") = ProjectDto(
    id = "p1",
    name = "Sample & Co",
    settings = ProjectSettingsDto(1920, 1080, fpsNum, fpsDen, colorSpace),
    mediaLibrary = listOf(
        MediaAssetDto("a1", "content://media/1", 900, 30, 1, "Rec709-SDR", displayName = "interview.mp4", tags = listOf("interview", "a-roll"), note = "main camera"),
        MediaAssetDto("a2", "file:///music/song.wav", 3600, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true, displayName = "song.wav"),
        MediaAssetDto("a3", "content://media/3", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true, displayName = "photo.jpg"),
        MediaAssetDto("a4", "content://media/4", 100, 30, 1, "Rec709-SDR", displayName = "unused.mp4"),
    ),
    tracks = listOf(
        TrackDto(
            "t1", "title", 0,
            listOf(
                ClipDto(
                    "title-1", null, 30, 0, 60,
                    title = TitleDto(text = "Hello <World> & \"friends\"", sizeFraction = 0.1, color = "#FFFF8000", alignment = "left", bold = true),
                ),
            ),
        ),
        TrackDto(
            "v2", "video", 1,
            listOf(
                ClipDto(
                    "over-1", "a1", 60, 0, 90,
                    transform = TransformDto(scale = listOf(0.5, 0.5), rotation = 10.0, position = listOf(108.0, -54.0), opacity = 0.8),
                ),
                ClipDto("photo-1", "a3", 200, 0, 90, still = "photo"),
            ),
        ),
        TrackDto(
            "v1", "video", 2,
            listOf(
                ClipDto("c1", "a1", 0, 30, 180),
                ClipDto("c2", "a1", 150, 300, 450, gainDb = -6.0),
                ClipDto("c3", "a1", 300, 0, 120, timelineFrames = 60),
            ),
        ),
        TrackDto("a1", "audio", 3, listOf(ClipDto("m1", "a2", 0, 0, 360, audio = ClipAudioDto(pan = 0.5)))),
    ),
    markers = listOf(
        MarkerDto("mk1", 75, "manual", note = "Cut here & <check>", color = "red"),
        MarkerDto("mk2", 120, "beat"),
    ),
)

/** Compares [actual] with `src/test/resources/golden/<name>`. A missing file is written next to the build output for review. */
internal fun assertGolden(name: String, actual: String) {
    val resource = ProjectDto::class.java.classLoader?.getResource("golden/$name") ?: run {
        val out = File("build/golden-actual").apply { mkdirs() }.resolve(name)
        out.writeText(actual)
        throw AssertionError("No golden file golden/$name; the current output was written to ${out.absolutePath} for review")
    }
    assertEquals("golden/$name", File(resource.toURI()).readText(), actual)
}
