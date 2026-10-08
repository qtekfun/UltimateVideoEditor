package com.qtekfun.ultimatevideoeditor.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipReaderTest {
    private class Bytes(val data: ByteArray) : RandomAccess {
        override val size: Long get() = data.size.toLong()

        override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(len.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buf, off, n)
            return n
        }
    }

    private fun zip(comment: String? = null, build: ZipOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (comment != null) z.setComment(comment)
            z.build()
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.stored(name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = data.size.toLong(); this.crc = crc.value })
        write(data)
        closeEntry()
    }

    private fun ZipOutputStream.deflated(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

    private fun ZipReader.text(name: String) = open(checkNotNull(find(name))).use { it.readBytes() }

    private val payload = ByteArray(5000) { (it * 7 % 251).toByte() }

    @Test
    fun `stored and deflated entries read back exactly`() {
        val reader = ZipReader(Bytes(zip { stored("a.bin", payload); deflated("b.txt", "hello hello hello hello".toByteArray()) }))
        assertEquals(listOf("a.bin", "b.txt"), reader.entries.map { it.name })
        assertEquals(0, reader.find("a.bin")!!.method)
        assertEquals(8, reader.find("b.txt")!!.method)
        assertTrue(payload.contentEquals(reader.text("a.bin")))
        assertEquals("hello hello hello hello", String(reader.text("b.txt")))
        assertEquals(5000L, reader.find("a.bin")!!.size)
    }

    @Test
    fun `deflated entries with data descriptors use the sizes of the central directory`() {
        val big = ByteArray(300_000) { (it % 13).toByte() }
        val reader = ZipReader(Bytes(zip { deflated("big.bin", big) }))
        assertTrue(big.contentEquals(reader.text("big.bin")))
    }

    @Test
    fun `names with spaces and unicode survive and folders are marked`() {
        val reader = ZipReader(Bytes(zip { putNextEntry(ZipEntry("Fotos/")); closeEntry(); stored("Fotos/Año nuevo é 日本.JPG", payload) }))
        assertTrue(reader.entries[0].isDirectory)
        assertFalse(reader.entries[1].isDirectory)
        assertNotNull(reader.find("Fotos/Año nuevo é 日本.JPG"))
    }

    @Test
    fun `a comment after the end record is skipped`() {
        val reader = ZipReader(Bytes(zip(comment = "x".repeat(3000)) { stored("a.bin", payload) }))
        assertEquals(listOf("a.bin"), reader.entries.map { it.name })
    }

    @Test
    fun `an empty zip has no entries`() {
        assertEquals(0, ZipReader(Bytes(zip { })).entries.size)
    }

    @Test
    fun `a truncated or foreign file is a typed error`() {
        val whole = zip { stored("a.bin", payload) }
        assertThrows(BundleError.Corrupt::class.java) { ZipReader(Bytes(whole.copyOf(whole.size - 30))) }
        assertThrows(BundleError.Corrupt::class.java) { ZipReader(Bytes(ByteArray(10))) }
        assertThrows(BundleError.Corrupt::class.java) { ZipReader(Bytes(ByteArray(5000) { 1 })) }
    }

    @Test
    fun `an entry that claims to run past the end of the file is refused`() {
        val whole = zip { stored("a.bin", payload) }
        // Cut the data but keep the directory: copy the directory over the middle of the data.
        val reader = ZipReader(Bytes(whole))
        val entry = reader.find("a.bin")!!.copy(compressedSize = 10_000_000)
        assertThrows(BundleError.Corrupt::class.java) { reader.open(entry) }
    }

    @Test
    fun `an unknown compression method is refused`() {
        val reader = ZipReader(Bytes(zip { stored("a.bin", payload) }))
        assertThrows(BundleError.Corrupt::class.java) { reader.open(reader.find("a.bin")!!.copy(method = 12)) }
    }

    @Test
    fun `zip64 sizes offsets and counts are read from the extra field and the zip64 record`() {
        // One stored entry; the central directory and the end record carry 0xFFFFFFFF/0xFFFF markers and the real
        // values live in the zip64 extra field, the zip64 end record and its locator.
        val name = "Big file.MOV".toByteArray()
        val data = ByteArray(1234) { (it % 100).toByte() }
        val out = ByteArrayOutputStream()
        fun le(n: Long, bytes: Int): ByteArray = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            when (bytes) { 2 -> b.putShort(n.toShort()); 4 -> b.putInt(n.toInt()); else -> b.putLong(n) }
        }.array()
        // local header
        out.write(le(0x04034b50, 4)); out.write(le(45, 2)); out.write(le(0, 2)); out.write(le(0, 2)); out.write(le(0, 4)); out.write(le(0, 4))
        out.write(le(0xFFFFFFFFL, 4)); out.write(le(0xFFFFFFFFL, 4)); out.write(le(name.size.toLong(), 2)); out.write(le(20, 2)); out.write(name)
        out.write(le(1, 2)); out.write(le(16, 2)); out.write(le(data.size.toLong(), 8)); out.write(le(data.size.toLong(), 8))
        out.write(data)
        val dirOffset = out.size().toLong()
        // central directory entry with zip64 extra (size, compressed size, offset)
        out.write(le(0x02014b50, 4)); out.write(le(45, 2)); out.write(le(45, 2)); out.write(le(0, 2)); out.write(le(0, 2)); out.write(le(0, 4)); out.write(le(0, 4))
        out.write(le(0xFFFFFFFFL, 4)); out.write(le(0xFFFFFFFFL, 4)); out.write(le(name.size.toLong(), 2)); out.write(le(28, 2)); out.write(le(0, 2))
        out.write(le(0, 2)); out.write(le(0, 2)); out.write(le(0, 4)); out.write(le(0xFFFFFFFFL, 4)); out.write(name)
        out.write(le(1, 2)); out.write(le(24, 2)); out.write(le(data.size.toLong(), 8)); out.write(le(data.size.toLong(), 8)); out.write(le(0, 8))
        val dirSize = out.size() - dirOffset
        val z64At = out.size().toLong()
        out.write(le(0x06064b50, 4)); out.write(le(44, 8)); out.write(le(45, 2)); out.write(le(45, 2)); out.write(le(0, 4)); out.write(le(0, 4))
        out.write(le(1, 8)); out.write(le(1, 8)); out.write(le(dirSize, 8)); out.write(le(dirOffset, 8))
        out.write(le(0x07064b50, 4)); out.write(le(0, 4)); out.write(le(z64At, 8)); out.write(le(1, 4))
        out.write(le(0x06054b50, 4)); out.write(le(0, 2)); out.write(le(0, 2)); out.write(le(0xFFFF, 2)); out.write(le(0xFFFF, 2))
        out.write(le(0xFFFFFFFFL, 4)); out.write(le(0xFFFFFFFFL, 4)); out.write(le(0, 2))

        val reader = ZipReader(Bytes(out.toByteArray()))
        val entry = reader.entries.single()
        assertEquals("Big file.MOV", entry.name)
        assertEquals(1234L, entry.size)
        assertEquals(1234L, entry.compressedSize)
        assertEquals(0L, entry.headerOffset)
        assertTrue(data.contentEquals(reader.open(entry).use { it.readBytes() }))
    }

    @Test
    fun `copyEntry streams an entry and checks its size`() {
        val reader = ZipReader(Bytes(zip { stored("a.bin", payload) }))
        val sink = ByteArrayOutputStream()
        var counted = 0L
        LumaFusionPackage.copyEntry(reader, reader.find("a.bin")!!, sink, { counted += it }, { false })
        assertTrue(payload.contentEquals(sink.toByteArray()))
        assertEquals(payload.size.toLong(), counted)
        val liar = reader.find("a.bin")!!.copy(size = 4999)
        assertThrows(BundleError.Corrupt::class.java) { LumaFusionPackage.copyEntry(reader, liar, ByteArrayOutputStream(), { }, { false }) }
    }
}
