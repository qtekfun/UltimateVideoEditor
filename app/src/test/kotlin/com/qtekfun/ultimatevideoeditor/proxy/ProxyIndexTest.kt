package com.qtekfun.ultimatevideoeditor.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProxyIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun index(dir: File = File(tmp.root, "proxies")) = ProxyIndex(dir) { now }

    @Test
    fun `entries survive a restart`() {
        val first = index()
        val entry = readyEntry(first, "k1", bytes = 250)
        first.put(entry)

        val second = index()

        assertEquals(entry, second.get("k1"))
        assertEquals(250L, second.totalBytes())
        assertEquals(listOf("k1"), second.all().map { it.key })
    }

    @Test
    fun `a corrupt index reads as empty instead of crashing`() {
        val dir = File(tmp.root, "proxies").also { it.mkdirs() }
        File(dir, "index.json").writeText("{ this is not json")

        val index = index(dir)

        assertTrue(index.all().isEmpty())
        index.put(readyEntry(index, "k"))
        assertNotNull(index.get("k")) // and it keeps working
    }

    @Test
    fun `writes leave no temporary file behind`() {
        val index = index()
        index.put(readyEntry(index, "k"))

        assertFalse(File(tmp.root, "proxies/index.json.tmp").exists())
        assertTrue(File(tmp.root, "proxies/index.json").isFile)
    }

    @Test
    fun `eviction removes the least recently used until the budget fits`() {
        val index = index()
        for ((key, used) in listOf("old" to 1L, "mid" to 2L, "new" to 3L)) index.put(readyEntry(index, key, bytes = 100, lastUsed = used))

        val removed = index.evictToBudget(150)

        assertEquals(listOf("old", "mid"), removed.map { it.key })
        assertNull(index.get("old"))
        assertFalse(index.finalFileFor("old").exists()) // the file goes with the entry
        assertNotNull(index.get("new"))
        assertEquals(100L, index.totalBytes())
    }

    @Test
    fun `eviction keeps protected entries and anything queued or running`() {
        val index = index()
        index.put(readyEntry(index, "oldest", lastUsed = 1))
        index.put(readyEntry(index, "other", lastUsed = 2))
        index.put(readyEntry(index, "queued", lastUsed = 0).copy(state = ProxyState.QUEUED, fileName = null, bytes = 0))

        val removed = index.evictToBudget(0, protect = setOf("oldest"))

        assertEquals(listOf("other"), removed.map { it.key })
        assertNotNull(index.get("oldest"))
        assertNotNull(index.get("queued"))
    }

    @Test
    fun `touching an entry makes it the last to go`() {
        val index = index()
        index.put(readyEntry(index, "a", lastUsed = 1))
        index.put(readyEntry(index, "b", lastUsed = 2))
        now = 9_000
        index.touch("a")

        val removed = index.evictToBudget(100)

        assertEquals(listOf("b"), removed.map { it.key })
    }

    @Test
    fun `touches are persisted by flush`() {
        val index = index()
        index.put(readyEntry(index, "a", lastUsed = 1))
        now = 5_000
        index.touch("a")
        index.flush()

        assertEquals(5_000L, index(File(tmp.root, "proxies")).get("a")!!.lastUsedMs)
    }

    @Test
    fun `clearing deletes proxies and strays but spares the one being made`() {
        val index = index()
        index.put(readyEntry(index, "ready", bytes = 100))
        index.put(readyEntry(index, "running").copy(state = ProxyState.RUNNING, fileName = null, bytes = 0))
        index.finalFileFor("running").delete() // a running job has only its part file
        index.partFileFor("running").writeBytes(ByteArray(7))
        File(tmp.root, "proxies/stray.mp4").writeBytes(ByteArray(40))

        val freed = index.clear()

        assertEquals(140L, freed)
        assertNull(index.get("ready"))
        assertNotNull(index.get("running"))
        assertFalse(File(tmp.root, "proxies/stray.mp4").exists())
    }

    @Test
    fun `after a kill a running job goes back to the queue and its part file is deleted`() {
        val index = index()
        index.put(readyEntry(index, "job").copy(state = ProxyState.RUNNING, fileName = null, bytes = 0))
        index.partFileFor("job").writeBytes(ByteArray(10))

        val resume = index(File(tmp.root, "proxies")).recoverAfterKill()

        assertEquals(listOf("job"), resume.map { it.key })
        assertEquals(ProxyState.QUEUED, resume.single().state)
        assertFalse(index.partFileFor("job").exists())
    }

    @Test
    fun `after a kill a ready proxy with a missing or truncated file is dropped`() {
        val index = index()
        index.put(readyEntry(index, "missing", bytes = 100))
        index.put(readyEntry(index, "short", bytes = 100))
        index.put(readyEntry(index, "good", bytes = 100))
        index.finalFileFor("missing").delete()
        index.finalFileFor("short").writeBytes(ByteArray(10))

        index.recoverAfterKill()

        assertNull(index.get("missing"))
        assertNull(index.get("short"))
        assertNotNull(index.get("good"))
        assertFalse(index.finalFileFor("short").exists())
    }

    @Test
    fun `after a kill files nothing refers to are removed`() {
        val index = index()
        File(tmp.root, "proxies/orphan.mp4").writeBytes(ByteArray(5))
        File(tmp.root, "proxies/old.part").writeBytes(ByteArray(5))

        index.recoverAfterKill()

        assertFalse(File(tmp.root, "proxies/orphan.mp4").exists())
        assertFalse(File(tmp.root, "proxies/old.part").exists())
    }

    @Test
    fun `fileOf is null for a proxy that is not ready or whose file is gone`() {
        val index = index()
        val ready = readyEntry(index, "k")
        index.put(ready)
        assertNotNull(index.fileOf(ready))

        index.finalFileFor("k").delete()
        assertNull(index.fileOf(ready))
        assertNull(index.fileOf(ready.copy(state = ProxyState.STALE)))
    }

    @Test
    fun `removing an entry deletes its files`() {
        val index = index()
        index.put(readyEntry(index, "k"))
        index.partFileFor("k").writeBytes(ByteArray(3))

        index.remove("k")

        assertNull(index.get("k"))
        assertFalse(index.finalFileFor("k").exists())
        assertFalse(index.partFileFor("k").exists())
    }
}
