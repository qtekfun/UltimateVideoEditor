package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The editor tells the Projects screen how many files are missing without rewriting project.json, so opening a project does not
 * move it to the top of the list (SPECS 4.1, `media-status.json`).
 */
class MediaStatusSidecarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private var nextId = 0

    private fun repository() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
        },
        idGenerator = { "p${++nextId}" },
    )

    private fun projectFile(id: String) = File(tmp.root, "projects/$id/project.json")

    private fun sidecar(id: String) = File(tmp.root, "projects/$id/media-status.json")

    @Test
    fun `the count reaches the list and project json is byte for byte what it was`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)
        val before = projectFile("p1").readBytes()
        val modified = projectFile("p1").lastModified()

        repo.saveMediaStatus("p1", 2)

        assertEquals(2, repo.list().projects.single().missingMedia)
        assertArrayEquals(before, projectFile("p1").readBytes())
        assertEquals(modified, projectFile("p1").lastModified())
        assertTrue(sidecar("p1").isFile)
        assertFalse("no temp or backup is made for a status", File(tmp.root, "projects/p1/project.json.tmp").exists())
    }

    @Test
    fun `checking the media of an old project does not move it to the top of the list`() = runBlocking {
        val repo = repository()
        repo.create("Old", settings)
        repo.create("New", settings)
        projectFile("p1").setLastModified(1_000_000L)
        projectFile("p2").setLastModified(2_000_000L)
        assertEquals(listOf("New", "Old"), repo.list().projects.map { it.name })

        repo.saveMediaStatus("p1", 3)

        val listing = repo.list().projects
        assertEquals(listOf("New", "Old"), listing.map { it.name })
        assertEquals(3, listing.last().missingMedia)
        assertEquals(1_000_000L, listing.last().lastModifiedMillis)
    }

    @Test
    fun `nothing is written when the list already shows that count`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)

        repo.saveMediaStatus("p1", 0)
        assertFalse(sidecar("p1").exists())

        repo.save(repo.load("p1").copy(missingMedia = 4))
        repo.saveMediaStatus("p1", 4)
        assertFalse(sidecar("p1").exists())

        repo.saveMediaStatus("p1", 1)
        val first = sidecar("p1").readText()
        repo.saveMediaStatus("p1", 1)
        assertEquals(first, sidecar("p1").readText())
    }

    @Test
    fun `the count can go back down to zero`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)
        repo.saveMediaStatus("p1", 2)

        repo.saveMediaStatus("p1", 0)

        assertEquals(0, repo.list().projects.single().missingMedia)
    }

    @Test
    fun `a later save of the project wins over an older sidecar`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)
        repo.saveMediaStatus("p1", 5)
        assertEquals(5, repo.list().projects.single().missingMedia)

        // The save carries its own count; the file's new modification time retires the sidecar.
        repo.save(repo.load("p1").copy(missingMedia = 1))
        projectFile("p1").setLastModified(projectFile("p1").lastModified() + 5_000)

        assertEquals(1, repo.list().projects.single().missingMedia)
    }

    @Test
    fun `a damaged sidecar is ignored`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)
        repo.save(repo.load("p1").copy(missingMedia = 2))
        sidecar("p1").writeText("{ not json")

        assertEquals(2, repo.list().projects.single().missingMedia)
    }

    @Test
    fun `a status for a project that does not exist is an explicit error`() {
        val repo = repository()

        val error = runCatching { runBlocking { repo.saveMediaStatus("nope", 1) } }.exceptionOrNull()

        assertTrue(error.toString(), error is ProjectError.NotFound)
    }

    @Test
    fun `the sidecar is not part of a copy or a bundle of the project`() = runBlocking {
        val repo = repository()
        repo.create("Film", settings)
        repo.saveMediaStatus("p1", 2)

        val copy = repo.clone("p1", "Copy")

        assertFalse(File(tmp.root, "projects/${copy.id}/media-status.json").exists())
        assertEquals(0, repo.list().projects.first { it.id == copy.id }.missingMedia)
    }
}
