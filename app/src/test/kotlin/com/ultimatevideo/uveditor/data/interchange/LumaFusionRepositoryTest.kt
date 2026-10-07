package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.SeekableDocument
import com.ultimatevideo.uveditor.data.interchange.LfFixture.ClipSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class LumaFusionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var counter = 0
    private val docs = HashMap<String, File>()

    private val io = object : ProjectTransferIO {
        override fun read(uri: String): ByteArray = (docs[uri] ?: throw java.io.FileNotFoundException(uri)).readBytes()

        override fun write(uri: String, bytes: ByteArray) = throw UnsupportedOperationException()

        override fun openInput(uri: String): InputStream = FileInputStream(docs[uri] ?: throw java.io.FileNotFoundException(uri))

        override fun openSeekable(uri: String): SeekableDocument? {
            val file = docs[uri] ?: return null
            return object : SeekableDocument {
                override val access = FileRandomAccess(java.io.RandomAccessFile(file, "r").channel)
                override fun close() = Unit
            }
        }
    }

    private fun repo(folder: MediaFolder?, probe: ((String) -> ProbedMedia?)? = null) = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = io,
        ioDispatcher = UnconfinedTestDispatcher(),
        idGenerator = { "id-${++counter}" },
        mediaFolder = folder?.let { f -> { f } },
        probeMedia = probe,
    )

    private val archive = LfFixture.archive(
        resolution = "[1920,1080]",
        tracks = listOf(
            LfFixture.track(
                0, 0, anchor = true,
                clips = listOf(ClipSpec("a", "clip1.MOV", 0, 600), ClipSpec("b", "clip1.MOV", 600, 600, sourceStart = 600)),
            ),
        ),
    )

    private fun pack(name: String, footage: Map<String, ByteArray>, json: String = archive): String {
        val file = File(tmp.root, "$name.lfpackage")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Test project.lfarchive"))
            zip.write(json.toByteArray())
            zip.closeEntry()
            for ((n, bytes) in footage) {
                zip.putNextEntry(ZipEntry(n))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        docs["doc://$name"] = file
        return "doc://$name"
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun projectDirs() = File(tmp.root, "projects").listFiles().orEmpty().map { it.name }

    @Test
    fun `a package unpacks its footage into the chosen folder and the project points at it`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        val footage = bytes(3000)
        val progress = ArrayList<ImportProgress>()
        val report = repo(folder).importWithReport(pack("p", mapOf("clip1.MOV" to footage))) { progress += it }
        val lf = checkNotNull(report.lumaFusion)
        assertEquals(1, lf.mediaCopied)
        assertTrue(lf.missing.isEmpty())
        assertEquals("content://fake/ultimateVE/Media/Test project/clip1.MOV", report.project.mediaLibrary.single().uri)
        assertTrue(footage.contentEquals(File(folder.dir, "ultimateVE/Media/Test project/clip1.MOV").readBytes()))
        assertEquals(listOf("ultimateVE"), folder.dir.list().orEmpty().toList()) // nothing loose in the chosen folder
        assertEquals(2, report.project.tracks.single().clips.size)
        assertEquals(listOf("id-2"), projectDirs()) // no scratch folder left
        assertEquals(footage.size.toLong(), progress.last().doneBytes)
        assertEquals(footage.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun `each import gets its own project folder and files already in the chosen folder are never touched`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        File(folder.dir, "clip1.MOV").writeBytes(byteArrayOf(9, 9, 9)) // an earlier import, loose in the chosen folder
        val uri = pack("p", mapOf("clip1.MOV" to bytes(100)))
        val r = repo(folder)
        val first = r.importWithReport(uri)
        val second = r.importWithReport(uri)
        assertEquals("content://fake/ultimateVE/Media/Test project/clip1.MOV", first.project.mediaLibrary.single().uri)
        assertEquals("content://fake/ultimateVE/Media/Test project (2)/clip1.MOV", second.project.mediaLibrary.single().uri)
        assertEquals(listOf<Byte>(9, 9, 9), File(folder.dir, "clip1.MOV").readBytes().toList())
    }

    @Test
    fun `a folder that is already named ultimateVE is used as the root and not nested`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "ultimateVE"))
        val report = repo(folder).importWithReport(pack("p", mapOf("clip1.MOV" to bytes(100))))
        assertEquals("content://fake/Media/Test project/clip1.MOV", report.project.mediaLibrary.single().uri)
        assertTrue(File(folder.dir, "Media/Test project/clip1.MOV").isFile)
        assertFalse(File(folder.dir, "ultimateVE").exists())
    }

    @Test
    fun `an ultimateVE folder that already exists inside the chosen one is reused whatever its case`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        File(folder.dir, "ULTIMATEVE/Media").mkdirs()
        repo(folder).importWithReport(pack("p", mapOf("clip1.MOV" to bytes(100))))
        assertEquals(listOf("ULTIMATEVE"), folder.dir.list().orEmpty().toList())
        assertTrue(File(folder.dir, "ULTIMATEVE/Media/Test project/clip1.MOV").isFile)
    }

    @Test
    fun `without a media folder the import asks for one and leaves nothing behind`() {
        val uri = pack("p", mapOf("clip1.MOV" to bytes(100)))
        assertThrows(ProjectError.MediaFolderRequired::class.java) { runBlocking { repo(null).importWithReport(uri) } }
        assertTrue(projectDirs().isEmpty())
    }

    @Test
    fun `an unavailable folder gives a clear error and no project`() {
        val folder = FakeMediaFolder(File(tmp.root, "media"), failListing = true)
        val uri = pack("p", mapOf("clip1.MOV" to bytes(100)))
        val e = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo(folder).importWithReport(uri) } }
        assertTrue(e.message!!.contains("media folder is not available"))
        assertTrue(projectDirs().isEmpty())
        val readOnly = FakeMediaFolder(File(tmp.root, "ro"), failCreate = true)
        assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo(readOnly).importWithReport(uri) } }
    }

    @Test
    fun `too little free space is reported before anything is copied`() {
        val folder = FakeMediaFolder(File(tmp.root, "media"), free = 10)
        val uri = pack("p", mapOf("clip1.MOV" to bytes(100)))
        val e = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo(folder).importWithReport(uri) } }
        assertTrue(e.message!!.contains("Not enough free space"))
        assertTrue(folder.dir.list().orEmpty().isEmpty()) // the folders this import made are gone too
        assertTrue(projectDirs().isEmpty())
    }

    @Test
    fun `cancelling removes the partial file and the project`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        val uri = pack("p", mapOf("clip1.MOV" to bytes(6 * 1024 * 1024)))
        val r = repo(folder)
        var job: Job? = null
        job = launch(UnconfinedTestDispatcher(), start = kotlinx.coroutines.CoroutineStart.LAZY) { r.importWithReport(uri) { job?.cancel() } }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(folder.dir.list().orEmpty().isEmpty()) // partial file, project folder, Media and ultimateVE: all created by this import
        assertTrue(projectDirs().isEmpty())
    }

    @Test
    fun `cancelling keeps the folders that were there before and removes only the one it made`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        File(folder.dir, "ultimateVE/Media/Old project").mkdirs()
        File(folder.dir, "ultimateVE/Media/Old project/a.MOV").writeBytes(byteArrayOf(1))
        val uri = pack("p", mapOf("clip1.MOV" to bytes(6 * 1024 * 1024)))
        val r = repo(folder)
        var job: Job? = null
        job = launch(UnconfinedTestDispatcher(), start = kotlinx.coroutines.CoroutineStart.LAZY) { r.importWithReport(uri) { job?.cancel() } }
        job.start()
        job.join()
        assertEquals(listOf("Old project"), File(folder.dir, "ultimateVE/Media").list().orEmpty().toList())
        assertTrue(File(folder.dir, "ultimateVE/Media/Old project/a.MOV").isFile)
    }

    @Test
    fun `a package with no footage in it creates no folders`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        repo(folder).importWithReport(pack("p", emptyMap()))
        assertTrue(folder.dir.list().orEmpty().isEmpty())
    }

    @Test
    fun `deleting the project keeps the files in the users folder`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        val r = repo(folder)
        val report = r.importWithReport(pack("p", mapOf("clip1.MOV" to bytes(100))))
        r.delete(report.project.id)
        assertTrue(File(folder.dir, "ultimateVE/Media/Test project/clip1.MOV").isFile)
        assertTrue(projectDirs().isEmpty())
    }

    @Test
    fun `the real length rate and colour of the unpacked file replace the estimate`() = runBlocking {
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        val probed = ProbedMedia(durationMicros = 10_000_000, fpsNum = 30, fpsDen = 1, colorSpace = "Rec2020-HLG", hasVideo = true, hasAudio = false)
        val asked = ArrayList<String>()
        val report = repo(folder) { uri -> asked += uri; probed }.importWithReport(pack("p", mapOf("clip1.MOV" to bytes(100))))
        val asset = report.project.mediaLibrary.single()
        assertEquals(listOf("content://fake/ultimateVE/Media/Test project/clip1.MOV"), asked)
        assertEquals(300L, asset.durationFrames)
        assertEquals(30, asset.nativeFpsNum)
        assertEquals("Rec2020-HLG", asset.colorSpace)
        assertFalse(asset.hasAudio)
    }

    @Test
    fun `footage the package lacks is missing and the rest still comes across`() = runBlocking {
        val two = LfFixture.archive(
            tracks = listOf(
                LfFixture.track(0, 0, anchor = true, clips = listOf(ClipSpec("a", "clip1.MOV", 0, 600), ClipSpec("b", "other.MOV", 600, 600))),
            ),
        )
        val folder = FakeMediaFolder(File(tmp.root, "media"))
        val report = repo(folder).importWithReport(pack("p", mapOf("clip1.MOV" to bytes(100)), two))
        assertEquals(1, report.lumaFusion!!.mediaCopied)
        assertEquals(listOf("other.MOV"), report.lumaFusion.missing)
        assertTrue(report.project.mediaLibrary.first { it.displayName == "other.MOV" }.uri.startsWith("file://"))
    }

    @Test
    fun `a standalone archive imports with all media missing and needs no folder`() = runBlocking {
        val file = File(tmp.root, "x.lfarchive").also { it.writeText(archive) }
        docs["doc://x"] = file
        val report = repo(null).importWithReport("doc://x")
        val lf = report.lumaFusion!!
        assertEquals(0, lf.mediaCopied)
        assertEquals(listOf("clip1.MOV"), lf.missing)
        assertEquals(2, report.project.tracks.single().clips.size)
        assertNull(report.bundle)
    }

    @Test
    fun `an archive that is damaged is a typed error and leaves nothing behind`() {
        val file = File(tmp.root, "bad.lfarchive").also { it.writeText("""{"attributes":{"appVersion":"5"},"tracks":[],"resolution":[0,0]}""") }
        docs["doc://bad"] = file
        assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo(null).importWithReport("doc://bad") } }
        assertTrue(projectDirs().isEmpty())
    }

    @Test
    fun `a zip that is neither a bundle nor a package is still a bundle error`() {
        val file = File(tmp.root, "z.zip")
        ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("readme.txt")); zip.write(1); zip.closeEntry() }
        docs["doc://z"] = file
        assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo(null).importWithReport("doc://z") } }
    }
}
