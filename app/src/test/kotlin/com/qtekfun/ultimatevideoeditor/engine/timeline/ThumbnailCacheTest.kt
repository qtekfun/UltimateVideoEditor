package com.qtekfun.ultimatevideoeditor.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `directory lives under the project's thumbnails folder and is created`() {
        val project = folder.newFolder("project")
        val dir = ThumbnailCache(project).dirFor("asset-1")

        assertTrue(dir.isDirectory)
        assertEquals(java.io.File(java.io.File(project, "thumbnails"), "asset-1"), dir)
    }

    @Test
    fun `same asset always maps to the same directory`() {
        val cache = ThumbnailCache(folder.newFolder("project"))
        assertEquals(cache.dirFor("clip_7"), cache.dirFor("clip_7"))
    }

    @Test
    fun `asset ids cannot escape the cache directory`() {
        val project = folder.newFolder("project")
        val dir = ThumbnailCache(project).dirFor("../../etc/passwd")

        assertTrue(dir.canonicalPath.startsWith(java.io.File(project, "thumbnails").canonicalPath + java.io.File.separator))
        assertEquals("______etc_passwd", dir.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank asset id is rejected`() {
        ThumbnailCache(folder.newFolder("project")).dirFor("  ")
    }
}
