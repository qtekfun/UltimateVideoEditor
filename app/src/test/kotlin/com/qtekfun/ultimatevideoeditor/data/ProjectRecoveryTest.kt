package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private var counter = 0

    private fun repo() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw java.io.IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw java.io.IOException("unused")
        },
        ioDispatcher = UnconfinedTestDispatcher(),
        idGenerator = { "id-${++counter}" },
    )

    private fun dir(id: String) = File(tmp.root, "projects/$id")

    private fun asset(id: String, uri: String) =
        MediaAssetDto(id, uri, durationFrames = 100, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    @Test
    fun `a first save has no backup and every later save backs up the one before`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        assertFalse(File(dir(created.id), "project.json.bak").exists())

        repo.save(created.copy(name = "A2"))
        repo.save(created.copy(name = "A3"))

        // The backup is the last good save, one behind the file on disk.
        val bak = ProjectJson.decode(File(dir(created.id), "project.json.bak").readText())
        assertEquals("A2", bak.name)
        assertEquals("A3", repo.load(created.id).name)
    }

    @Test
    fun `a corrupt file is never copied over the backup`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        File(dir(created.id), "project.json").writeText("{ half a file")

        repo.save(created.copy(name = "A3"))

        // The backup still holds the last parsable save, not the garbage.
        assertEquals("A", ProjectJson.decode(File(dir(created.id), "project.json.bak").readText()).name)
    }

    @Test
    fun `a corrupt project is listed as recoverable when a backup parses`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        File(dir(created.id), "project.json").writeText("{ nope")

        val listing = repo.list()

        assertTrue(listing.projects.isEmpty())
        val unreadable = listing.unreadable.single()
        assertEquals(created.id, unreadable.id)
        assertTrue(unreadable.error is ProjectError.Corrupt)
        assertTrue(unreadable.recoverable)
    }

    @Test
    fun `recover restores the last good save and keeps the damaged file aside`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        File(dir(created.id), "project.json").writeText("{ nope")

        val recovered = repo.recover(created.id)

        // The backup is the save before the last one: "A" (the file damaged was "A2").
        assertEquals("A", recovered.name)
        assertEquals(recovered, repo.load(created.id))
        assertEquals("{ nope", File(dir(created.id), "project.json.corrupt").readText())
        assertTrue(repo.list().unreadable.isEmpty())
    }

    @Test
    fun `recover prefers the temp file of an interrupted write over the older backup`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        // A write that finished its temp file but died before the rename.
        File(dir(created.id), "project.json.tmp").writeText(ProjectJson.encode(created.copy(name = "A3"), null))
        File(dir(created.id), "project.json").writeText("")

        assertEquals("A3", repo.recover(created.id).name)
    }

    @Test
    fun `a half written temp file is skipped for the backup`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        File(dir(created.id), "project.json.tmp").writeText("{\"version\":1,\"id\":")
        File(dir(created.id), "project.json").delete()

        assertEquals("A", repo.recover(created.id).name)
    }

    @Test
    fun `a folder with no project file but a backup is listed and recoverable`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        File(dir(created.id), "project.json").delete()

        val unreadable = repo.list().unreadable.single()

        assertEquals(created.id, unreadable.id)
        assertTrue(unreadable.recoverable)
        assertNotNull(repo.recover(created.id))
        assertEquals(listOf(created.id), repo.list().projects.map { it.id })
    }

    @Test
    fun `with nothing usable left recover fails and the project is not recoverable`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        File(dir(created.id), "project.json").writeText("{ nope")

        assertFalse(repo.list().unreadable.single().recoverable)
        assertThrows(ProjectError.Corrupt::class.java) { runBlocking { repo.recover(created.id) } }
        // Nothing was moved or overwritten by the failed attempt.
        assertEquals("{ nope", File(dir(created.id), "project.json").readText())
        assertFalse(File(dir(created.id), "project.json.corrupt").exists())
    }

    @Test
    fun `recover of a healthy project changes nothing`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)

        assertEquals(created, repo.recover(created.id))
        assertFalse(File(dir(created.id), "project.json.corrupt").exists())
    }

    @Test
    fun `an unreadable project can be deleted`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        File(dir(created.id), "project.json").writeText("{ nope")

        repo.delete(created.id)

        assertFalse(dir(created.id).exists())
    }

    @Test
    fun `referenced media uris come from readable projects only`() = runTest {
        val repo = repo()
        val a = repo.create("A", settings)
        val b = repo.create("B", settings)
        repo.save(a.copy(mediaLibrary = listOf(asset("1", "content://x/1"), asset("2", "content://x/2"))))
        repo.save(b.copy(mediaLibrary = listOf(asset("3", "content://x/3"))))
        File(dir(b.id), "project.json").writeText("{ nope")

        assertEquals(setOf("content://x/1", "content://x/2"), repo.referencedMediaUris())
    }

    @Test
    fun `unknown fields survive a save that also writes the backup`() = runTest {
        val repo = repo()
        val created = repo.create("A", settings)
        val file = File(dir(created.id), "project.json")
        file.writeText(file.readText().trimEnd().removeSuffix("}") + ",\"futureField\":{\"x\":1}}")

        repo.save(repo.load(created.id).copy(name = "A2"))

        assertTrue(file.readText().contains("futureField"))
        assertTrue(File(dir(created.id), "project.json.bak").readText().contains("futureField"))
    }
}
