package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectOverviewTest {
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun video(id: String, fpsNum: Int = 30, fpsDen: Int = 1) =
        MediaAssetDto(id, "content://$id", 900, fpsNum, fpsDen, "Rec709-SDR")

    private fun clip(id: String, asset: String?, start: Long, from: Long = 0, to: Long = 100, frames: Long? = null, title: TitleDto? = null) =
        ClipDto(id, asset, start, from, to, timelineFrames = frames, title = title)

    private fun project(assets: List<MediaAssetDto>, vararg tracks: TrackDto) =
        ProjectDto(id = "p", name = "P", settings = settings, mediaLibrary = assets, tracks = tracks.toList())

    @Test
    fun `an empty project has no length and no picture`() {
        val empty = project(emptyList(), TrackDto("v1", "video", 0))

        assertEquals(0L, ProjectOverview.durationFrames(empty))
        assertNull(ProjectOverview.thumbnailSource(empty))
    }

    @Test
    fun `the length is the end of the last clip on any track and honours retimed clips`() {
        val p = project(
            listOf(video("a")),
            TrackDto("v1", "video", 0, listOf(clip("c1", "a", 0, 0, 100), clip("c2", "a", 100, 0, 200, frames = 50))),
            TrackDto("a1", "audio", 1, listOf(clip("m", "a", 0, 0, 400))),
        )

        assertEquals(400L, ProjectOverview.durationFrames(p))
    }

    @Test
    fun `the picture comes from the earliest video clip, in its own source time`() {
        val p = project(
            listOf(video("a", 30000, 1001), video("b")),
            TrackDto("v2", "video", 0, listOf(clip("late", "b", 200, 0, 50))),
            TrackDto("v1", "video", 1, listOf(clip("first", "a", 40, 300, 400))),
        )

        val source = checkNotNull(ProjectOverview.thumbnailSource(p))

        assertEquals("content://a", source.uri)
        // 300 frames at 29.97 fps = 10.01 s
        assertEquals(10_010_000L, source.timeMicros)
        assertEquals(false, source.isImage)
    }

    @Test
    fun `titles stickers and audio-only media never give a picture`() {
        val audio = MediaAssetDto("song", "content://song", 900, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)
        val p = project(
            listOf(audio),
            TrackDto("t1", "title", 0, listOf(clip("title", null, 0, title = TitleDto("Hello")))),
            TrackDto("v1", "video", 1, listOf(clip("sticker", "shape:star", 0), clip("song-clip", "song", 10))),
        )

        assertNull(ProjectOverview.thumbnailSource(p))
    }

    @Test
    fun `a photo is its own picture at time zero`() {
        val photo = MediaAssetDto("shot", "content://shot", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
        val p = project(listOf(photo), TrackDto("v1", "video", 0, listOf(clip("c", "shot", 0, 0, 150))))

        val source = checkNotNull(ProjectOverview.thumbnailSource(p))

        assertEquals(ThumbnailSource("content://shot", 0L, true), source)
    }

    @Test
    fun `durations read as minutes and seconds, with hours when long`() {
        assertEquals("0:00", ProjectOverview.formatDuration(0, 30, 1))
        assertEquals("0:42", ProjectOverview.formatDuration(1260, 30, 1))
        assertEquals("12:05", ProjectOverview.formatDuration(21750, 30, 1))
        assertEquals("1:02:03", ProjectOverview.formatDuration(111_690, 30, 1))
        assertEquals("0:10", ProjectOverview.formatDuration(300, 30000, 1001))
        assertEquals("0:00", ProjectOverview.formatDuration(-5, 30, 1))
        assertEquals("0:00", ProjectOverview.formatDuration(10, 0, 1))
    }
}
