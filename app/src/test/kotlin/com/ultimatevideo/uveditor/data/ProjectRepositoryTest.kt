package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val io = MemoryTransferIO()
    private var counter = 0

    private fun repo() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = io,
        ioDispatcher = UnconfinedTestDispatcher(),
        idGenerator = { "id-${++counter}" },
    )

    @Test
    fun `create then load and list`() = runTest {
        val repo = repo()

        val created = repo.create("  My film  ", settings)

        assertEquals("My film", created.name)
        assertEquals(created, repo.load(created.id))
        val listing = repo.list()
        assertEquals(listOf(created.id), listing.projects.map { it.id })
        assertTrue(listing.unreadable.isEmpty())
    }

    @Test
    fun `blank and oversized names are rejected`() = runTest {
        val repo = repo()
        assertThrows(ProjectError.InvalidName::class.java) { kotlinx.coroutines.runBlocking { repo.create("   ", settings) } }
        assertThrows(ProjectError.InvalidName::class.java) {
            kotlinx.coroutines.runBlocking { repo.create("x".repeat(81), settings) }
        }
    }

    @Test
    fun `rename keeps the rest of the project`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)

        val renamed = repo.rename(created.id, "B")

        assertEquals(created.copy(name = "B"), repo.load(created.id))
        assertEquals("B", renamed.name)
    }

    @Test
    fun `clone gets a new id and a copy name and keeps unknown fields`() = runTest {
        val repo = repo()
        val original = repo.create("Film", settings)
        val file = File(tmp.root, "projects/${original.id}/project.json")
        file.writeText(file.readText().replaceFirst("{", "{\"futureField\": 42,"))

        val copy = repo.clone(original.id)

        assertNotEquals(original.id, copy.id)
        assertEquals("Film copy", copy.name)
        assertTrue(File(tmp.root, "projects/${copy.id}/project.json").readText().contains("\"futureField\": 42"))
        assertEquals(2, repo.list().projects.size)
    }

    @Test
    fun `delete removes the project and a second delete fails`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)

        repo.delete(created.id)

        assertTrue(repo.list().projects.isEmpty())
        assertFalse(File(tmp.root, "projects/${created.id}").exists())
        assertThrows(ProjectError.NotFound::class.java) { kotlinx.coroutines.runBlocking { repo.delete(created.id) } }
    }

    @Test
    fun `save leaves no temp file behind and survives overwrite`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)

        repo.save(created.copy(name = "A2"))

        val dir = File(tmp.root, "projects/${created.id}")
        assertEquals(listOf("project.json"), dir.list()!!.toList())
        assertEquals("A2", repo.load(created.id).name)
    }

    @Test
    fun `corrupt projects are reported instead of hiding the list`() = runTest {
        val repo = repo()
        val good = repo.create("Good", settings)
        File(tmp.root, "projects/bad").mkdirs()
        File(tmp.root, "projects/bad/project.json").writeText("{ nope")

        val listing = repo.list()

        assertEquals(listOf(good.id), listing.projects.map { it.id })
        assertEquals(listOf("bad"), listing.unreadable.map { it.id })
        assertTrue(listing.unreadable[0].error is ProjectError.Corrupt)
    }

    @Test
    fun `path traversal ids are refused`() = runTest {
        val repo = repo()
        assertThrows(ProjectError.InvalidId::class.java) { kotlinx.coroutines.runBlocking { repo.load("../etc") } }
        assertThrows(ProjectError.InvalidId::class.java) { kotlinx.coroutines.runBlocking { repo.delete("a/b") } }
    }

    @Test
    fun `export then import round trips and avoids id collisions`() = runTest {
        val repo = repo()
        val original = repo.create("Film", settings)

        repo.exportTo(original.id, "mem://out")
        val imported = repo.importFrom("mem://out")

        assertNotEquals(original.id, imported.id)
        assertEquals(original.copy(id = imported.id), imported)
        assertEquals(2, repo.list().projects.size)
    }

    @Test
    fun `import keeps its id when free`() = runTest {
        val source = repo()
        val original = source.create("Film", settings)
        source.exportTo(original.id, "mem://out")
        source.delete(original.id)

        val imported = source.importFrom("mem://out")

        assertEquals(original.id, imported.id)
    }

    @Test
    fun `import of garbage is a corrupt error and storage failures are io errors`() = runTest {
        val repo = repo()
        io.files["mem://bad"] = "garbage".toByteArray()

        assertThrows(ProjectError.Corrupt::class.java) { kotlinx.coroutines.runBlocking { repo.importFrom("mem://bad") } }
        assertThrows(ProjectError.Io::class.java) { kotlinx.coroutines.runBlocking { repo.importFrom("mem://missing") } }
        assertTrue(repo.list().projects.isEmpty())
    }

    private class MemoryTransferIO : ProjectTransferIO {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(uri: String): ByteArray = files[uri] ?: throw IOException("no such document $uri")
        override fun write(uri: String, bytes: ByteArray) {
            files[uri] = bytes
        }
    }
}
