package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.ProjectJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val project = sampleProject()
    private val projectText = ProjectJson.encode(project)

    /** Media by URI; sizes are the byte counts. */
    private class MapMedia(private val files: Map<String, ByteArray>) : BundleMediaSource {
        override fun sizeOf(uri: String): Long? = files[uri]?.size?.toLong()

        override fun open(uri: String): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
    }

    private val media = MapMedia(
        mapOf(
            "content://media/1" to ByteArray(5000) { (it % 251).toByte() },
            "file:///music/song.wav" to ByteArray(300) { 7 },
        ),
    )

    private fun write(includeMedia: Boolean, thumbs: Map<String, ByteArray> = emptyMap()): Pair<ByteArray, BundleWriteResult> {
        val out = ByteArrayOutputStream()
        val result = ProjectBundle.write(projectText, project, media, includeMedia, thumbs, out)
        return out.toByteArray() to result
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

    private fun extract(bytes: ByteArray, limits: BundleLimits = BundleLimits()) =
        ProjectBundle.extract(ByteArrayInputStream(bytes), File(tmp.root, "out"), limits)

    private val validManifest = """{"format":"uveditor-bundle","formatVersion":1,"projectName":"x","media":[]}""".toByteArray()

    @Test
    fun `a bundle with media round trips`() {
        val (bytes, result) = write(includeMedia = true, thumbs = mapOf("project.jpg" to byteArrayOf(1, 2, 3)))
        assertEquals(2, result.mediaCopied)
        assertTrue(result.mediaSkipped.toString(), result.mediaSkipped.containsAll(listOf("photo.jpg", "unused.mp4")))

        val extracted = extract(bytes)
        assertEquals(projectText, extracted.projectJson)
        assertEquals("Sample & Co", extracted.manifest.projectName)
        assertEquals(listOf("a1", "a2"), extracted.mediaFiles.keys.sorted())
        assertArrayEquals(media.open("content://media/1")!!.readBytes(), extracted.mediaFiles.getValue("a1").readBytes())
        assertEquals(1, extracted.thumbnailFiles.size)
        assertEquals(5000L, extracted.manifest.media.first { it.assetId == "a1" }.sizeBytes)
    }

    @Test
    fun `a bundle without media still lists names and sizes for relinking`() {
        val (bytes, result) = write(includeMedia = false)
        assertEquals(0, result.mediaCopied)
        val extracted = extract(bytes)
        assertTrue(extracted.mediaFiles.isEmpty())
        val a1 = extracted.manifest.media.first { it.assetId == "a1" }
        assertEquals("interview.mp4", a1.name)
        assertEquals(5000L, a1.sizeBytes)
        assertNull(a1.entry)
    }

    @Test
    fun `the same input produces the same bytes`() {
        assertArrayEquals(write(true).first, write(true).first)
    }

    @Test
    fun `sniffing tells a bundle from a project file without consuming it`() {
        val bundle = BufferedInputStream(ByteArrayInputStream(write(false).first))
        assertTrue(ProjectBundle.sniff(bundle))
        assertEquals('P'.code, bundle.read())
        val json = BufferedInputStream(ByteArrayInputStream(projectText.toByteArray()))
        assertFalse(ProjectBundle.sniff(json))
        assertEquals('{'.code, json.read())
        assertFalse(ProjectBundle.sniff(BufferedInputStream(ByteArrayInputStream(byteArrayOf()))))
    }

    @Test
    fun `entry names that could escape the target directory are refused and nothing is written outside it`() {
        val unsafe = listOf(
            "../evil.txt",
            "media/../../evil.txt",
            "media/../evil.txt",
            "/etc/passwd",
            "media/a/b.mp4",
            "thumbnails/x/y.jpg",
            "media\\evil.mp4",
            "C:/evil.mp4",
            "./evil",
            "media//x",
        )
        for (name in unsafe) {
            val bytes = zipOf("bundle.json" to validManifest, name to byteArrayOf(1), "project.json" to "{}".toByteArray())
            val error = assertThrows(name, BundleError::class.java) { extract(bytes) }
            assertTrue("$name -> ${error.message}", error is BundleError.UnsafePath)
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test
    fun `a zip that is not a bundle is refused`() {
        assertTrue(assertThrows(BundleError::class.java) { extract(zipOf("hello.txt" to byteArrayOf(1))) } is BundleError.NotABundle)
        val wrong = """{"format":"other","formatVersion":1}""".toByteArray()
        assertTrue(assertThrows(BundleError::class.java) { extract(zipOf("bundle.json" to wrong, "project.json" to "{}".toByteArray())) } is BundleError.NotABundle)
    }

    @Test
    fun `a bundle without a project file is damaged`() {
        val error = assertThrows(BundleError::class.java) { extract(zipOf("bundle.json" to validManifest)) }
        assertTrue(error is BundleError.Corrupt)
    }

    @Test
    fun `a bundle from a newer format is refused with the versions`() {
        val newer = """{"format":"uveditor-bundle","formatVersion":9}""".toByteArray()
        val error = assertThrows(BundleError::class.java) { extract(zipOf("bundle.json" to newer, "project.json" to "{}".toByteArray())) }
        assertTrue(error is BundleError.UnsupportedVersion)
    }

    @Test
    fun `garbage and truncated archives are reported, not thrown raw`() {
        val good = write(true).first
        val truncated = good.copyOf(good.size / 2)
        assertThrows(Exception::class.java) { extract(truncated) }
        assertThrows(BundleError::class.java) { extract(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @Test
    fun `size and count limits stop a hostile bundle`() {
        val many = zipOf("bundle.json" to validManifest, "project.json" to "{}".toByteArray(), "media/a" to byteArrayOf(1), "media/b" to byteArrayOf(1))
        assertTrue(assertThrows(BundleError::class.java) { extract(many, BundleLimits(maxEntries = 3)) } is BundleError.TooLarge)

        val bigJson = zipOf("bundle.json" to validManifest, "project.json" to ByteArray(2000) { ' '.code.toByte() })
        assertTrue(assertThrows(BundleError::class.java) { extract(bigJson, BundleLimits(maxJsonBytes = 1000)) } is BundleError.TooLarge)

        val bigFile = zipOf("bundle.json" to validManifest, "project.json" to "{}".toByteArray(), "media/a" to ByteArray(5000))
        assertTrue(assertThrows(BundleError::class.java) { extract(bigFile, BundleLimits(maxFileBytes = 1000)) } is BundleError.TooLarge)

        val two = zipOf("bundle.json" to validManifest, "project.json" to "{}".toByteArray(), "media/a" to ByteArray(800), "media/b" to ByteArray(800))
        assertTrue(assertThrows(BundleError::class.java) { extract(two, BundleLimits(maxTotalBytes = 1000)) } is BundleError.TooLarge)
    }

    @Test
    fun `media entry names are safe leaves`() {
        assertEquals("media/a1-my_clip__final_.mp4", ProjectBundle.mediaEntryName("a1", "my clip (final).mp4"))
        assertFalse(ProjectBundle.mediaEntryName("a1", "../../x").contains(".."))
        assertEquals("media/a1-", ProjectBundle.mediaEntryName("a1", ""))
    }
}
