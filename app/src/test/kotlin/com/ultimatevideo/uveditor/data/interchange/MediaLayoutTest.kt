package com.ultimatevideo.uveditor.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MediaLayoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun chosen(name: String = "Movies") = FakeMediaFolder(File(tmp.root, name))

    @Test
    fun `ensure creates the root and the category lazily and reports what it made`() {
        val folder = chosen()
        val created = ArrayList<MediaFolder>()
        MediaLayout.path(folder, MediaLayout.MEDIA).ensure(created)
        assertEquals(listOf("ultimateVE", "Media"), created.map { it.name })
        assertTrue(File(folder.dir, "ultimateVE/Media").isDirectory)
        assertFalse(File(folder.dir, "ultimateVE/Project-Backups").exists())
    }

    @Test
    fun `find never creates anything`() {
        val folder = chosen()
        assertNull(MediaLayout.path(folder, MediaLayout.MEDIA).find())
        assertTrue(folder.dir.list().orEmpty().isEmpty())
    }

    @Test
    fun `existing folders are reused ignoring case and only the missing part is created`() {
        val folder = chosen()
        File(folder.dir, "ULTIMATEve").mkdirs()
        val created = ArrayList<MediaFolder>()
        MediaLayout.path(folder, MediaLayout.PROJECT_BACKUPS).ensure(created)
        assertEquals(listOf("Project-Backups"), created.map { it.name })
        assertEquals(listOf("ULTIMATEve"), folder.dir.list().orEmpty().toList())
    }

    @Test
    fun `a chosen folder that is called ultimateVE is the root itself`() {
        val folder = chosen("ultimateVE")
        val created = ArrayList<MediaFolder>()
        MediaLayout.path(folder, MediaLayout.MEDIA).ensure(created)
        assertEquals(listOf("Media"), created.map { it.name })
        assertFalse(File(folder.dir, "ultimateVE").exists())
        assertEquals(listOf("Media"), folder.dir.list().orEmpty().toList())
    }

    @Test
    fun `a file called ultimateVE blocks the folder with an error and nothing is added`() {
        val folder = chosen()
        File(folder.dir, "ultimateVE").writeText("x")
        assertThrows(IOException::class.java) { MediaLayout.path(folder, MediaLayout.MEDIA).ensure(ArrayList()) }
        assertEquals(listOf("ultimateVE"), folder.dir.list().orEmpty().toList())
    }

    @Test
    fun `project folder names are cleaned and made unique per Media folder`() {
        assertEquals("Holiday", MediaLayout.projectFolderName("Holiday", emptyList()))
        assertEquals("Holiday (2)", MediaLayout.projectFolderName("Holiday", listOf("holiday")))
        assertEquals("Holiday (3)", MediaLayout.projectFolderName("Holiday", listOf("Holiday", "Holiday (2)")))
        assertEquals("a_b_c", MediaLayout.projectFolderName("a:b|c", emptyList()))
        assertEquals("Project", MediaLayout.projectFolderName("  ", emptyList()))
        assertEquals("Project", MediaLayout.projectFolderName("../..", emptyList()))
        assertEquals("evil", MediaLayout.projectFolderName("../../evil", emptyList()))
    }

    @Test
    fun `discarding removes empty folders innermost first and stops at one that holds something`() {
        val folder = chosen()
        val created = ArrayList<MediaFolder>()
        val media = MediaLayout.path(folder, MediaLayout.MEDIA).ensure(created)
        MediaLayout.discardEmpty(created)
        assertTrue(folder.dir.list().orEmpty().isEmpty())

        created.clear()
        val again = MediaLayout.path(folder, MediaLayout.MEDIA).ensure(created)
        File(again.let { (it as FakeMediaFolder).dir }, "keep.txt").writeText("x")
        MediaLayout.discardEmpty(created)
        assertTrue(File(folder.dir, "ultimateVE/Media/keep.txt").isFile)
        assertTrue(media is FakeMediaFolder)
    }

    @Test
    fun `describe shows the effective path, the categories that exist and the loose files left by earlier imports`() {
        val folder = chosen("LFImport")
        File(folder.dir, "old1.MOV").writeText("x")
        File(folder.dir, "old2.MOV").writeText("x")
        val empty = MediaLayout.describe(folder)
        assertEquals("LFImport/ultimateVE", empty.path)
        assertFalse(empty.rootExists)
        assertTrue(empty.categories.isEmpty())
        assertEquals(2, empty.looseFiles)

        File(folder.dir, "ultimateVE/Media/P1").mkdirs()
        File(folder.dir, "ultimateVE/Media/P2").mkdirs()
        File(folder.dir, "ultimateVE/Project-Backups").mkdirs()
        File(folder.dir, "ultimateVE/Project-Backups/a.uvbundle").writeText("x")
        val summary = MediaLayout.describe(folder)
        assertTrue(summary.rootExists)
        assertEquals(listOf(CategoryCount("Media", 2), CategoryCount("Project-Backups", 1)), summary.categories)
        assertEquals(2, summary.looseFiles) // the ultimateVE folder is not a loose file
    }

    @Test
    fun `describe of a folder that is the root has no loose files and shows its own name`() {
        val folder = chosen("ultimateVE")
        File(folder.dir, "Media").mkdirs()
        val summary = MediaLayout.describe(folder)
        assertEquals("ultimateVE", summary.path)
        assertEquals(0, summary.looseFiles)
        assertEquals(listOf(CategoryCount("Media", 0)), summary.categories)
    }

    @Test
    fun `the categories not written by the app are listed for the documentation`() {
        assertEquals(listOf("LibraryMedia", "ReversedMedia", "UserMedia"), MediaLayout.NOT_USED)
    }
}
