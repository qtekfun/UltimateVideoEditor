package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingMediaTest {
    private val fps = FrameRate(30, 1)

    private fun clip(id: String, asset: String?, start: Long, len: Long, srcIn: Long = 0) =
        Clip(id, asset, FrameIndex(start), FrameIndex(srcIn), FrameIndex(srcIn + len))

    private fun asset(id: String, uri: String = "content://m/$id", name: String? = null, video: Boolean = true, audio: Boolean = true) =
        MediaAssetDto(id, uri, durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR", hasVideo = video, hasAudio = audio, displayName = name)

    // Overlay v2 on top, base v1 below, audio under it.
    private val timeline = Timeline(
        listOf(
            Track("v2", TrackType.VIDEO, listOf(clip("o1", "a2", 300, 60))),
            Track("v1", TrackType.VIDEO, listOf(clip("b1", "a1", 0, 100), clip("b2", "a2", 100, 200, srcIn = 50))),
            Track("a1", TrackType.AUDIO, listOf(clip("m1", "a3", 0, 90))),
        ),
    )

    @Test
    fun `clips needing a missing asset are listed with where they are`() {
        val clips = MissingMedia.clipsUsing(timeline, setOf("a2"), fps)

        assertEquals(listOf("o1", "b2"), clips.map { it.clipId })
        assertEquals(listOf("V2 at 0:10", "V1 at 0:03"), clips.map { it.where })
    }

    @Test
    fun `nothing missing lists nothing`() {
        assertTrue(MissingMedia.clipsUsing(timeline, emptySet(), fps).isEmpty())
    }

    @Test
    fun `track labels count video up from the base and audio from the top`() {
        assertEquals("V2", MissingMedia.trackLabel(timeline, 0))
        assertEquals("V1", MissingMedia.trackLabel(timeline, 1))
        assertEquals("A1", MissingMedia.trackLabel(timeline, 2))
    }

    @Test
    fun `summary counts clips per file and puts the most used first`() {
        val assets = listOf(asset("a1", name = "intro.mp4"), asset("a2", name = "broll.mp4"), asset("a3"))
        val summary = MissingMedia.summarize(
            timeline,
            assets,
            mapOf("a1" to MediaProblem.UNREADABLE, "a2" to MediaProblem.PERMISSION_LOST),
        )

        assertEquals(listOf("a2", "a1"), summary.map { it.assetId })
        assertEquals(listOf("broll.mp4", "intro.mp4"), summary.map { it.name })
        assertEquals(listOf(2, 1), summary.map { it.clipCount })
        assertEquals(MediaProblem.PERMISSION_LOST, summary[0].problem)
    }

    @Test
    fun `a file without a stored name is named after its uri then its id`() {
        assertEquals("clip.mp4", MissingMedia.nameOf(asset("x", uri = "content://docs/tree/primary%3ADCIM/clip.mp4")))
        assertEquals("1234", MissingMedia.nameOf(asset("x", uri = "content://media/external/video/media/1234")))
        assertEquals("x", MissingMedia.nameOf(asset("x", uri = "")))
        // Seen on a device: a media-provider document URI whose id is the percent-encoded raw path.
        assertEquals(
            "shaky.mp4",
            MissingMedia.nameOf(asset("x", uri = "content://com.android.providers.media.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fqa-b%2Fshaky.mp4")),
        )
        assertEquals("a b.mp4", MissingMedia.nameOf(asset("x", uri = "content://docs/document/primary%3AMovies%2Fa%20b.mp4")))
        assertEquals("100%.mp4", MissingMedia.nameOf(asset("x", uri = "content://docs/files/100%.mp4")))
    }

    @Test
    fun `required source is the furthest point any clip of the file reads to`() {
        // b2 reads source frames 50..250 of a2, o1 reads 0..60: 250 frames at 30 fps.
        assertEquals(fps.framesToMicros(250), MissingMedia.requiredSourceMicros(timeline, "a2", fps))
        assertEquals(0L, MissingMedia.requiredSourceMicros(timeline, "unused", fps))
    }

    // region relink verdicts

    private val old = asset("a1", video = true, audio = true)

    private fun probed(
        seconds: Long = 60,
        video: Boolean = true,
        audio: Boolean = true,
        fpsNum: Int = 30,
        color: String = "Rec709-SDR",
        image: Boolean = false,
    ) = ProbedMedia(seconds * 1_000_000, fpsNum, 1, color, hasVideo = video, hasAudio = audio, isImage = image)

    private fun verdict(replacement: ProbedMedia, uri: String = "content://new", others: List<String> = emptyList(), needed: Long = 10_000_000, base: MediaAssetDto = old) =
        RelinkCheck.evaluate(base, replacement, uri, others, needed)

    @Test
    fun `a matching file is accepted without warnings`() {
        assertEquals(RelinkVerdict.Accepted(emptyList()), verdict(probed()))
    }

    @Test
    fun `a file already in the project is refused`() {
        val result = verdict(probed(), uri = "content://m/a2", others = listOf("content://m/a2"))
        assertTrue(result is RelinkVerdict.Rejected)
    }

    @Test
    fun `video media needs video in the replacement`() {
        assertTrue(verdict(probed(video = false)) is RelinkVerdict.Rejected)
    }

    @Test
    fun `an audio only file needs audio in the replacement`() {
        val audioOnly = asset("a3", video = false, audio = true)
        assertTrue(verdict(probed(video = false, audio = false), base = audioOnly) is RelinkVerdict.Rejected)
        assertEquals(RelinkVerdict.Accepted(emptyList()), verdict(probed(video = false, audio = true), base = audioOnly))
    }

    @Test
    fun `a replacement without audio is accepted with a warning`() {
        val result = verdict(probed(audio = false)) as RelinkVerdict.Accepted
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("no audio"))
    }

    @Test
    fun `a shorter replacement warns only when it cuts into the used part`() {
        assertEquals(RelinkVerdict.Accepted(emptyList()), verdict(probed(seconds = 20), needed = 10_000_000))
        val result = verdict(probed(seconds = 5), needed = 10_000_000) as RelinkVerdict.Accepted
        assertTrue(result.warnings.single().contains("shorter"))
    }

    @Test
    fun `different frame rate and colour space only warn`() {
        val result = verdict(probed(fpsNum = 60, color = "Rec2020-HLG")) as RelinkVerdict.Accepted
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `pictures only replace pictures`() {
        val picture = asset("p", video = false, audio = false).copy(isImage = true)
        assertTrue(verdict(probed(), base = picture) is RelinkVerdict.Rejected)
        assertTrue(verdict(probed(image = true), base = old) is RelinkVerdict.Rejected)
        assertFalse(verdict(probed(image = true, video = false, audio = false), base = picture) is RelinkVerdict.Rejected)
    }

    // endregion
}
