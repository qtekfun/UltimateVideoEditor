package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectJson
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.model.ProjectDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files = HashMap<String, ByteArray>()
    private var counter = 0

    private val io = object : ProjectTransferIO {
        override fun read(uri: String) = files[uri] ?: throw java.io.FileNotFoundException(uri)

        override fun write(uri: String, bytes: ByteArray) {
            files[uri] = bytes
        }
    }

    private val mediaBytes = mapOf(
        "content://media/1" to ByteArray(4096) { (it % 200).toByte() },
        "file:///music/song.wav" to ByteArray(512) { 3 },
    )

    private val access = object : BundleMediaSource {
        override fun sizeOf(uri: String): Long? = mediaBytes[uri]?.size?.toLong()

        override fun open(uri: String): InputStream? = mediaBytes[uri]?.let { ByteArrayInputStream(it) }
    }

    private fun repo(dir: String = "projects", media: BundleMediaSource? = access) = ProjectRepository(
        rootDir = File(tmp.root, dir),
        transferIO = io,
        ioDispatcher = UnconfinedTestDispatcher(),
        idGenerator = { "id-${++counter}" },
        mediaAccess = media,
    )

    /** Stores the sample project in [repo] under its own id. */
    private fun seed(repo: ProjectRepository, project: ProjectDto = sampleProject()): ProjectDto = runBlocking {
        repo.save(project)
        project
    }

    @Test
    fun `export with media then import on another repository unpacks the files and points the project at them`() = runBlocking {
        val source = repo("a")
        seed(source)
        val result = source.exportBundle("p1", "doc://bundle", includeMedia = true)
        assertEquals(2, result.mediaCopied)

        val target = repo("b")
        val report = target.importWithReport("doc://bundle")
        val summary = report.bundle!!
        assertEquals(2, summary.mediaCopied)
        assertEquals(0, summary.relinked)
        assertEquals(listOf("photo.jpg", "unused.mp4"), summary.missing.sorted())

        val imported = target.load(report.project.id)
        val a1 = imported.mediaLibrary.first { it.id == "a1" }
        assertTrue(a1.uri, a1.uri.startsWith("file://"))
        val copy = File(a1.uri.removePrefix("file://"))
        assertTrue(copy.isFile)
        assertEquals(4096L, copy.length())
        // Everything else about the project, including the fields added by the library, came along.
        assertEquals(listOf("interview", "a-roll"), a1.tags)
        assertEquals("main camera", a1.note)
        assertEquals(sampleProject().tracks, imported.tracks)
        assertEquals(sampleProject().markers, imported.markers)
    }

    @Test
    fun `a bundle without media is relinked by name and size to files other projects already use`() = runBlocking {
        val source = repo("a")
        seed(source)
        source.exportBundle("p1", "doc://names", includeMedia = false)

        // The target already has a project that points at the same files, under other URIs.
        val target = repo("b")
        val other = sampleProject().copy(
            id = "other",
            name = "Other",
            mediaLibrary = sampleProject().mediaLibrary.map { it.copy(uri = it.uri.replace("media/", "local/")) },
        )
        seed(target, other)
        val access2 = object : BundleMediaSource {
            override fun sizeOf(uri: String): Long? = mediaBytes[uri.replace("local/", "media/")]?.size?.toLong()

            override fun open(uri: String): InputStream? = null
        }
        val repo2 = repo("b", access2)
        val report = repo2.importWithReport("doc://names")

        val summary = report.bundle!!
        assertEquals(0, summary.mediaCopied)
        // The video and the song have the same name and size as files the other project uses; the photo and the unused clip do not.
        assertEquals(2, summary.relinked)
        assertEquals(listOf("photo.jpg", "unused.mp4"), summary.missing.sorted())
        val imported = repo2.load(report.project.id)
        assertEquals("content://local/1", imported.mediaLibrary.first { it.id == "a1" }.uri)
    }

    @Test
    fun `importing a plain project file still works and reports no bundle`() = runBlocking {
        val project = sampleProject()
        files["doc://json"] = ProjectJson.encode(project).toByteArray()
        val report = repo("c").importWithReport("doc://json")
        assertEquals(null, report.bundle)
        assertEquals(project.name, report.project.name)
    }

    @Test
    fun `a name clash is resolved by renaming the imported project`() = runBlocking {
        val source = repo("a")
        seed(source)
        source.exportBundle("p1", "doc://b", includeMedia = false)
        val target = repo("t")
        val first = target.importWithReport("doc://b").project
        val second = target.importWithReport("doc://b").project
        assertEquals("Sample & Co", first.name)
        assertEquals("Sample & Co (2)", second.name)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `a broken bundle leaves no project and no scratch folder behind`() = runBlocking {
        val source = repo("a")
        seed(source)
        source.exportBundle("p1", "doc://good", includeMedia = true)
        val good = files.getValue("doc://good")
        files["doc://cut"] = good.copyOf(good.size - 600)

        val target = repo("t")
        assertThrows(Exception::class.java) { runBlocking { target.importWithReport("doc://cut") } }
        val root = File(tmp.root, "t")
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
        assertTrue(target.list().projects.isEmpty())
    }

    @Test
    fun `a bundle that is not one is reported with a readable message`() = runBlocking {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
                zip.write(1)
                zip.closeEntry()
            }
        }.toByteArray()
        files["doc://zip"] = bytes
        val error = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo("t").importWithReport("doc://zip") } }
        assertTrue(error.message, error.message!!.contains("not a ultimateVE project bundle"))
    }

    @Test
    fun `a hostile entry name is refused and nothing is written outside the projects folder`() = runBlocking {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("bundle.json"))
                zip.write("""{"format":"uveditor-bundle","formatVersion":1}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("media/../../escaped.mp4"))
                zip.write(1)
                zip.closeEntry()
            }
        }.toByteArray()
        files["doc://evil"] = bytes
        assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo("t").importWithReport("doc://evil") } }
        assertFalse(File(tmp.root, "escaped.mp4").exists())
        assertFalse(File(tmp.root, "t/escaped.mp4").exists())
    }
}
