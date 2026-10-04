package com.ultimatevideo.uveditor.ui.library

import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.interchange.sampleProject
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.ui.editor.tray.usageCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelTest {
    private val project = sampleProject()
    private val timeline = TimelineMapper.toTimeline(project)
    private val usage = usageCounts(timeline)
    private val fps = FrameRate(30, 1)

    private fun ids(query: LibraryQuery, missing: Set<String> = emptySet()) =
        Library.items(project.mediaLibrary, usage, missing, query).map { it.asset.id }

    @Test
    fun `usage counts every clip that reads a file`() {
        // a1: three base clips and one overlay; a2: the song; a3: the photo; a4 is not used.
        assertEquals(mapOf("a1" to 4, "a2" to 1, "a3" to 1), usage)
    }

    @Test
    fun `filters by kind and by unused`() {
        assertEquals(listOf("a1", "a2", "a3", "a4"), ids(LibraryQuery()))
        assertEquals(listOf("a1", "a4"), ids(LibraryQuery(filter = LibraryFilter.VIDEO)))
        assertEquals(listOf("a2"), ids(LibraryQuery(filter = LibraryFilter.AUDIO)))
        assertEquals(listOf("a3"), ids(LibraryQuery(filter = LibraryFilter.IMAGE)))
        assertEquals(listOf("a4"), ids(LibraryQuery(filter = LibraryFilter.UNUSED)))
    }

    @Test
    fun `search matches name, tag and note without regard to case`() {
        assertEquals(listOf("a2"), ids(LibraryQuery(text = "SONG")))
        assertEquals(listOf("a1"), ids(LibraryQuery(text = "a-roll")))
        assertEquals(listOf("a1"), ids(LibraryQuery(text = "main CAMERA")))
        assertEquals(emptyList<String>(), ids(LibraryQuery(text = "nothing like this")))
    }

    @Test
    fun `the tag filter combines with the kind filter`() {
        assertEquals(listOf("a1"), ids(LibraryQuery(tag = "Interview")))
        assertEquals(emptyList<String>(), ids(LibraryQuery(tag = "interview", filter = LibraryFilter.AUDIO)))
    }

    @Test
    fun `items carry usage, tags, kind and the missing flag`() {
        val item = Library.items(project.mediaLibrary, usage, setOf("a1"), LibraryQuery()).first()
        assertEquals(AssetKind.VIDEO, item.kind)
        assertEquals("interview.mp4", item.name)
        assertEquals(4, item.usage)
        assertTrue(item.missing)
        assertEquals(listOf("interview", "a-roll"), item.tags)
    }

    @Test
    fun `tags are counted ignoring case, most used first`() {
        val assets = project.mediaLibrary.map { if (it.id == "a2") it.copy(tags = listOf("Interview", "music")) else it }
        assertEquals(listOf("interview" to 2, "a-roll" to 1, "music" to 1), Library.allTags(assets))
    }

    @Test
    fun `typed tags are cleaned, deduplicated and capped`() {
        assertEquals(listOf("one", "two three"), Library.parseTags("  one , two   three,ONE,, "))
        assertEquals(listOf("x".repeat(Library.MAX_TAG_LENGTH)), Library.parseTags("x".repeat(100)))
        assertEquals(Library.MAX_TAGS, Library.parseTags((1..40).joinToString(",") { "t$it" }).size)
    }

    @Test
    fun `setting tags and a note changes only that file`() {
        val tagged = Library.withTags(project.mediaLibrary, "a4", listOf(" b-roll ", "B-Roll", "night"))
        assertEquals(listOf("b-roll", "night"), tagged.first { it.id == "a4" }.tags)
        assertEquals(project.mediaLibrary.first { it.id == "a1" }, tagged.first { it.id == "a1" })

        val noted = Library.withNote(tagged, "a4", "  shaky  ")
        assertEquals("shaky", noted.first { it.id == "a4" }.note)
        assertNull(Library.withNote(noted, "a4", "   ").first { it.id == "a4" }.note)
        assertEquals(Library.MAX_NOTE_LENGTH, Library.cleanNote("n".repeat(1000))!!.length)
    }

    @Test
    fun `find in timeline lists the uses in time order and steps through them`() {
        val uses = Library.uses(timeline, "a1", fps)
        assertEquals(listOf("c1", "over-1", "c2", "c3"), uses.map { it.clipId })
        assertEquals(listOf("V1", "V2", "V1", "V1"), uses.map { it.trackLabel })
        assertEquals("over-1", Library.nextUse(uses, 0)!!.clipId)
        assertEquals("c3", Library.nextUse(uses, 150)!!.clipId)
        // After the last use it wraps to the first.
        assertEquals("c1", Library.nextUse(uses, 300)!!.clipId)
        assertNull(Library.nextUse(emptyList(), 0))
    }

    @Test
    fun `photos count as uses and titles do not use a file`() {
        assertEquals(listOf("photo-1"), Library.uses(timeline, "a3", fps).map { it.clipId })
        assertNull(Library.assetOfClip(timeline, "title-1"))
        assertEquals("a1", Library.assetOfClip(timeline, "c2"))
        assertEquals("a3", Library.assetOfClip(timeline, "photo-1"))
        assertNull(Library.assetOfClip(timeline, "nope"))
    }

    @Test
    fun `unused files are found and removed in order`() {
        assertEquals(listOf("a4"), Library.unused(project.mediaLibrary, usage).map { it.id })
        assertEquals(listOf("a1", "a2", "a3"), Library.withoutUnused(project.mediaLibrary, usage).map { it.id })
    }
}
