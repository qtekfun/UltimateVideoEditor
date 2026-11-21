package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.data.folders.BrowsePosition
import com.qtekfun.ultimatevideoeditor.data.folders.BrowsePositionStore
import com.qtekfun.ultimatevideoeditor.data.folders.BrowseProblem
import com.qtekfun.ultimatevideoeditor.data.folders.FolderBrowseException
import com.qtekfun.ultimatevideoeditor.data.folders.FolderBrowser
import com.qtekfun.ultimatevideoeditor.data.folders.FolderEntry
import com.qtekfun.ultimatevideoeditor.data.folders.FolderPage
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFolder
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFoldersStore
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkedFoldersControllerTest {
    private val auth = "com.android.externalstorage.documents"
    private val tree = "content://$auth/tree/primary%3ARushes"

    private fun file(name: String, kind: RelinkKind = RelinkKind.VIDEO) = FolderEntry("r/$name", name, false, kind, 1L, 1L, "$tree/document/r%2F$name")
    private fun folder(name: String) = FolderEntry("r/$name", name, true, null, null, null, "$tree/document/r%2F$name")

    /** Folders by document id; [gate] holds a read until it is completed, [failing] makes reads fail. */
    private class FakeBrowser(val pages: Map<String, FolderPage>) : FolderBrowser {
        val reads = mutableListOf<String>()
        var failing: BrowseProblem? = null
        var gate: CompletableDeferred<Unit>? = null

        override fun rootId(treeUri: String): String? = if (treeUri.contains("/tree/")) "root" else null

        override suspend fun list(treeUri: String, documentId: String): FolderPage {
            reads += documentId
            gate?.await()
            failing?.let { throw FolderBrowseException(it) }
            return pages[documentId] ?: FolderPage(emptyList(), emptyList())
        }
    }

    private class MemoryStore(var saved: List<LinkedFolder> = emptyList()) : LinkedFoldersStore {
        override fun load() = saved

        override fun save(folders: List<LinkedFolder>) {
            saved = folders
        }
    }

    private val day = folder("Day 1")
    private val pages = mapOf(
        "root" to FolderPage(listOf(day), listOf(file("a.mp4"), file("b.jpg", RelinkKind.IMAGE))),
        day.documentId to FolderPage(emptyList(), listOf(file("c.mp4"))),
    )

    private class MemoryPosition(var saved: BrowsePosition? = null) : BrowsePositionStore {
        override fun load() = saved

        override fun save(position: BrowsePosition?) {
            saved = position
        }
    }

    private fun TestScope.controller(
        browser: FakeBrowser = FakeBrowser(pages),
        store: MemoryStore = MemoryStore(),
        retain: (String) -> Boolean = { true },
        positions: MemoryPosition = MemoryPosition(),
    ): LinkedFoldersController {
        var n = 0
        return LinkedFoldersController(store, browser, this, newId = { "lf-${n++}" }, retainAccess = retain, positions = positions)
    }

    @Test
    fun `linking a folder keeps it, remembers it and opens it`() = runTest {
        val store = MemoryStore()
        val c = controller(store = store)
        c.link(tree)
        advanceUntilIdle()

        assertEquals(listOf(LinkedFolder("lf-0", tree, "Rushes")), c.folders)
        assertEquals(c.folders, store.saved)
        assertEquals(listOf("Rushes"), c.browse.trail.map { it.name })
        assertEquals(listOf("a.mp4", "b.jpg"), c.visible?.files?.map { it.name })
    }

    @Test
    fun `a folder the platform will not keep is not linked`() = runTest {
        val store = MemoryStore()
        val c = controller(store = store, retain = { false })
        c.link(tree)
        advanceUntilIdle()

        assertTrue(c.folders.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertTrue(c.linkRefused)
        assertTrue(c.browse.atTop)
    }

    @Test
    fun `something that is not a tree is not linked`() = runTest {
        val c = controller()
        c.link("content://$auth/document/x")
        assertTrue(c.folders.isEmpty())
        assertTrue(c.linkRefused)
    }

    @Test
    fun `linking a folder again opens it and keeps one entry`() = runTest {
        val c = controller()
        c.link(tree)
        advanceUntilIdle()
        c.up()
        c.link(tree.replace("%3A", ":"))
        advanceUntilIdle()

        assertEquals(1, c.folders.size)
        assertFalse(c.browse.atTop)
        assertFalse(c.linkRefused)
    }

    @Test
    fun `walking in and out reads each folder once`() = runTest {
        val browser = FakeBrowser(pages)
        val c = controller(browser)
        c.link(tree)
        advanceUntilIdle()
        c.enter(day)
        advanceUntilIdle()
        assertEquals(listOf("Rushes", "Day 1"), c.browse.trail.map { it.name })
        assertEquals(listOf("c.mp4"), c.visible?.files?.map { it.name })

        c.up()
        advanceUntilIdle()
        c.enter(day)
        advanceUntilIdle()
        assertEquals(listOf("root", day.documentId), browser.reads)
    }

    @Test
    fun `refresh reads the folder on show again`() = runTest {
        val browser = FakeBrowser(pages)
        val c = controller(browser)
        c.link(tree)
        advanceUntilIdle()
        c.refresh()
        advanceUntilIdle()
        assertEquals(listOf("root", "root"), browser.reads)
    }

    @Test
    fun `while a folder is being read the tab says so, and a late answer for a folder already left is dropped`() = runTest {
        val browser = FakeBrowser(pages).also { it.gate = CompletableDeferred() }
        val c = controller(browser)
        c.link(tree)
        advanceUntilIdle()
        assertEquals(FolderLoad.Loading, c.load)

        // Leave before the answer comes: the folder list is not touched by it.
        c.up()
        browser.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.browse.atTop)
        assertEquals(FolderLoad.Idle, c.load)
    }

    @Test
    fun `a folder that cannot be read says why`() = runTest {
        val browser = FakeBrowser(pages).also { it.failing = BrowseProblem.ACCESS_LOST }
        val c = controller(browser)
        c.link(tree)
        advanceUntilIdle()
        assertEquals(FolderLoad.Failed(BrowseProblem.ACCESS_LOST), c.load)

        // Access comes back: refresh reads it for real and shows it.
        browser.failing = null
        c.refresh()
        advanceUntilIdle()
        assertTrue(c.load is FolderLoad.Loaded)
    }

    @Test
    fun `unlinking forgets the folder, leaves the one on show and keeps the others`() = runTest {
        val store = MemoryStore()
        val c = controller(store = store)
        c.link(tree)
        advanceUntilIdle()
        c.link("content://$auth/tree/1234-ABCD%3AMore")
        advanceUntilIdle()
        c.unlink("lf-1")

        assertEquals(listOf("lf-0"), c.folders.map { it.id })
        assertEquals(c.folders, store.saved)
        assertTrue(c.browse.atTop)
        assertEquals(FolderLoad.Idle, c.load)
    }

    @Test
    fun `renaming changes the label and the name at the top of the path`() = runTest {
        val store = MemoryStore()
        val c = controller(store = store)
        c.link(tree)
        advanceUntilIdle()
        c.enter(day)
        advanceUntilIdle()
        c.rename("lf-0", "Holiday")

        assertEquals("Holiday", c.folders.single().label)
        assertEquals("Holiday", store.saved.single().label)
        assertEquals(listOf("Holiday", "Day 1"), c.browse.trail.map { it.name })
        c.rename("lf-0", "   ")
        assertEquals("Holiday", c.folders.single().label)
    }

    @Test
    fun `the choice survives walking into another folder and clears when it is added`() = runTest {
        val c = controller()
        c.link(tree)
        advanceUntilIdle()
        c.setPicking(true)
        val a = c.visible!!.files.first().uri
        c.togglePicked(a)
        c.enter(day)
        advanceUntilIdle()
        val inner = c.visible!!.files.first().uri
        c.togglePicked(inner)

        assertEquals(listOf(a, inner), c.browse.picked)
        assertEquals(2, c.browse.pickNumber(inner))
        c.togglePicked(a)
        assertEquals(1, c.browse.pickNumber(inner))
        c.clearPicked()
        assertTrue(c.browse.picking)
        assertTrue(c.browse.picked.isEmpty())
        c.togglePicked(inner)
        c.finishPicking()
        assertFalse(c.browse.picking)
        assertTrue(c.browse.picked.isEmpty())
    }

    @Test
    fun `the filter and the search apply to what is shown`() = runTest {
        val c = controller()
        c.link(tree)
        advanceUntilIdle()
        c.setFilter(FolderFilter.PHOTO)
        assertEquals(listOf("b.jpg"), c.visible?.files?.map { it.name })
        c.setFilter(FolderFilter.ALL)
        c.setQuery("day")
        assertEquals(listOf("Day 1"), c.visible?.folders?.map { it.name })
        assertTrue(c.visible!!.files.isEmpty())
    }

    @Test
    fun `a tree that is not readable opens as unavailable instead of crashing`() = runTest {
        val store = MemoryStore(listOf(LinkedFolder("lf-9", "content://x/document/not-a-tree", "Odd")))
        val c = controller(store = store)
        c.open(c.folders.single())
        assertEquals(FolderLoad.Failed(BrowseProblem.UNAVAILABLE), c.load)
    }

    @Test
    fun `the tab opens where it was left`() = runTest {
        val store = MemoryStore()
        val positions = MemoryPosition()
        val first = controller(store = store, positions = positions)
        first.link(tree)
        advanceUntilIdle()
        first.enter(day)
        advanceUntilIdle()
        assertEquals(BrowsePosition("lf-0", listOf("root" to "Rushes", day.documentId to "Day 1")), positions.saved)

        // A new editor: the same folders and the same place, read again.
        val second = controller(browser = FakeBrowser(pages), store = store, positions = positions)
        advanceUntilIdle()
        assertEquals(listOf("Rushes", "Day 1"), second.browse.trail.map { it.name })
        assertEquals(listOf("c.mp4"), second.visible?.files?.map { it.name })
    }

    @Test
    fun `going back to the list forgets the place, and a place in a folder that was unlinked is not restored`() = runTest {
        val store = MemoryStore()
        val positions = MemoryPosition()
        val c = controller(store = store, positions = positions)
        c.link(tree)
        advanceUntilIdle()
        c.up()
        assertEquals(null, positions.saved)

        positions.saved = BrowsePosition("lf-gone", listOf("root" to "Rushes"))
        val fresh = controller(store = store, positions = positions)
        advanceUntilIdle()
        assertTrue(fresh.browse.atTop)
    }

    @Test
    fun `unlinking the folder on show forgets the place`() = runTest {
        val positions = MemoryPosition()
        val c = controller(positions = positions)
        c.link(tree)
        advanceUntilIdle()
        c.unlink("lf-0")
        assertEquals(null, positions.saved)
    }

    @Test
    fun `the sort applies to what is shown`() = runTest {
        val newer = FolderEntry("r/n", "n.mp4", false, RelinkKind.VIDEO, 1L, 900L, "$tree/document/r%2Fn.mp4")
        val older = FolderEntry("r/o", "o.mp4", false, RelinkKind.VIDEO, 1L, 100L, "$tree/document/r%2Fo.mp4")
        val c = controller(FakeBrowser(mapOf("root" to FolderPage(emptyList(), listOf(newer, older)))))
        c.link(tree)
        advanceUntilIdle()
        assertEquals(listOf("n.mp4", "o.mp4"), c.visible?.files?.map { it.name })
        c.setSort(FolderSort.NEWEST)
        assertEquals(listOf("n.mp4", "o.mp4"), c.visible?.files?.map { it.name })
        c.setSort(FolderSort.NAME)
        c.setSort(FolderSort.LARGEST)
        assertEquals(2, c.visible?.files?.size)
    }
}
