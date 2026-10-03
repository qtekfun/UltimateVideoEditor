package com.ultimatevideo.uveditor.engine.captions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class CaptionModelStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val model = CaptionModel(
        id = "test",
        label = "Test",
        fileName = "ggml-test.bin",
        url = "https://example.invalid/ggml-test.bin",
        sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        sizeBytes = payload.size.toLong(),
        description = "",
    )

    private fun store(freeSpace: Long = Long.MAX_VALUE, source: ModelSource) =
        CaptionModelStore(folder.newFolder("models"), source, Dispatchers.Unconfined) { freeSpace }

    private fun good() = ModelSource { ModelDownload(ByteArrayInputStream(payload), payload.size.toLong()) }

    private fun failureOf(block: () -> Unit): CaptionException {
        try {
            block()
        } catch (e: CaptionException) {
            return e
        }
        fail("expected a CaptionException")
        throw IllegalStateException()
    }

    @Test
    fun `a good download installs the verified file and reports rising progress`() = runBlocking {
        val store = store(source = good())

        val progress = store.download(model).toList()

        assertTrue(store.isInstalled(model))
        assertEquals(model.sizeBytes, store.fileOf(model)?.length())
        assertEquals(100, progress.last().percent)
        assertEquals(progress.map { it.percent }, progress.map { it.percent }.sorted())
        assertTrue(progress.size > 2)
        assertFalse("no partial file is left", store.fileOf(model)!!.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `an installed model is not downloaded again`() = runBlocking {
        var opened = 0
        val store = store { opened++; ModelDownload(ByteArrayInputStream(payload), null) }
        store.download(model).toList()
        store.download(model).toList()

        assertEquals(1, opened)
    }

    @Test
    fun `a checksum mismatch is refused and leaves nothing behind`() = runBlocking {
        val corrupted = payload.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val store = store { ModelDownload(ByteArrayInputStream(corrupted), corrupted.size.toLong()) }

        val error = failureOf { runBlocking { store.download(model).toList() } }

        assertEquals(CaptionErrorCode.ModelCorrupt, error.errorCode)
        assertFalse(store.isInstalled(model))
        assertEquals(0, folderFiles(store).size)
    }

    @Test
    fun `a truncated download is refused`() = runBlocking {
        val store = store { ModelDownload(ByteArrayInputStream(payload.copyOf(1234)), null) }

        val error = failureOf { runBlocking { store.download(model).toList() } }

        assertEquals(CaptionErrorCode.ModelCorrupt, error.errorCode)
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun `an interrupted connection is reported as a download error and cleaned up`() = runBlocking {
        val broken = object : InputStream() {
            var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 100_000) throw IOException("connection reset")
                val n = minOf(len, 50_000)
                served += n
                return n
            }
        }
        val store = store { ModelDownload(broken, payload.size.toLong()) }

        val error = failureOf { runBlocking { store.download(model).toList() } }

        assertEquals(CaptionErrorCode.Download, error.errorCode)
        assertEquals(0, folderFiles(store).size)
    }

    @Test
    fun `an unreachable server is a download error`() = runBlocking {
        val store = store { throw IOException("no route to host") }

        val error = failureOf { runBlocking { store.download(model).toList() } }

        assertEquals(CaptionErrorCode.Download, error.errorCode)
    }

    @Test
    fun `not enough free space fails before any transfer`() = runBlocking {
        var opened = false
        val store = store(freeSpace = 1_000) { opened = true; ModelDownload(ByteArrayInputStream(payload), null) }

        val error = failureOf { runBlocking { store.download(model).toList() } }

        assertEquals(CaptionErrorCode.NoSpace, error.errorCode)
        assertFalse(opened)
    }

    @Test
    fun `stopping collection removes the partial file`() = runBlocking {
        val store = store(source = good())

        store.download(model).first() // takes one progress item, then cancels the flow

        assertFalse(store.isInstalled(model))
        assertEquals(0, folderFiles(store).size)
    }

    @Test
    fun `a model whose file changed size is no longer installed, and delete removes it`() = runBlocking {
        val store = store(source = good())
        store.download(model).toList()
        val file = store.fileOf(model)
        assertNotNull(file)

        file!!.appendBytes(byteArrayOf(1))
        assertFalse(store.isInstalled(model))

        store.delete(model)
        assertFalse(file.exists())
    }

    @Test
    fun `the published models carry well-formed checksums`() {
        for (m in CaptionModels.ALL) {
            assertEquals(64, m.sha256.length)
            assertTrue(m.sha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(m.url.startsWith("https://"))
            assertTrue(m.sizeBytes > 1_000_000)
        }
        assertEquals(CaptionModels.ALL.size, CaptionModels.ALL.map { it.id }.toSet().size)
        assertEquals(CaptionModels.DEFAULT, CaptionModels.byId("unknown"))
    }

    private fun folderFiles(store: CaptionModelStore): List<java.io.File> =
        (store.fileOf(model)?.parentFile ?: java.io.File(folder.root, "models")).listFiles()?.toList().orEmpty()
}
