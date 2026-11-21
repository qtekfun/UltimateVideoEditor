package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.data.folders.FolderEntry
import com.qtekfun.ultimatevideoeditor.data.folders.FolderPage
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBrowseStateTest {
    private val top = Crumb("root", "Rushes")
    private val day2 = Crumb("root/day2", "Day 2")
    private val take = Crumb("root/day2/take", "Take 1")

    private fun file(name: String, kind: RelinkKind) = FolderEntry("id/$name", name, false, kind, null, null, "content://x/$name")
    private fun folder(name: String) = FolderEntry("id/$name", name, true, null, null, null, "content://x/$name")

    @Test
    fun `it starts on the list of linked folders`() {
        val state = FolderBrowseState()
        assertTrue(state.atTop)
        assertEquals(null, state.current)
        assertSame(state, state.up())
        assertSame(state, state.enter(day2))
        assertSame(state, state.jumpTo(0))
    }

    @Test
    fun `opening, going in and going up walk the tree`() {
        val inside = FolderBrowseState().openLinked("lf-1", top).enter(day2).enter(take)
        assertEquals(listOf(top, day2, take), inside.trail)
        assertEquals(take, inside.current)
        assertEquals(listOf(top, day2), inside.up().trail)
        assertEquals(listOf(top), inside.up().up().trail)
        // Up from the top of a linked folder is back on the list of linked folders.
        assertTrue(inside.up().up().up().atTop)
        assertEquals(FolderBrowseState(), inside.up().up().up())
    }

    @Test
    fun `the path bar jumps to a piece of the path and ignores one that is not there`() {
        val inside = FolderBrowseState().openLinked("lf-1", top).enter(day2).enter(take)
        assertEquals(listOf(top), inside.jumpTo(0).trail)
        assertEquals(listOf(top, day2), inside.jumpTo(1).trail)
        assertSame(inside, inside.jumpTo(3))
        assertSame(inside, inside.jumpTo(-1))
    }

    @Test
    fun `the search is forgotten when the folder changes but the filter stays`() {
        val searching = FolderBrowseState().openLinked("lf-1", top).withFilter(FolderFilter.VIDEO).withQuery("beach")
        val entered = searching.enter(day2)
        assertEquals("", entered.query)
        assertEquals(FolderFilter.VIDEO, entered.filter)
        assertEquals("", entered.up().query)
        assertEquals("", searching.jumpTo(0).query)
    }

    @Test
    fun `the filter limits files and the search limits both folders and files`() {
        val page = FolderPage(
            folders = listOf(folder("Beach day"), folder("Town")),
            files = listOf(file("beach.mp4", RelinkKind.VIDEO), file("beach.jpg", RelinkKind.IMAGE), file("song.wav", RelinkKind.AUDIO)),
        )
        val state = FolderBrowseState().openLinked("lf-1", top)
        assertEquals(page, state.visible(page))
        assertEquals(listOf("beach.mp4"), state.withFilter(FolderFilter.VIDEO).visible(page).files.map { it.name })
        assertEquals(listOf("beach.jpg"), state.withFilter(FolderFilter.PHOTO).visible(page).files.map { it.name })
        assertEquals(listOf("song.wav"), state.withFilter(FolderFilter.AUDIO).visible(page).files.map { it.name })
        // The filter never hides a folder: the footage may be inside it.
        assertEquals(2, state.withFilter(FolderFilter.AUDIO).visible(page).folders.size)
        val searched = state.withQuery(" BEACH ").visible(page)
        assertEquals(listOf("Beach day"), searched.folders.map { it.name })
        assertEquals(listOf("beach.mp4", "beach.jpg"), searched.files.map { it.name })
        assertTrue(state.withQuery("zzz").visible(page).isEmpty)
    }

    @Test
    fun `files can be sorted by date or size, with the ones that have none last, and folders stay by name`() {
        fun f(name: String, size: Long?, modified: Long?) = FolderEntry("id/$name", name, false, RelinkKind.VIDEO, size, modified, "content://x/$name")
        val page = FolderPage(
            folders = listOf(folder("A"), folder("B")),
            files = listOf(f("a", 5, 100), f("b", null, null), f("c", 50, 300), f("d", 20, 200)),
        )
        val state = FolderBrowseState().openLinked("lf-1", top)
        assertEquals(listOf("a", "b", "c", "d"), state.visible(page).files.map { it.name })
        assertEquals(listOf("c", "d", "a", "b"), state.withSort(FolderSort.NEWEST).visible(page).files.map { it.name })
        assertEquals(listOf("c", "d", "a", "b"), state.withSort(FolderSort.LARGEST).visible(page).files.map { it.name })
        assertEquals(listOf("A", "B"), state.withSort(FolderSort.LARGEST).visible(page).folders.map { it.name })
        // The sort stays when the person walks into another folder.
        assertEquals(FolderSort.NEWEST, state.withSort(FolderSort.NEWEST).enter(day2).sort)
    }

    @Test
    fun `the sort button goes round the three ways`() {
        assertEquals(FolderSort.NEWEST, FolderSort.NAME.next())
        assertEquals(FolderSort.LARGEST, FolderSort.NEWEST.next())
        assertEquals(FolderSort.NAME, FolderSort.LARGEST.next())
    }

    @Test
    fun `clock text counts minutes and hours and never goes negative`() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:09", clockText(9_999))
        assertEquals("1:05", clockText(65_000))
        assertEquals("59:59", clockText(3_599_999))
        assertEquals("1:00:00", clockText(3_600_000))
        assertEquals("2:03:04", clockText(7_384_000))
        assertEquals("0:00", clockText(-5))
    }
}
