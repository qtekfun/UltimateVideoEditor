package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.ProjectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The check of a saved backup: a good zip passes, a truncated or damaged one is a warning, never a silent success. */
class BundleCheckerTest {
    private val project = sampleProject()
    private val projectText = ProjectJson.encode(project)

    private class Bytes(val data: ByteArray) : RandomAccess {
        override val size: Long get() = data.size.toLong()

        override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(len.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buf, off, n)
            return n
        }
    }

    private val media = object : BundleMediaSource {
        private val files = mapOf("content://media/1" to ByteArray(90_000) { (it % 13).toByte() }, "file:///music/song.wav" to ByteArray(20_000) { 3 })

        override fun sizeOf(uri: String): Long? = files[uri]?.size?.toLong()

        override fun open(uri: String): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
    }

    private fun written(): Pair<ByteArray, BundleWriteResult> {
        val out = ByteArrayOutputStream()
        val result = ProjectBundle.write(projectText, project, media, true, mapOf("project.jpg" to ByteArray(500)), out)
        return out.toByteArray() to result
    }

    private fun problems(v: BundleVerification) = (v as BundleVerification.Warning).problems

    @Test
    fun `a complete bundle is verified with its entry count`() {
        val (bytes, result) = written()

        val verdict = BundleChecker.check(Bytes(bytes), result)

        assertEquals(BundleVerification.Verified(result.entries.size, bytes.size.toLong()), verdict)
    }

    @Test
    fun `a bundle cut short loses its table of contents and is a warning`() {
        val (bytes, result) = written()

        val verdict = BundleChecker.check(Bytes(bytes.copyOf(bytes.size - 200)), result)

        val found = problems(verdict)
        assertTrue(found.toString(), found.any { it.contains("is ${bytes.size - 200} bytes but ${bytes.size} were written") })
        assertTrue(found.toString(), found.any { it.contains("table of contents") })
    }

    @Test
    fun `a file half the size is a warning`() {
        val (bytes, result) = written()

        assertTrue(BundleChecker.check(Bytes(bytes.copyOf(bytes.size / 2)), result) is BundleVerification.Warning)
    }

    @Test
    fun `a zero byte file is a warning not a crash`() {
        val (_, result) = written()

        assertTrue(BundleChecker.check(Bytes(ByteArray(0)), result) is BundleVerification.Warning)
    }

    @Test
    fun `an entry written shorter than promised is named`() {
        val (bytes, result) = written()
        val promised = result.copy(entries = result.entries.map { if (it.name.endsWith("interview.mp4")) it.copy(size = it.size + 10) else it })

        val found = problems(BundleChecker.check(Bytes(bytes), promised))

        assertTrue(found.toString(), found.any { it.contains("interview.mp4") && it.contains("bytes") })
    }

    @Test
    fun `a missing project json is a warning`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ProjectBundle.MANIFEST))
            zip.write("""{"format":"uveditor-bundle","formatVersion":1,"projectName":"x","media":[]}""".toByteArray())
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        val result = BundleWriteResult(0, emptyList(), bytesWritten = bytes.size.toLong(), entries = listOf(WrittenEntry(ProjectBundle.MANIFEST, 80)))

        val found = problems(BundleChecker.check(Bytes(bytes), result))

        assertTrue(found.toString(), found.contains("${ProjectBundle.PROJECT} is missing"))
    }

    @Test
    fun `a manifest that names media which are not inside is a warning`() {
        val out = ByteArrayOutputStream()
        val manifest = """{"format":"uveditor-bundle","formatVersion":1,"projectName":"x","media":[{"assetId":"a","name":"clip.mov","sizeBytes":5,"entry":"media/a-clip.mov"}]}"""
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ProjectBundle.MANIFEST))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ProjectBundle.PROJECT))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        val result = BundleWriteResult(0, emptyList(), bytesWritten = bytes.size.toLong(), entries = listOf(WrittenEntry(ProjectBundle.MANIFEST, manifest.length.toLong()), WrittenEntry(ProjectBundle.PROJECT, 2)))

        val found = problems(BundleChecker.check(Bytes(bytes), result))

        assertTrue(found.toString(), found.any { it.contains("clip.mov") })
    }

    @Test
    fun `an unreadable manifest is a warning`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ProjectBundle.MANIFEST))
            zip.write("not json".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ProjectBundle.PROJECT))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        val result = BundleWriteResult(0, emptyList(), bytesWritten = bytes.size.toLong(), entries = listOf(WrittenEntry(ProjectBundle.MANIFEST, 8), WrittenEntry(ProjectBundle.PROJECT, 2)))

        assertTrue(BundleChecker.check(Bytes(bytes), result) is BundleVerification.Warning)
    }
}
