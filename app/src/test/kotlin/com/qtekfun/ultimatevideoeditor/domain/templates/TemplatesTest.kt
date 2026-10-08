package com.qtekfun.ultimatevideoeditor.domain.templates

import com.qtekfun.ultimatevideoeditor.data.TemplateFile
import com.qtekfun.ultimatevideoeditor.data.TemplateFormatException
import com.qtekfun.ultimatevideoeditor.data.TemplateStore
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.domain.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun video(id: String, frames: Long = 600) = MediaAssetDto(id, "content://$id", frames, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)
    private fun photo(id: String) = MediaAssetDto(id, "content://$id", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
    private fun song(id: String, frames: Long = 900) = MediaAssetDto(id, "content://$id", frames, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)

    private fun fillsFor(template: ProjectTemplate, frames: Long = 600): Map<String, SlotFill> =
        template.placeholders.associate { p ->
            p.id to SlotFill(
                when (p.kind) {
                    PlaceholderKind.AUDIO -> song("a-${p.id}", frames)
                    PlaceholderKind.PHOTO -> photo("p-${p.id}")
                    else -> video("v-${p.id}", frames)
                },
            )
        }

    private fun EditResult<TemplateResult>.ok(): TemplateResult = when (this) {
        is EditResult.Success -> value
        is EditResult.Failure -> fail("expected success but got $error").let { throw IllegalStateException() }
    }

    private fun EditResult<TemplateResult>.failure(): String = when (this) {
        is EditResult.Success -> fail("expected a failure").let { throw IllegalStateException() }
        is EditResult.Failure -> error.toString()
    }

    // region the built-in templates

    @Test
    fun `the built-in templates are sound`() {
        val templates = BuiltInTemplates.all
        assertTrue(templates.size >= 3)
        assertEquals(templates.size, templates.map { it.id }.toSet().size)
        for (template in templates) {
            assertNull("${template.id}: ${template.problem()}", template.problem())
            assertTrue(template.placeholders.isNotEmpty())
            assertTrue(template.width > 0 && template.height > 0)
        }
        assertEquals(1080 to 1920, BuiltInTemplates.find("builtin-vertical-montage")!!.let { it.width to it.height })
        assertNull(BuiltInTemplates.find("nope"))
    }

    @Test
    fun `every built-in template turns into a valid timeline with the same structure`() {
        for (template in BuiltInTemplates.all) {
            val result = TemplateInstantiator.instantiate(template, fillsFor(template)).ok()
            assertEquals("${template.id}: ${result.timeline.invariantViolations()}", emptyList<String>(), result.timeline.invariantViolations())
            assertTrue("${template.id}: ${result.warnings}", result.warnings.isEmpty())
            assertEquals(template.timeline.tracks.map { it.id }, result.timeline.tracks.map { it.id })
            // Titles and stickers stay; every media clip got real media.
            val titlesBefore = template.timeline.tracks.flatMap { it.clips }.count { it.title != null }
            assertEquals(titlesBefore, result.timeline.tracks.flatMap { it.clips }.count { it.title != null })
            assertTrue(result.timeline.tracks.flatMap { it.clips }.none { Placeholder.idOfSlotAsset(it.assetId) != null })
            assertEquals(template.timeline.transitions.size, result.timeline.transitions.size)
            assertEquals(fillsFor(template).values.map { it.asset.id }.toSet(), result.assets.map { it.id }.toSet())
        }
    }

    // endregion

    // region fitting

    private val montage get() = BuiltInTemplates.find("builtin-vertical-montage")!!

    @Test
    fun `a longer video is trimmed to the slot from the chosen start`() {
        val fills = fillsFor(montage).toMutableMap()
        fills["clip1"] = SlotFill(video("long", 600), sourceInFrame = 100)
        val clip = TemplateInstantiator.instantiate(montage, fills).ok().timeline.track("track-v1")!!.clips.first()
        assertEquals("long", clip.assetId)
        assertEquals(100L, clip.sourceIn.value)
        assertEquals(190L, clip.sourceOut.value)
        assertEquals(0L, clip.timelineStart.value)
    }

    @Test
    fun `a shorter video shortens its slot and the clips and titles after it move up`() {
        val fills = fillsFor(montage).toMutableMap()
        fills["clip1"] = SlotFill(video("short", 45))
        val result = TemplateInstantiator.instantiate(montage, fills).ok()
        val base = result.timeline.track("track-v1")!!.clips
        assertEquals(45L, base[0].durationFrames)
        assertEquals(45L, base[1].timelineStart.value)
        assertEquals(base.last().timelineEnd.value, 5 * 90L - 45L)
        // The title sat over the first slot (frames 6..78): it is cut where the base lost its tail.
        assertEquals(45L, result.timeline.track("track-t1")!!.clips.single().timelineEnd.value)
        assertEquals(emptyList<String>(), result.timeline.invariantViolations())
    }

    @Test
    fun `a file much shorter than its slot is reported`() {
        val fills = fillsFor(montage).toMutableMap()
        fills["clip2"] = SlotFill(video("tiny", 20))
        val warnings = TemplateInstantiator.instantiate(montage, fills).ok().warnings
        assertTrue(warnings.any { it.contains("Clip 2") && it.contains("shorter") })
    }

    @Test
    fun `a photo takes the slot length and is drawn as a still`() {
        val fills = fillsFor(montage).toMutableMap()
        fills["clip3"] = SlotFill(photo("pic"))
        val clip = TemplateInstantiator.instantiate(montage, fills).ok().timeline.track("track-v1")!!.clips[2]
        assertEquals(StillKind.PHOTO, clip.still)
        assertEquals(90L, clip.durationFrames)
        assertEquals("pic", clip.assetId)
    }

    @Test
    fun `a picture of another shape is centre-cropped to the canvas and a matching one is left alone`() {
        val fills = fillsFor(montage).toMutableMap()
        fills["clip1"] = SlotFill(video("wide"), width = 1920, height = 1080)
        fills["clip2"] = SlotFill(video("tall"), width = 1080, height = 1920)
        val clips = TemplateInstantiator.instantiate(montage, fills).ok().timeline.track("track-v1")!!.clips
        assertTrue(clips[0].transform.scaleX > 3.0)
        assertEquals(0.0, clips[0].transform.positionX, 1e-9)
        assertEquals(1.0, clips[1].transform.scaleX, 1e-9)
    }

    @Test
    fun `an empty required slot is an error and an empty optional one is removed`() {
        val missing = fillsFor(montage).toMutableMap().apply { remove("clip4") }
        assertTrue(TemplateInstantiator.instantiate(montage, missing).failure().contains("Clip 4"))

        val noMusic = fillsFor(montage).toMutableMap().apply { remove("music") }
        val result = TemplateInstantiator.instantiate(montage, noMusic).ok()
        assertTrue(result.timeline.track("track-a1")!!.clips.isEmpty())
        assertEquals(emptyList<String>(), result.timeline.invariantViolations())
    }

    @Test
    fun `media of the wrong kind is refused`() {
        val outro = BuiltInTemplates.find("builtin-intro-outro")!!
        val asPhoto = fillsFor(outro).toMutableMap().apply { put("main", SlotFill(photo("p"))) }
        assertTrue(TemplateInstantiator.instantiate(outro, asPhoto).failure().contains("Main clip"))
        val asAudio = fillsFor(montage).toMutableMap().apply { put("clip1", SlotFill(song("s"))) }
        assertTrue(TemplateInstantiator.instantiate(montage, asAudio).failure().contains("Clip 1"))
        val asVideoMusic = fillsFor(montage).toMutableMap().apply { put("music", SlotFill(video("m"))) }
        // A video with sound is acceptable as a music source; a silent one is not.
        TemplateInstantiator.instantiate(montage, asVideoMusic).ok()
        val silent = fillsFor(montage).toMutableMap().apply { put("music", SlotFill(video("m").copy(hasAudio = false))) }
        assertTrue(TemplateInstantiator.instantiate(montage, silent).failure().contains("Music"))
    }

    @Test
    fun `a file with no footage after the chosen start is refused`() {
        val fills = fillsFor(montage).toMutableMap().apply { put("clip1", SlotFill(video("x", 100), sourceInFrame = 100)) }
        assertTrue(TemplateInstantiator.instantiate(montage, fills).failure().contains("no footage"))
    }

    @Test
    fun `transitions are kept where the footage allows and dropped with a warning where it does not`() {
        val generous = TemplateInstantiator.instantiate(montage, fillsFor(montage, 600)).ok()
        assertEquals(4, generous.timeline.transitions.size)
        assertTrue(generous.warnings.none { it.contains("dropped") })
        // Files exactly as long as their slots leave no spare footage after any cut.
        val exact = TemplateInstantiator.instantiate(montage, fillsFor(montage, 90)).ok()
        assertTrue(exact.timeline.transitions.size < 4)
        assertTrue(exact.warnings.any { it.contains("dropped") })
        assertEquals(emptyList<String>(), exact.timeline.invariantViolations())
        assertEquals(TransitionType.SLIDE, generous.timeline.transitions.first().type)
    }

    @Test
    fun `a template that is not sound is refused`() {
        val broken = montage.copy(placeholders = montage.placeholders.drop(1))
        assertNotNull(broken.problem())
        assertTrue(TemplateInstantiator.instantiate(broken, fillsFor(montage)).failure().isNotBlank())
    }

    // endregion

    // region saving a project as a template

    private fun project(): Pair<Timeline, List<MediaAssetDto>> {
        val a = Clip("a", "va", FrameIndex(0), FrameIndex(100), FrameIndex(200), transform = ClipTransform(positionX = 40.0), retimedFrames = 50)
        val b = Clip("b", "vb", FrameIndex(50), FrameIndex(300), FrameIndex(400))
        val t = Clip("t", null, FrameIndex(10), FrameIndex.ZERO, FrameIndex(40), title = TitleContent("Hello"))
        val m = Clip("m", "sm", FrameIndex(0), FrameIndex(0), FrameIndex(100))
        val timeline = Timeline(
            tracks = listOf(
                Track("tt", TrackType.TITLE, listOf(t)),
                Track("v", TrackType.VIDEO, listOf(a, b)),
                Track("au", TrackType.AUDIO, listOf(m)),
            ),
            transitions = listOf(Transition("x", "a", "b", 10)),
        )
        return timeline to listOf(video("va"), video("vb"), song("sm"))
    }

    @Test
    fun `saving a project as a template turns its media into placeholders and keeps the rest`() {
        val (timeline, assets) = project()
        val template = TemplateBuilder.fromProject("mine", "My cut", "two clips", 1920, 1080, 30, 1, "Rec709-SDR", timeline, assets)
        assertNull(template.problem())
        // In order of where they start: the first clip and the song both start at frame 0, then the second clip at 50.
        assertEquals(listOf(PlaceholderKind.VIDEO_OR_PHOTO, PlaceholderKind.AUDIO, PlaceholderKind.VIDEO_OR_PHOTO), template.placeholders.map { it.kind })
        assertEquals(listOf(50L, 100L, 100L), template.placeholders.map { it.frames })
        val clips = template.timeline.tracks.flatMap { it.clips }
        assertEquals("Hello", clips.single { it.title != null }.title!!.text)
        assertTrue(clips.none { it.assetId == "va" || it.assetId == "vb" || it.assetId == "sm" })
        // Speed, position and the file are gone from the slots.
        val slotA = clips.first { it.id == "a" }
        assertNull(slotA.retimedFrames)
        assertEquals(0.0, slotA.transform.positionX, 0.0)
        assertEquals(1, template.timeline.transitions.size)
    }

    @Test
    fun `a template made from a project can be filled again`() {
        val (timeline, assets) = project()
        val template = TemplateBuilder.fromProject("mine", "My cut", "", 1920, 1080, 30, 1, "Rec709-SDR", timeline, assets)
        val result = TemplateInstantiator.instantiate(template, fillsFor(template, 600)).ok()
        assertEquals(emptyList<String>(), result.timeline.invariantViolations())
        assertEquals(timeline.tracks.map { it.clips.map { c -> c.timelineStart.value to c.durationFrames } }, result.timeline.tracks.map { it.clips.map { c -> c.timelineStart.value to c.durationFrames } })
        assertEquals(1, result.timeline.transitions.size)
    }

    // endregion

    // region the file

    @Test
    fun `every built-in template survives the file format`() {
        for (template in BuiltInTemplates.all) {
            val back = TemplateFile.decode(TemplateFile.encode(template))
            assertEquals(template.id, back.id)
            assertEquals(template.placeholders, back.placeholders)
            assertEquals(template.width to template.height, back.width to back.height)
            fun layout(t: ProjectTemplate) = t.timeline.tracks.map { tr -> tr.id to tr.clips.map { Triple(it.id, it.timelineStart.value, it.durationFrames) } }
            assertEquals(layout(template), layout(back))
            assertEquals(template.timeline.transitions.map { it.type to it.durationFrames }, back.timeline.transitions.map { it.type to it.durationFrames })
            // A filled copy of the decoded template matches a filled copy of the original.
            assertEquals(
                TemplateInstantiator.instantiate(template, fillsFor(template)).ok().timeline.tracks.map { it.clips.size },
                TemplateInstantiator.instantiate(back, fillsFor(back)).ok().timeline.tracks.map { it.clips.size },
            )
        }
    }

    @Test
    fun `a file that is not a usable template is refused with a message`() {
        fun refused(text: String): String = try {
            TemplateFile.decode(text)
            fail("expected a refusal")
            ""
        } catch (e: TemplateFormatException) {
            e.message.orEmpty()
        }
        val good = TemplateFile.encode(montage)
        assertTrue(refused("not json at all").isNotBlank())
        assertTrue(refused(good.replace("\"format\": \"uvtemplate\"", "\"format\": \"something-else\"")).contains("not a"))
        assertTrue(refused(good.replace("\"version\": 1,", "\"version\": 9,")).contains("newer"))
        assertTrue(refused("x".repeat(TemplateFile.MAX_BYTES + 1)).contains("too big"))
        // A real file in the media library, or a clip pointing at one, makes it not a template.
        assertTrue(refused(good.replace("\"mediaLibrary\": []", "\"mediaLibrary\": [{\"id\":\"a\",\"uri\":\"content://x\",\"durationFrames\":10,\"nativeFpsNum\":30,\"nativeFpsDen\":1,\"colorSpace\":\"Rec709-SDR\"}]")).contains("media"))
        assertTrue(refused(good.replace("\"assetId\": \"slot:clip1\"", "\"assetId\": \"real-file\"")).isNotBlank())
        assertTrue(refused(good.replace("\"kind\": \"video-or-photo\"", "\"kind\": \"hologram\"")).contains("placeholder"))
    }

    // endregion

    // region the store

    @Test
    fun `the store lists the built-ins first and keeps the user's templates`() {
        val store = TemplateStore(tmp.newFolder("templates"))
        assertEquals(BuiltInTemplates.all.map { it.id }, store.all().map { it.id })

        val (timeline, assets) = project()
        val mine = TemplateBuilder.fromProject("mine", "My cut", "", 1920, 1080, 30, 1, "Rec709-SDR", timeline, assets)
        val stored = store.save(mine)
        assertEquals("mine", stored.id)
        assertEquals(BuiltInTemplates.all.size + 1, store.all().size)
        assertEquals("My cut", store.userTemplates().single().name)

        assertTrue(store.delete("mine"))
        assertTrue(store.userTemplates().isEmpty())
        assertFalse(store.delete("mine"))
    }

    @Test
    fun `saving under a taken or built-in id gets a free one`() {
        val store = TemplateStore(tmp.newFolder("templates"))
        val (timeline, assets) = project()
        val mine = TemplateBuilder.fromProject("mine", "My cut", "", 1920, 1080, 30, 1, "Rec709-SDR", timeline, assets)
        val first = store.save(mine)
        val second = store.save(mine)
        assertNotEquals(first.id, second.id)
        val clash = store.save(mine.copy(id = BuiltInTemplates.all.first().id))
        assertNotEquals(BuiltInTemplates.all.first().id, clash.id)
        assertEquals(3, store.userTemplates().size)
    }

    @Test
    fun `importing a template file stores it and a broken file in the folder is skipped`() {
        val dir = tmp.newFolder("templates")
        val store = TemplateStore(dir)
        val imported = store.import(TemplateFile.encode(montage.copy(id = "shared", name = "Shared montage")))
        assertEquals("shared", imported.id)
        java.io.File(dir, "broken.uvtemplate").writeText("{ nope")
        assertEquals(listOf("shared"), store.userTemplates().map { it.id })
        try {
            store.import("{ nope")
            fail("expected a refusal")
        } catch (e: TemplateFormatException) {
            // expected
        }
    }

    // endregion
}
