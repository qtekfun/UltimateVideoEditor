package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The `missingMedia` count in project.json (SPECS 4.1): older files without it keep working, and it reaches the summary. */
class ProjectSummaryMissingMediaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun repository() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
        },
        idGenerator = { "p1" },
    )

    private val oldFile = """
        {
          "version": 1,
          "id": "p1",
          "name": "Old project",
          "settings": { "width": 1920, "height": 1080, "fpsNum": 30, "fpsDen": 1, "colorSpace": "Rec709-SDR" }
        }
    """.trimIndent()

    @Test
    fun `a file written before the field existed reads as zero`() {
        assertEquals(0, ProjectJson.decode(oldFile).missingMedia)
    }

    @Test
    fun `the listing of an old file has no missing media`() {
        val dir = File(tmp.root, "projects/p1").also { it.mkdirs() }
        File(dir, "project.json").writeText(oldFile)

        val summary = runBlocking { repository().list() }.projects.single()

        assertEquals(0, summary.missingMedia)
        assertEquals("Old project", summary.name)
    }

    @Test
    fun `a saved count reaches the summary`() = runBlocking {
        val repo = repository()
        val created = repo.create("Film", settings)

        repo.save(created.copy(missingMedia = 3))

        assertEquals(3, repo.list().projects.single().missingMedia)
        assertEquals(3, repo.load("p1").missingMedia)
    }

    @Test
    fun `saving an old file leaves its other fields and unknown keys alone`() = runBlocking {
        val dir = File(tmp.root, "projects/p1").also { it.mkdirs() }
        File(dir, "project.json").writeText(oldFile.replace("\"version\": 1,", "\"version\": 1, \"futureField\": 7,"))
        val repo = repository()

        repo.save(repo.load("p1").copy(missingMedia = 2))

        val raw = ProjectJson.parseObject(File(dir, "project.json").readText())
        assertEquals(JsonPrimitive(7), raw["futureField"])
        assertEquals(JsonPrimitive(2), raw["missingMedia"])
        assertEquals("Old project", repo.load("p1").name)
    }

    @Test
    fun `a negative or damaged count never reaches the screen as negative`() = runBlocking {
        val repo = repository()
        val created = repo.create("Film", settings)

        repo.save(created.copy(missingMedia = -4))

        assertEquals(0, repo.list().projects.single().missingMedia)
    }

    @Test
    fun `a duplicate keeps the count`() = runBlocking {
        val repo = ProjectRepository(
            rootDir = File(tmp.root, "projects"),
            transferIO = object : ProjectTransferIO {
                override fun read(uri: String): ByteArray = throw IOException("unused")

                override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
            },
            idGenerator = sequenceOf("p1", "p2").iterator().let { ids -> { ids.next() } },
        )
        val created: ProjectDto = repo.create("Film", settings)
        repo.save(created.copy(missingMedia = 1))

        repo.clone("p1")

        assertTrue(repo.list().projects.all { it.missingMedia == 1 })
        assertFalse(repo.list().projects.isEmpty())
        assertEquals(JsonObject::class, ProjectJson.parseObject(File(tmp.root, "projects/p2/project.json").readText())::class)
    }
}
