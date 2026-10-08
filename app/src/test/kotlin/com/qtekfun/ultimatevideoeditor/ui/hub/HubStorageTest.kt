package com.qtekfun.ultimatevideoeditor.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubStorageTest {

    /** A directory tree in a map: path to its entries. */
    private class FakeLister(private val tree: Map<String, List<DiskEntry>>) : DiskLister {
        val listed = mutableListOf<String>()

        override fun list(path: String): List<DiskEntry> {
            listed += path
            return tree[path].orEmpty()
        }
    }

    private fun file(name: String, size: Long) = DiskEntry(name, false, size)
    private fun dir(name: String) = DiskEntry(name, true, 0)

    private val tree = mapOf(
        "/files/projects" to listOf(dir("p1"), dir("p2"), file("stray.txt", 7)),
        "/files/projects/p1" to listOf(file("project.json", 100), file("project.json.bak", 90), dir("thumbnails"), dir("waveforms")),
        "/files/projects/p1/thumbnails" to listOf(file("t0", 1000), dir("asset")),
        "/files/projects/p1/thumbnails/asset" to listOf(file("tile", 500)),
        "/files/projects/p1/waveforms" to listOf(file("a.peaks", 50)),
        "/files/projects/p2" to listOf(file("project.json", 10), dir("stab"), dir("other")),
        "/files/projects/p2/stab" to listOf(file("s", 20)),
        "/files/projects/p2/other" to listOf(file("o", 5)),
        "/cache" to listOf(dir("proxies"), dir("misc"), file("loose", 3)),
        "/cache/proxies" to listOf(file("x.mp4", 4000), dir("sub")),
        "/cache/proxies/sub" to listOf(file("y.mp4", 1000)),
        "/cache/misc" to listOf(file("m", 30)),
    )

    private fun scanner(lister: DiskLister = FakeLister(tree)) = StorageScanner(lister, "/files/projects", "/cache", { 123L })

    @Test
    fun `sizes are split into projects, cache and proxies`() {
        val snapshot = scanner().scan()

        // p1: json 100 + bak 90 count as project data, thumbnails 1500 + waveforms 50 as cache; p2: json 10 + other 5, stab 20 as cache.
        assertEquals(100 + 90 + 10 + 5L, snapshot.projectsBytes)
        assertEquals(1500 + 50 + 20 + 30 + 3L, snapshot.cacheBytes)
        assertEquals(5000L, snapshot.proxyBytes)
        assertEquals(123L, snapshot.freeBytes)
    }

    @Test
    fun `each project folder has its own total including its caches`() {
        val snapshot = scanner().scan()
        assertEquals(100 + 90 + 1500 + 50L, snapshot.perProject["p1"])
        assertEquals(10 + 20 + 5L, snapshot.perProject["p2"])
        // A loose file in the projects folder is not a project.
        assertEquals(setOf("p1", "p2"), snapshot.perProject.keys)
    }

    @Test
    fun `an empty or missing store measures zero`() {
        val snapshot = scanner(FakeLister(emptyMap())).scan()
        assertEquals(0L, snapshot.usedBytes)
        assertTrue(snapshot.perProject.isEmpty())
        assertTrue(snapshot.segments.isEmpty())
    }

    @Test
    fun `segments are shares of the used bytes in a fixed order`() {
        val snapshot = StorageSnapshot(projectsBytes = 50, cacheBytes = 30, proxyBytes = 20, freeBytes = 1, perProject = emptyMap())
        assertEquals(listOf(StorageKind.PROJECTS, StorageKind.CACHE, StorageKind.PROXIES), snapshot.segments.map { it.kind })
        assertEquals(listOf(0.5f, 0.3f, 0.2f), snapshot.segments.map { it.fraction })
        assertEquals(100L, snapshot.usedBytes)
    }
}
