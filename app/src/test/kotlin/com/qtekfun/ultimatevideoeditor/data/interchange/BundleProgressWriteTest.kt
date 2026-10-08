package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.ProjectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The writer's progress contract: the total up front, bytes counted as they are copied, a prompt stop, and the size it reports. */
class BundleProgressWriteTest {
    private val project = sampleProject()
    private val projectText = ProjectJson.encode(project)

    private class MapMedia(private val files: Map<String, ByteArray>) : BundleMediaSource {
        override fun sizeOf(uri: String): Long? = files[uri]?.size?.toLong()

        override fun open(uri: String): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
    }

    private val media = MapMedia(
        mapOf(
            "content://media/1" to ByteArray(700_000) { (it % 251).toByte() },
            "file:///music/song.wav" to ByteArray(300_000) { 7 },
        ),
    )

    private class Recorder(private val stopAfterBytes: Long = Long.MAX_VALUE) : BundleWriteObserver {
        var total = -1L
        var mediaCount = -1
        var bytes = 0L
        val items = ArrayList<String>()

        override fun onStart(totalBytes: Long, mediaCount: Int) {
            total = totalBytes
            this.mediaCount = mediaCount
        }

        override fun onItem(kind: BundleItemKind, name: String, mediaIndex: Int) {
            items += "$kind:$mediaIndex:$name"
        }

        override fun onBytes(count: Long) {
            bytes += count
        }

        override fun isCancelled(): Boolean = bytes >= stopAfterBytes
    }

    private fun write(observer: BundleWriteObserver, out: OutputStream = ByteArrayOutputStream(), media: BundleMediaSource = this.media) =
        ProjectBundle.write(projectText, project, media, true, mapOf("project.jpg" to ByteArray(1234)), out, observer = observer)

    @Test
    fun `the total is known before writing and equals the bytes counted`() {
        val recorder = Recorder()
        val result = write(recorder)

        assertEquals(2, recorder.mediaCount)
        assertTrue(recorder.total > 1_000_000)
        assertEquals("every announced byte is counted, so the percent ends at 100", recorder.total, recorder.bytes)
        assertEquals(2, result.mediaCopied)
    }

    @Test
    fun `entries are announced in order with media numbered from one`() {
        val recorder = Recorder()
        write(recorder)

        assertEquals("PROJECT:0:project.json", recorder.items.first())
        val media = recorder.items.filter { it.startsWith("MEDIA") }
        assertEquals(listOf("MEDIA:1:interview.mp4", "MEDIA:2:song.wav"), media)
    }

    @Test
    fun `the reported size is the size of the file and each entry is listed with its size`() {
        val out = ByteArrayOutputStream()
        val result = write(Recorder(), out)

        assertEquals(out.size().toLong(), result.bytesWritten)
        val sizes = result.entries.associate { it.name to it.size }
        assertEquals(700_000L, sizes.entries.first { it.key.startsWith("media/") && it.key.endsWith("interview.mp4") }.value)
        assertEquals(1234L, sizes["thumbnails/project.jpg"])
        assertTrue(ProjectBundle.MANIFEST in sizes && ProjectBundle.PROJECT in sizes)
    }

    @Test
    fun `cancelling stops the copy within a chunk and reports nothing as written`() {
        val recorder = Recorder(stopAfterBytes = 200_000)
        assertThrows(BundleWriteCancelled::class.java) { write(recorder) }

        // A chunk is 256 KB: the writer notices at the next one, long before the second file starts.
        assertTrue("stopped at ${recorder.bytes}", recorder.bytes < 700_000)
        assertFalse(recorder.items.any { it.startsWith("MEDIA:2") })
    }

    @Test
    fun `a media file that cannot be opened is skipped and its bytes are still counted so the percent reaches 100`() {
        val half = MapMedia(mapOf("content://media/1" to ByteArray(700_000)))
        val sizes = object : BundleMediaSource {
            override fun sizeOf(uri: String): Long? = if (uri == "file:///music/song.wav") 300_000L else half.sizeOf(uri)

            override fun open(uri: String): InputStream? = half.open(uri) // song.wav has a size but will not open
        }
        val recorder = Recorder()
        val result = write(recorder, media = sizes)

        assertEquals(listOf("song.wav"), result.mediaSkipped.filter { it == "song.wav" })
        assertEquals(recorder.total, recorder.bytes)
    }

    @Test
    fun `a read failure names the file, a write failure is not called a read failure`() {
        val failingRead = object : BundleMediaSource {
            override fun sizeOf(uri: String): Long? = if (uri == "content://media/1") 1000L else null

            override fun open(uri: String): InputStream? = object : InputStream() {
                override fun read(): Int = throw IOException("EIO")

                override fun read(b: ByteArray): Int = throw IOException("EIO")
            }
        }
        val readError = assertThrows(IOException::class.java) { write(Recorder(), media = failingRead) }
        assertTrue(readError.message.orEmpty(), readError.message.orEmpty().startsWith("could not read interview.mp4"))

        var written = 0L
        val fullDisk = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > 300_000) throw IOException("No space left on device")
            }
        }
        val writeError = assertThrows(IOException::class.java) { write(Recorder(), out = fullDisk) }
        assertFalse(writeError.message.orEmpty(), writeError.message.orEmpty().startsWith("could not read"))
    }
}
