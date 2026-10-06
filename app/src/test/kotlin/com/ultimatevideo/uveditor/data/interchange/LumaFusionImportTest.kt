package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.interchange.LfFixture.ClipSpec
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaFusionImportTest {
    private fun convert(json: String, available: Set<String> = emptySet()) = LumaFusionImport.convert(LumaFusionImport.parse(json), available)

    private fun base(c: LumaFusionConversion): TrackDto = c.project.tracks.last { it.type == "video" }

    private fun List<String>.has(part: String) = any { it.contains(part, ignoreCase = true) }

    private val fourCuts = LfFixture.archive(
        resolution = "[3840,2160]",
        tracks = listOf(
            LfFixture.track(1, -1, emptyList()),
            LfFixture.track(
                0, 0, anchor = true,
                clips = listOf(
                    ClipSpec("c1", "clip1.MOV", 0, 54380, sourceStart = 790),
                    ClipSpec("c2", "clip1.MOV", 54380, 37080, sourceStart = 55170),
                    ClipSpec("c3", "clip1.MOV", 91460, 36700, sourceStart = 92250),
                    ClipSpec("c4", "clip1.MOV", 128160, 156610, sourceStart = 128950),
                ),
            ),
            LfFixture.track(0, 1, emptyList()),
        ),
    )

    // region detection and errors

    @Test
    fun `detects an archive by its content and refuses other json`() {
        assertTrue(LumaFusionImport.looksLikeArchive(fourCuts))
        assertFalse(LumaFusionImport.looksLikeArchive("""{"tracks":[],"mediaLibrary":[],"attributes":{"appVersion":"1"}}"""))
        assertFalse(LumaFusionImport.looksLikeArchive("""{"tracks":[]}"""))
        assertFalse(LumaFusionImport.looksLikeArchive("not json"))
        assertFalse(LumaFusionImport.looksLikeArchive("[]"))
    }

    @Test
    fun `malformed input is a typed error and never a crash`() {
        assertThrows(LumaFusionError.Malformed::class.java) { LumaFusionImport.parse("{") }
        assertThrows(LumaFusionError.NotLumaFusion::class.java) { LumaFusionImport.parse("[]") }
        assertThrows(LumaFusionError.NotLumaFusion::class.java) { LumaFusionImport.parse("""{"title":"x"}""") }
        // A picture size of zero cannot become a project.
        val noSize = """{"attributes":{"appVersion":"5"},"tracks":[]}"""
        assertThrows(LumaFusionError.Malformed::class.java) { LumaFusionImport.convert(LumaFusionImport.parse(noSize)) }
    }

    @Test
    fun `unknown keys are ignored and missing ones defaulted`() {
        val sparse = """{"attributes":{"appVersion":"5"},"resolution":[1280,720],"surprise":{"a":1},
            "tracks":[{"trackType":0,"isAnchorTrack":true,"clips":[{"streams":[],"mystery":true},"junk",7]},"junk"]}"""
        val c = convert(sparse)
        assertEquals(1280, c.project.settings.width)
        assertEquals(30, c.project.settings.fpsNum) // no stepTime: 30 fps and a line about it
        assertTrue(c.report.notImported.has("Frame rate not found"))
        assertTrue(base(c).clips.isEmpty())
    }

    // endregion

    // region cuts and rounding

    @Test
    fun `four consecutive cuts stay gap free and keep their lengths`() {
        val c = convert(fourCuts)
        assertEquals(3840, c.project.settings.width)
        assertEquals(60, c.project.settings.fpsNum)
        assertEquals(1, c.project.settings.fpsDen)
        val clips = base(c).clips
        assertEquals(listOf(0L, 5438L, 9146L, 12816L), clips.map { it.timelineStartFrame })
        assertEquals(listOf(5438L, 3708L, 3670L, 15661L), clips.map { it.sourceOutFrame - it.sourceInFrame })
        assertEquals(79L, clips[0].sourceInFrame)
        clips.zipWithNext { a, b -> assertEquals(a.timelineStartFrame + a.sourceOutFrame - a.sourceInFrame, b.timelineStartFrame) }
        // 474.6 s: 7:54.6
        val end = clips.last().timelineStartFrame + clips.last().sourceOutFrame - clips.last().sourceInFrame
        assertEquals(28477L, end)
        assertEquals(1, c.project.mediaLibrary.size)
        assertTrue(c.report.notImported.isEmpty())
    }

    @Test
    fun `positions are rounded at 29_97 and touching clips still touch`() {
        // Frame step 1001/30000 s. 0.5 s is 14.985 frames and 1 s is 29.97 frames.
        val json = LfFixture.archive(
            stepValue = 1001, stepScale = 30000,
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(ClipSpec("a", "clip1.MOV", 0, 300), ClipSpec("b", "clip1.MOV", 300, 300, sourceStart = 300), ClipSpec("c", "clip1.MOV", 3600, 600)),
                ),
            ),
        )
        val c = convert(json)
        assertEquals(30000, c.project.settings.fpsNum)
        assertEquals(1001, c.project.settings.fpsDen)
        val clips = base(c).clips
        assertEquals(listOf(0L, 15L, 180L), clips.map { it.timelineStartFrame }) // 6 s = 179.82
        assertEquals(15L, clips[0].sourceOutFrame - clips[0].sourceInFrame)
        assertEquals(15L, clips[1].sourceOutFrame - clips[1].sourceInFrame) // 30 - 15
        assertEquals(15L, clips[1].sourceInFrame) // 0.5 s into the source
        assertEquals(30L, clips[2].sourceOutFrame - clips[2].sourceInFrame) // 6 s..7 s = 179.82..209.79 -> 180..210
    }

    @Test
    fun `cmtime rounds half up and exact times are exact`() {
        assertEquals(1L, LumaFusionImport.toFrames(24, 1, LfTime(1, 48)))
        assertEquals(2L, LumaFusionImport.toFrames(24, 1, LfTime(3, 48)))
        assertEquals(0L, LumaFusionImport.toFrames(24, 1, LfTime(-1, 48)))
        assertEquals(-1L, LumaFusionImport.toFrames(24, 1, LfTime(-3, 48)))
        assertEquals(60L, LumaFusionImport.toFrames(60, 1, LfTime(600, 600)))
        assertEquals(30L, LumaFusionImport.toFrames(30000, 1001, LfTime(1, 1)))
        // Sums are exact before rounding: 1/3 s + 1/6 s is 0.5 s.
        assertEquals(15L, LumaFusionImport.toFrames(30, 1, LfTime(1, 3), LfTime(1, 6)))
    }

    @Test
    fun `frame rate comes from the step time as a reduced rational`() {
        assertEquals(60 to 1, LumaFusionImport.frameRate(LfTime(10, 600)))
        assertEquals(30000 to 1001, LumaFusionImport.frameRate(LfTime(1001, 30000)))
        assertEquals(24000 to 1001, LumaFusionImport.frameRate(LfTime(1001, 24000)))
        assertNull(LumaFusionImport.frameRate(LfTime(0, 600)))
        assertNull(LumaFusionImport.frameRate(LfTime(1, 1000))) // 1000 fps
        assertNull(LumaFusionImport.frameRate(null))
    }

    // endregion

    // region tracks

    @Test
    fun `overlay lanes sit above the base in offset order and audio tracks follow`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(1, -1, listOf(ClipSpec("m", "song.wav", 0, 1200, video = false)), volume = 0.5),
                LfFixture.track(0, 0, listOf(ClipSpec("a", "clip1.MOV", 0, 1200)), anchor = true),
                LfFixture.track(0, 1, listOf(ClipSpec("b", "clip2.MOV", 600, 600))),
                LfFixture.track(0, 2, listOf(ClipSpec("c", "clip3.MOV", 0, 600))),
            ),
        )
        val c = convert(json)
        assertEquals(listOf("track-v3", "track-v2", "track-v1", "track-a1"), c.project.tracks.map { it.id })
        assertEquals(listOf("video", "video", "video", "audio"), c.project.tracks.map { it.type })
        assertEquals(listOf(0, 1, 2, 3), c.project.tracks.map { it.order })
        assertEquals("clip1.MOV", c.project.mediaLibrary.first { it.id == base(c).clips.single().assetId }.displayName)
        val audio = c.project.tracks.last()
        assertEquals(-6.02, audio.audio!!.volumeDb, 0.01)
        val song = c.project.mediaLibrary.first { it.id == audio.clips.single().assetId }
        assertFalse(song.hasVideo)
        assertTrue(song.hasAudio)
        assertEquals(120L, audio.clips.single().sourceOutFrame - audio.clips.single().sourceInFrame)
    }

    @Test
    fun `colour space 1 makes an HDR project and 0 an SDR one`() {
        val clips = listOf(LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 600))))
        val hdr = convert(LfFixture.archive(tracks = clips, colorspace = 1))
        assertEquals("Rec2020-HLG", hdr.project.settings.colorSpace)
        assertEquals("Rec2020-HLG", hdr.project.mediaLibrary.single().colorSpace)
        assertTrue(hdr.report.imported.has("HDR"))
        assertEquals("Rec709-SDR", convert(LfFixture.archive(tracks = clips)).project.settings.colorSpace)
        val other = convert(LfFixture.archive(tracks = clips, colorspace = 5))
        assertEquals("Rec709-SDR", other.project.settings.colorSpace)
        assertTrue(other.report.notImported.has("Colour space 5"))
    }

    @Test
    fun `an archive without an anchor video track still gets a base track`() {
        val c = convert(LfFixture.archive(tracks = listOf(LfFixture.track(1, -1, emptyList()))))
        assertEquals(listOf("track-v1", "track-a1"), c.project.tracks.map { it.id })
    }

    @Test
    fun `assets are shared by file and named after it`() {
        val c = convert(fourCuts)
        assertEquals(mapOf("clip1.MOV" to "asset-1"), c.assetNames)
        val asset = c.project.mediaLibrary.single()
        assertEquals("clip1.MOV", asset.displayName)
        assertTrue(asset.hasVideo && asset.hasAudio)
        assertEquals(12895L + 15661L, asset.durationFrames)
    }

    @Test
    fun `a placeholder original name falls back to the title as the file name`() {
        val json = LfFixture.archive(
            tracks = listOf(LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("p", "pic.PNG", 0, 600, type = 2, audio = false, original = "sioProviderRelink")))),
        )
        assertEquals(mapOf("pic.PNG" to "asset-1"), convert(json).assetNames)
    }

    // endregion

    // region clip settings

    @Test
    fun `volume pan and opacity are mapped and a muted clip is silent`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(
                        ClipSpec("a", "clip1.MOV", 0, 600, volume = 0.5, pan = -0.25, alpha = 0.4),
                        ClipSpec("b", "clip1.MOV", 600, 600, volume = 0.0),
                    ),
                ),
            ),
        )
        val clips = base(convert(json)).clips
        assertEquals(-6.02, clips[0].gainDb, 0.01)
        assertEquals(-0.25, clips[0].audio!!.pan, 1e-9)
        assertEquals(0.4, clips[0].transform.opacity, 1e-9)
        assertEquals(-96.0, clips[1].gainDb, 0.0)
        assertNull(clips[1].audio)
    }

    @Test
    fun `volume 1 clips keep 0 dB and the report lists the clips silent because LumaFusion had volume 0`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(
                        ClipSpec("a", "clip1.MOV", 0, 600),
                        ClipSpec("b", "clip1.MOV", 600, 600, volume = 0.0),
                        ClipSpec("c", "clip1.MOV", 1200, 600),
                    ),
                ),
                LfFixture.track(0, 1, clips = listOf(ClipSpec("d", "clip2.MOV", 0, 600)), volume = 0.0),
            ),
        )
        val c = convert(json)
        assertEquals(listOf(0.0, -96.0, 0.0), base(c).clips.map { it.gainDb })
        val line = c.report.imported.single { it.startsWith("Clips silent because their LumaFusion volume was 0") }
        assertTrue(line, line.contains(": 2 ("))
        assertTrue(line, line.contains("V1 at 0:01"))
        assertTrue(line, line.contains("V2 at 0:00"))
        // Nothing is said when no clip is muted.
        assertFalse(convert(fourCuts).report.imported.has("silent because"))
    }

    @Test
    fun `rotation values are not added because the app applies the file orientation itself`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(
                        ClipSpec("a", "clip1.MOV", 0, 600, rotation = Math.PI),
                        ClipSpec("b", "clip1.MOV", 600, 600, rotation = -Math.PI / 2),
                    ),
                ),
            ),
        )
        val c = convert(json)
        assertEquals(0.0, base(c).clips[0].transform.rotation, 0.0)
        assertEquals(0.0, base(c).clips[1].transform.rotation, 0.0)
        assertTrue(c.report.imported.has("rotation value"))
        assertTrue(c.report.notImported.isEmpty())
    }

    @Test
    fun `a portrait clip whose orientation and rotation cancel needs no extra turn`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 600, rotation = -Math.PI / 2, orientation = Math.PI / 2))),
            ),
        )
        val c = convert(json)
        assertEquals(0.0, base(c).clips.single().transform.rotation, 0.0)
        assertTrue(c.report.notImported.isEmpty())
    }

    @Test
    fun `a side by side layout converts with half canvas units and a vertical offset is reported`() {
        val json = LfFixture.archive(
            resolution = "[3840,2160]",
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 600, scale = "[0.472,0.472]", translation = -0.519 to 0.0))),
                LfFixture.track(0, 1, clips = listOf(ClipSpec("b", "clip2.MOV", 0, 600, scale = "[0.292,0.292]", translation = 0.55 to -0.704))),
            ),
        )
        val c = convert(json)
        val left = base(c).clips.single().transform
        assertEquals(listOf(0.472, 0.472), left.scale)
        assertEquals(-0.519 * 1920, left.position[0], 1e-6)
        assertEquals(0.0, left.position[1], 0.0)
        val corner = c.project.tracks.first().clips.single().transform
        assertEquals(listOf(1.0, 1.0), corner.scale)
        assertEquals(listOf(0.0, 0.0), corner.position)
        assertTrue(c.report.imported.has("inferred"))
        assertTrue(c.report.notImported.has("Picture settings: scale, position"))
    }

    // endregion

    // region photos and titles

    @Test
    fun `a photo becomes a still clip of an image asset`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 1200))),
                LfFixture.track(0, 1, clips = listOf(ClipSpec("p", "photo1.JPG", 300, 1200, type = 2, audio = false, sourceStart = 0))),
            ),
        )
        val c = convert(json)
        val photo = c.project.tracks.first().clips.single()
        assertEquals("photo", photo.still)
        assertEquals(0L, photo.sourceInFrame)
        assertEquals(120L, photo.sourceOutFrame)
        assertEquals(0.0, photo.gainDb, 0.0)
        val asset = c.project.mediaLibrary.first { it.id == photo.assetId }
        assertTrue(asset.isImage)
        assertFalse(asset.hasVideo)
        assertFalse(asset.hasAudio)
        assertEquals("photo1.JPG", asset.displayName)
    }

    @Test
    fun `a lower third title becomes a layered title track above the video`() {
        val json = LfFixture.archive(
            resolution = "[3840,2160]",
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 3000))),
                LfFixture.track(0, 1, clips = listOf(ClipSpec("t", "Plain.titleData", 600, 1200, type = 4, video = false, audio = false, runtimeTitle = LfFixture.lowerThird))),
            ),
        )
        val c = convert(json)
        assertEquals(listOf("title", "video", "video"), c.project.tracks.map { it.type })
        val clip = c.project.tracks.first().clips.single()
        assertNull(clip.assetId)
        assertEquals(60L, clip.timelineStartFrame)
        assertEquals(120L, clip.sourceOutFrame)
        val title = clip.title!!
        assertEquals("Hello", title.text)
        val (box, text) = title.layers
        assertEquals("shape", box.type)
        assertEquals("rect", box.shape)
        assertEquals(3632.0 / 3840, box.width, 1e-9)
        assertEquals(444.0 / 2160, box.height, 1e-9)
        assertEquals(0.35, box.opacity, 1e-9)
        assertEquals("#FF000000", box.fill)
        assertEquals((1600.0 + 222 - 1080) / 2160, box.offsetY, 1e-9)
        assertEquals("text", text.type)
        assertEquals("Hello", text.text)
        assertEquals(178.125 / 2160, text.size, 1e-9)
        assertEquals("#FFFFFFFF", text.color)
        assertEquals("center", text.alignment)
        assertEquals((270.0 + 1650 - 1920) / 3840, text.offsetX, 1e-9)
        assertEquals((1616.0 + 207 - 1080) / 2160, text.offsetY, 1e-9)
        assertTrue(c.report.notImported.has("Title fonts"))
        assertTrue(c.report.notImported.has("shadow"))
        assertEquals(1, c.project.mediaLibrary.size)
    }

    @Test
    fun `a title without layers is reported and left out`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("t", "x.titleData", 0, 600, type = 4, video = false, audio = false, runtimeTitle = """{"frameSize":[3840,2160],"layers":[]}"""))),
            ),
        )
        val c = convert(json)
        assertTrue(base(c).clips.isEmpty())
        assertTrue(c.report.notImported.has("Titles without readable"))
    }

    // endregion

    // region not imported

    @Test
    fun `reversed speed transition effects keyframes flips and hidden tracks are reported not guessed`() {
        val json = LfFixture.archive(
            markers = """[{"time":${LfFixture.time(0)},"title":"m"}]""",
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(
                        ClipSpec("a", "clip1.MOV", 0, 600, reversed = true),
                        ClipSpec("b", "clip1.MOV", 600, 600, speed = 2.0),
                        ClipSpec("c", "clip1.MOV", 1200, 600, transition = 3),
                        ClipSpec("d", "clip1.MOV", 1800, 600, effects = listOf("Noir")),
                        ClipSpec("e", "clip1.MOV", 2400, 600, keyedAlpha = true),
                        ClipSpec("f", "clip1.MOV", 3000, 600, flipH = true),
                    ),
                ),
                LfFixture.track(0, 1, hidden = true, clips = listOf(ClipSpec("g", "clip2.MOV", 0, 600))),
            ),
        )
        val c = convert(json)
        val lines = c.report.notImported
        assertTrue(lines.has("Reversed"))
        assertTrue(lines.has("Speed changes"))
        assertTrue(lines.has("Transitions"))
        assertTrue(lines.has("Effects") && lines.any { it.contains("Noir") })
        assertTrue(lines.has("keyframed"))
        assertTrue(lines.has("horizontal flip"))
        assertTrue(lines.has("Hidden tracks"))
        assertTrue(lines.has("Markers"))
        // The cuts themselves are all there, at normal speed and in place.
        assertEquals(6, base(c).clips.size)
        assertEquals(60L, base(c).clips[1].sourceOutFrame - base(c).clips[1].sourceInFrame)
    }

    @Test
    fun `a blank clip and an unknown kind are left out with a reason and leave a gap`() {
        val json = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(
                    0, 0, anchor = true,
                    clips = listOf(
                        ClipSpec("a", "clip1.MOV", 0, 600),
                        ClipSpec("g", "gen", 600, 600, type = 9, video = false, audio = false),
                        ClipSpec("b", "clip1.MOV", 1200, 600),
                    ),
                ),
            ),
        )
        val c = convert(json)
        assertEquals(listOf(0L, 120L), base(c).clips.map { it.timelineStartFrame })
        assertTrue(c.report.notImported.has("asset type 9"))
    }

    // endregion

    // region standalone

    @Test
    fun `an archive without footage lists every media file as to be relinked`() {
        val c = convert(fourCuts)
        assertTrue(c.report.imported.has("not included"))
    }

    @Test
    fun `the report counts footage that came with the package`() {
        val c = convert(fourCuts, setOf("clip1.mov"))
        assertTrue(c.report.imported.has("1 of 1 media files came with the package"))
    }

    // endregion
}
