package com.qtekfun.ultimatevideoeditor.data.folders

import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPagesTest {
    private fun file(name: String, mime: String? = null) = RawEntry("id/$name", name, mime, false, 10L, 5L)
    private fun dir(name: String) = RawEntry("id/$name", name, "vnd.android.document/directory", true, null, null)
    private val uriOf = { id: String -> "content://t/tree/r/document/$id" }

    @Test
    fun `folders come first and numbers sort as numbers`() {
        val page = FolderPages.build(
            listOf(file("clip10.mp4"), dir("Day 2"), file("clip2.mp4"), dir("Day 10"), file("Clip1.mp4"), dir("day 1")),
            uriOf,
        )
        assertEquals(listOf("day 1", "Day 2", "Day 10"), page.folders.map { it.name })
        assertEquals(listOf("Clip1.mp4", "clip2.mp4", "clip10.mp4"), page.files.map { it.name })
    }

    @Test
    fun `only media files are listed and hidden entries are left out`() {
        val page = FolderPages.build(
            listOf(file("a.mp4", "video/mp4"), file("notes.txt", "text/plain"), file(".thumb.jpg", "image/jpeg"), dir(".cache"), file("b.jpg"), file("c.wav", "application/octet-stream")),
            uriOf,
        )
        assertEquals(listOf("a.mp4", "b.jpg", "c.wav"), page.files.map { it.name })
        assertEquals(listOf(RelinkKind.VIDEO, RelinkKind.IMAGE, RelinkKind.AUDIO), page.files.map { it.kind })
        assertTrue(page.folders.isEmpty())
    }

    @Test
    fun `an entry carries its facts and the uri built from its id`() {
        val page = FolderPages.build(listOf(file("a.mp4", "video/mp4"), dir("Sub")), uriOf)
        val a = page.files.single()
        assertEquals("content://t/tree/r/document/id/a.mp4", a.uri)
        assertEquals(10L, a.sizeBytes)
        assertEquals(5L, a.modifiedMillis)
        assertEquals(true, page.folders.single().isFolder)
        assertEquals(null, page.folders.single().kind)
    }

    @Test
    fun `an empty folder is empty`() {
        assertTrue(FolderPages.build(emptyList(), uriOf).isEmpty)
        assertTrue(FolderPages.build(listOf(file("x.txt", "text/plain")), uriOf).isEmpty)
    }

    @Test
    fun `natural order ignores case and leading zeros and is stable`() {
        assertTrue(FolderPages.compareNatural("a2", "a10") < 0)
        assertTrue(FolderPages.compareNatural("A", "b") < 0)
        assertTrue(FolderPages.compareNatural("clip002", "clip10") < 0)
        assertTrue(FolderPages.compareNatural("clip", "clip1") < 0)
        assertEquals(0, FolderPages.compareNatural("same", "same"))
        // Equal apart from case: a fixed order, not a tie.
        assertTrue(FolderPages.compareNatural("Abc", "abc") != 0)
        assertEquals(-FolderPages.compareNatural("Abc", "abc"), FolderPages.compareNatural("abc", "Abc"))
    }
}
