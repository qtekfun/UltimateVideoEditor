package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectJson
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.SeekableDocument
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The import of a project file or bundle as the process-wide import job sees it: the total is known from the zip's directory before
 * the first byte, every 256 KB chunk is counted, Cancel stops within a chunk and leaves nothing, a bad bundle is refused before its
 * media is copied, and none of it runs on the thread that asked (the main thread, in the app).
 */
class RepositoryImportProgressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files = HashMap<String, ByteArray>()
    private var counter = 0
    private var randomAccess = true

    /** Stands for the main thread: the source must never be touched on it. */
    private val callerThread: Thread = Thread.currentThread()
    private val threads = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "uv-io-${threads.incrementAndGet()}") }
    private val dispatcher = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun assertOffCaller() {
        assertTrue("the source was read on ${Thread.currentThread().name}", Thread.currentThread() !== callerThread && Thread.currentThread().name.startsWith("uv-io"))
    }

    private class BytesAccess(private val bytes: ByteArray) : RandomAccess {
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(len.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buf, off, n)
            return n
        }
    }

    private val io = object : ProjectTransferIO {
        override fun read(uri: String): ByteArray {
            assertOffCaller()
            return files[uri] ?: throw java.io.FileNotFoundException(uri)
        }

        override fun write(uri: String, bytes: ByteArray) = throw UnsupportedOperationException()

        override fun openInput(uri: String): InputStream {
            assertOffCaller()
            return ByteArrayInputStream(files[uri] ?: throw java.io.FileNotFoundException(uri))
        }

        override fun openSeekable(uri: String): SeekableDocument? {
            assertOffCaller()
            if (!randomAccess) return null
            val bytes = files[uri] ?: return null
            return object : SeekableDocument {
                override val access = BytesAccess(bytes)
                override fun close() = Unit
            }
        }
    }

    private val big = ByteArray(1024 * 1024) { (it % 251).toByte() }
    private val song = ByteArray(512) { 3 }

    private val media = object : BundleMediaSource {
        private val bytes = mapOf("content://media/1" to big, "file:///music/song.wav" to song)
        override fun sizeOf(uri: String): Long? = bytes[uri]?.size?.toLong()
        override fun open(uri: String): InputStream? = bytes[uri]?.let { ByteArrayInputStream(it) }
    }

    private fun repo(dir: String = "projects", freeBytes: (File) -> Long = { Long.MAX_VALUE }, maxDocumentBytes: Int = 64 * 1024 * 1024) = ProjectRepository(
        rootDir = File(tmp.root, dir),
        transferIO = io,
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
        freeBytes = freeBytes,
        maxDocumentBytes = maxDocumentBytes,
    )

    private fun bundle(): ByteArray {
        val project = sampleProject()
        val out = ByteArrayOutputStream()
        ProjectBundle.write(ProjectJson.encode(project), project, media, true, emptyMap(), out)
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifest = """{"format":"uveditor-bundle","formatVersion":1,"projectName":"x","media":[{"assetId":"a1","name":"big.mp4","sizeBytes":1048576,"entry":"media/a1-big.mp4"}]}""".toByteArray()

    private class Recorder(val cancelAfterChunks: Int = Int.MAX_VALUE) : BundleWriteObserver {
        val events = ArrayList<String>()
        var total = -1L
        var mediaCount = -1
        var done = 0L
        var chunks = 0
        val chunkSizes = ArrayList<Long>()
        val media = ArrayList<Pair<String, Int>>()
        var stopped = false

        override fun onStart(totalBytes: Long, mediaCount: Int) {
            events += "start"
            total = totalBytes
            this.mediaCount = mediaCount
        }

        override fun onItem(kind: BundleItemKind, name: String, mediaIndex: Int) {
            events += "item:$kind"
            if (kind == BundleItemKind.MEDIA) media += name to mediaIndex
        }

        override fun onBytes(count: Long) {
            events += "bytes"
            done += count
            chunks++
            chunkSizes += count
        }

        override fun isCancelled(): Boolean = (chunks >= cancelAfterChunks).also { if (it) stopped = true }
    }

    private fun root() = File(tmp.root, "projects")

    private fun nothingLeft(repo: ProjectRepository) {
        assertEquals(emptyList<String>(), root().list().orEmpty().toList())
        assertTrue(runBlocking { repo.list().projects.isEmpty() })
    }

    @Test
    fun `the total is known before the first byte and every chunk is counted`() {
        files["doc://b"] = bundle()
        val seen = Recorder()

        val report = runBlocking { repo().importWithReport("doc://b", seen) }

        val zip = ZipReader(BytesAccess(files.getValue("doc://b")))
        assertEquals("start comes first, before any byte", "start", seen.events.first())
        assertEquals(zip.entries.filter { !it.isDirectory }.sumOf { it.size }, seen.total)
        assertEquals(2, seen.mediaCount)
        assertEquals(seen.total, seen.done)
        assertTrue("1 MB in 256 KB chunks: ${seen.chunkSizes}", seen.chunkSizes.count { it == 256L * 1024 } >= 4)
        assertTrue(seen.chunkSizes.all { it <= 256L * 1024 })
        assertEquals(listOf(1, 2), seen.media.map { it.second })
        assertTrue("the user's file names, not entry names: ${seen.media}", seen.media.none { it.first.startsWith("media/") })
        assertEquals(2, report.bundle!!.mediaCopied)
        assertEquals(1024L * 1024, File(report.project.mediaLibrary.first { it.id == "a1" }.uri.removePrefix("file://")).length())
    }

    @Test
    fun `a source without random access still imports, counting bytes without a total`() {
        randomAccess = false
        files["doc://b"] = bundle()
        val seen = Recorder()

        val report = runBlocking { repo().importWithReport("doc://b", seen) }

        assertEquals(0L, seen.total)
        assertTrue(seen.done > 1024 * 1024)
        assertEquals(2, report.bundle!!.mediaCopied)
    }

    @Test
    fun `cancel stops within one chunk and leaves no project and no scratch folder`() {
        files["doc://b"] = bundle()
        val seen = Recorder(cancelAfterChunks = 2)
        val repo = repo()

        assertThrows(ImportCancelled::class.java) { runBlocking { repo.importWithReport("doc://b", seen) } }

        assertTrue(seen.stopped)
        assertTrue("stopped at the next chunk, not at the end: ${seen.chunks}", seen.chunks <= 4)
        nothingLeft(repo)
    }

    @Test
    fun `cancel also stops a stream import`() {
        randomAccess = false
        files["doc://b"] = bundle()
        val repo = repo()

        assertThrows(ImportCancelled::class.java) { runBlocking { repo.importWithReport("doc://b", Recorder(cancelAfterChunks = 1)) } }

        nothingLeft(repo)
    }

    @Test
    fun `a truncated bundle is refused with a readable message and leaves nothing`() {
        val good = bundle()
        files["doc://cut"] = good.copyOf(good.size - 600)
        val repo = repo()

        val error = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo.importWithReport("doc://cut", Recorder()) } }

        assertTrue(error.message, error.message!!.contains("cut short") || error.message!!.contains("damaged"))
        nothingLeft(repo)
    }

    @Test
    fun `an entry whose copied size differs from its declared size is refused`() {
        val patched = bundle()
        // Central directory records are "PK\u0001\u0002": make the media entry claim 10 more bytes than it holds (offset 24 is its size).
        var i = 0
        var changed = 0
        while (i < patched.size - 46) {
            if (patched[i] == 'P'.code.toByte() && patched[i + 1] == 'K'.code.toByte() && patched[i + 2] == 1.toByte() && patched[i + 3] == 2.toByte()) {
                val nameLen = (patched[i + 28].toInt() and 0xFF) or ((patched[i + 29].toInt() and 0xFF) shl 8)
                if (String(patched, i + 46, nameLen).startsWith("media/")) {
                    patched[i + 24] = (patched[i + 24] + 10).toByte()
                    changed++
                }
            }
            i++
        }
        assertTrue(changed > 0)
        files["doc://wrong"] = patched
        val repo = repo()

        val error = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo.importWithReport("doc://wrong", Recorder()) } }

        assertTrue(error.message, error.message!!.contains("cut short or damaged"))
        nothingLeft(repo)
    }

    @Test
    fun `a bundle without project json is refused before any media is copied`() {
        files["doc://nop"] = zipOf("bundle.json" to manifest, "media/a1-big.mp4" to big)
        val seen = Recorder()
        val repo = repo()

        assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo.importWithReport("doc://nop", seen) } }

        assertEquals("not a byte moved", 0L, seen.done)
        assertTrue(seen.media.isEmpty())
        nothingLeft(repo)
    }

    @Test
    fun `a project that does not parse is refused before any media is copied`() {
        files["doc://bad"] = zipOf("bundle.json" to manifest, "project.json" to "{ not json".toByteArray(), "media/a1-big.mp4" to big)
        val seen = Recorder()
        val repo = repo()

        assertThrows(ProjectError.Corrupt::class.java) { runBlocking { repo.importWithReport("doc://bad", seen) } }

        assertEquals(0L, seen.done)
        nothingLeft(repo)
    }

    @Test
    fun `a bundle that cannot fit is refused before it is unpacked, with the numbers`() {
        files["doc://b"] = bundle()
        val seen = Recorder()
        val repo = repo(freeBytes = { 1000 })

        val error = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo.importWithReport("doc://b", seen) } }

        assertTrue(error.message, error.message!!.contains("Not enough free space"))
        assertEquals(0L, seen.done)
        nothingLeft(repo)
    }

    @Test
    fun `sizes past 4 GB add up as longs and the limits still hold`() {
        fun media(name: String, size: Long) = ZipEntryInfo("media/$name", 0, size, size, 0)
        val gb = 1024L * 1024 * 1024
        val entries = listOf(
            ZipEntryInfo("bundle.json", 8, 100, 300, 0),
            ZipEntryInfo("project.json", 8, 100, 900, 0),
            media("a.mov", 5 * gb),
            media("b.mov", 6 * gb),
            ZipEntryInfo("media/", 0, 0, 0, 0),
        )

        val plan = ProjectBundle.plan(entries)

        assertEquals(11 * gb + 1200, plan.totalBytes)
        assertEquals(2, plan.mediaCount)
        assertThrows(BundleError.TooLarge::class.java) { ProjectBundle.plan(listOf(media("huge.mov", 70 * gb))) }
        assertThrows(BundleError.TooLarge::class.java) { ProjectBundle.plan(listOf(media("a", 60 * gb), media("b", 60 * gb), media("c", 60 * gb))) }
        assertThrows(BundleError.UnsafePath::class.java) { ProjectBundle.plan(listOf(media("../x", 1))) }
    }

    @Test
    fun `a name that is taken is reported as a rename`() {
        files["doc://b"] = bundle()
        val repo = repo()

        val first = runBlocking { repo.importWithReport("doc://b") }
        val second = runBlocking { repo.importWithReport("doc://b") }

        assertNull(first.renamedFrom)
        assertEquals("Sample & Co", second.renamedFrom)
        assertEquals("Sample & Co (2)", second.project.name)
        assertNotEquals(first.project.id, second.project.id)
    }

    @Test
    fun `a plain project file reports its rename too`() {
        files["doc://json"] = ProjectJson.encode(sampleProject()).toByteArray()
        val repo = repo()

        val first = runBlocking { repo.importWithReport("doc://json", Recorder()) }
        val second = runBlocking { repo.importWithReport("doc://json") }

        assertNull(first.renamedFrom)
        assertEquals(sampleProject().name, second.renamedFrom)
    }

    @Test
    fun `a file that is neither a project nor a bundle is never read into memory past the limit`() {
        files["doc://huge"] = ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() }
        val repo = repo(maxDocumentBytes = 2 * 1024 * 1024)

        val error = assertThrows(ProjectError.Bundle::class.java) { runBlocking { repo.importWithReport("doc://huge") } }

        assertTrue(error.message, error.message!!.contains("not a project"))
        nothingLeft(repo)
    }

    @Test
    fun `saving another project is not kept waiting while a bundle copies`() {
        files["doc://b"] = bundle()
        val repo = repo()
        val saved = java.util.concurrent.CountDownLatch(1)
        var started = false
        val watcher = object : BundleWriteObserver {
            override fun onBytes(count: Long) {
                if (started) return
                started = true
                // A save from an open editor, in the middle of the copy, must go through at once.
                Thread {
                    runBlocking { repo.save(sampleProject().copy(id = "other", name = "Other")) }
                    saved.countDown()
                }.start()
                assertTrue("the save waited for the import", saved.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }

        runBlocking { repo.importWithReport("doc://b", watcher) }

        assertTrue(started)
        assertTrue(File(root(), "other/project.json").isFile)
    }

    @Test
    fun `a scratch folder left by a killed import is removed once nothing has touched it for a while`() {
        val old = File(root(), ".import-old/media").apply { mkdirs() }
        File(old, "big.mov").writeBytes(ByteArray(100))
        val fresh = File(root(), ".import-fresh").apply { mkdirs() }
        File(fresh, "partial.mov").writeBytes(ByteArray(100))
        val project = File(root(), "keep").apply { mkdirs() }
        val longAgo = System.currentTimeMillis() - 3_600_000
        File(root(), ".import-old").walkTopDown().forEach { it.setLastModified(longAgo) }

        val removed = runBlocking { repo().removeStaleImports() }

        assertEquals(1, removed)
        assertFalse(File(root(), ".import-old").exists())
        assertTrue("an import that is writing right now is never touched", fresh.exists())
        assertTrue(project.exists())
    }

    @Test
    fun `nothing of the import runs on the calling thread`() {
        // assertOffCaller() is in every call of the fake source; a plain file, a stream and a seekable zip all pass through it.
        files["doc://json"] = ProjectJson.encode(sampleProject()).toByteArray()
        files["doc://b"] = bundle()
        val repo = repo()

        runBlocking { repo.importWithReport("doc://json") }
        runBlocking { repo.importWithReport("doc://b") }
        randomAccess = false
        runBlocking { repo.importWithReport("doc://b") }

        assertEquals(3, runBlocking { repo.list().projects.size })
        assertFalse(root().list().orEmpty().any { it.startsWith(".import") })
    }
}
