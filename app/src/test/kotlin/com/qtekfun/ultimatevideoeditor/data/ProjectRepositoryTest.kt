package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
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
        // No temp file is left; the previous good save stays as the backup.
        assertEquals(listOf("project.json", "project.json.bak"), dir.list()!!.sorted())
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
        assertEquals(original.copy(id = imported.id, name = "Film (2)"), imported)
        assertEquals(2, repo.list().projects.size)
    }

    @Test
    fun `creating a project with a taken name is rejected ignoring case and spaces`() = runTest {
        val repo = repo()
        repo.create("Film", settings)

        assertThrows(ProjectError.DuplicateName::class.java) { runBlockingCreate(repo, "film") }
        assertThrows(ProjectError.DuplicateName::class.java) { runBlockingCreate(repo, "  FILM  ") }
        assertEquals(1, repo.list().projects.size)
    }

    @Test
    fun `renaming onto another project's name is rejected but keeping or recasing your own is fine`() = runTest {
        val repo = repo()
        val a = repo.create("Alpha", settings)
        repo.create("Beta", settings)

        assertThrows(ProjectError.DuplicateName::class.java) { runBlockingRename(repo, a.id, "beta") }
        assertEquals("Alpha", repo.rename(a.id, "Alpha").name)
        assertEquals("ALPHA", repo.rename(a.id, "ALPHA").name)
    }

    @Test
    fun `clones get a free name`() = runTest {
        val repo = repo()
        val a = repo.create("Film", settings)

        assertEquals("Film copy", repo.clone(a.id).name)
        assertEquals("Film copy 2", repo.clone(a.id).name)
    }

    @Test
    fun `an explicit clone name that is taken is rejected`() = runTest {
        val repo = repo()
        val a = repo.create("Film", settings)

        assertThrows(ProjectError.DuplicateName::class.java) { runBlockingClone(repo, a.id, "FILM") }
    }

    private fun runBlockingCreate(repo: ProjectRepository, name: String) =
        kotlinx.coroutines.runBlocking { repo.create(name, settings) }

    private fun runBlockingRename(repo: ProjectRepository, id: String, name: String) =
        kotlinx.coroutines.runBlocking { repo.rename(id, name) }

    private fun runBlockingClone(repo: ProjectRepository, id: String, name: String) =
        kotlinx.coroutines.runBlocking { repo.clone(id, name) }

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
