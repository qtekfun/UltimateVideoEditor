package com.qtekfun.ultimatevideoeditor.data.interchange

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Positional reads on something already open (a file descriptor), so nothing is ever re-opened by path. */
interface RandomAccess {
    val size: Long

    /** Reads up to [len] bytes at [position] into [buf]; -1 at the end. Never moves a shared cursor. */
    @Throws(IOException::class)
    fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** [RandomAccess] over a channel of an open descriptor (`pread`). */
class FileRandomAccess(private val channel: FileChannel) : RandomAccess {
    override val size: Long get() = channel.size()

    override fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(buf, off, len), position)
}

/** One entry of a zip's central directory. */
data class ZipEntryInfo(
    val name: String,
    /** 0 stored, 8 deflated. */
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val headerOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * A zip reader over [RandomAccess]: the end of central directory (with its zip64 record and locator), the central
 * directory and the entries' data are read by position, so a multi-gigabyte package opened from a descriptor needs no
 * path and no memory beyond the directory. Sizes come from the central directory, so entries written with data
 * descriptors work. Stored and deflated entries are supported.
 */
class ZipReader(private val source: RandomAccess, private val maxEntries: Int = 100_000) {
    val entries: List<ZipEntryInfo>

    init {
        entries = readDirectory()
    }

    fun find(name: String): ZipEntryInfo? = entries.firstOrNull { it.name == name }

    /** A stream over the uncompressed content of [entry]. */
    @Throws(IOException::class, BundleError::class)
    fun open(entry: ZipEntryInfo): InputStream {
        val header = readFully(entry.headerOffset, LOCAL_HEADER)
        if (le32(header, 0) != LOCAL_SIG) throw BundleError.Corrupt("the entry ${entry.name.takeLast(60)} has no local header")
        val start = entry.headerOffset + LOCAL_HEADER + le16(header, 26) + le16(header, 28)
        if (start + entry.compressedSize > source.size) throw BundleError.Corrupt("the entry ${entry.name.takeLast(60)} runs past the end of the file")
        return when (entry.method) {
            METHOD_STORED -> Bounded(source, start, entry.compressedSize, pad = false)
            METHOD_DEFLATED -> {
                val inflater = Inflater(true)
                // A big buffer: the default 512 bytes would be one positional read per 512 bytes of a multi-gigabyte entry.
                object : FilterInputStream(InflaterInputStream(Bounded(source, start, entry.compressedSize, pad = true), inflater, INFLATE_BUFFER)) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> throw BundleError.Corrupt("the entry ${entry.name.takeLast(60)} uses compression method ${entry.method}")
        }
    }

    private fun readDirectory(): List<ZipEntryInfo> {
        val total = source.size
        if (total < EOCD_MIN) throw BundleError.Corrupt("the file is too small to be a zip")
        val tailLen = minOf(total, EOCD_MIN + MAX_COMMENT.toLong()).toInt()
        val tailStart = total - tailLen
        val tail = readFully(tailStart, tailLen)
        var eocd = -1
        for (i in tailLen - EOCD_MIN downTo 0) {
            if (le32(tail, i) == EOCD_SIG && i + EOCD_MIN + le16(tail, i + 20) <= tailLen) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw BundleError.Corrupt("no end of central directory: the file is cut short or is not a zip")
        var count = le16(tail, eocd + 10).toLong()
        var dirSize = le32(tail, eocd + 12)
        var dirOffset = le32(tail, eocd + 16)
        if (count == 0xFFFFL || dirSize == MASK32 || dirOffset == MASK32) {
            val locatorAt = tailStart + eocd - LOCATOR_LEN
            if (locatorAt < 0) throw BundleError.Corrupt("zip64 end of central directory is missing")
            val locator = readFully(locatorAt, LOCATOR_LEN)
            if (le32(locator, 0) != LOCATOR_SIG) throw BundleError.Corrupt("zip64 locator is missing")
            val z64 = readFully(le64(locator, 8), ZIP64_EOCD_MIN)
            if (le32(z64, 0) != ZIP64_EOCD_SIG) throw BundleError.Corrupt("zip64 end of central directory is damaged")
            count = le64(z64, 32)
            dirSize = le64(z64, 40)
            dirOffset = le64(z64, 48)
        }
        if (count > maxEntries) throw BundleError.TooLarge("more than $maxEntries files")
        if (dirSize > MAX_DIRECTORY || dirOffset < 0 || dirOffset + dirSize > total) throw BundleError.Corrupt("the central directory is outside the file")
        val dir = readFully(dirOffset, dirSize.toInt())
        val out = ArrayList<ZipEntryInfo>()
        var p = 0
        while (p + CENTRAL_FIXED <= dir.size && le32(dir, p) == CENTRAL_SIG) {
            val method = le16(dir, p + 10)
            var compressed = le32(dir, p + 20)
            var size = le32(dir, p + 24)
            val nameLen = le16(dir, p + 28)
            val extraLen = le16(dir, p + 30)
            val commentLen = le16(dir, p + 32)
            var offset = le32(dir, p + 42)
            val end = p + CENTRAL_FIXED + nameLen + extraLen + commentLen
            if (end > dir.size) throw BundleError.Corrupt("the central directory is cut short")
            val name = String(dir, p + CENTRAL_FIXED, nameLen, Charsets.UTF_8)
            var x = p + CENTRAL_FIXED + nameLen
            val extraEnd = x + extraLen
            while (x + 4 <= extraEnd) {
                val id = le16(dir, x)
                val len = le16(dir, x + 2)
                if (id == ZIP64_EXTRA && x + 4 + len <= extraEnd) {
                    var f = x + 4
                    if (size == MASK32 && f + 8 <= x + 4 + len) { size = le64(dir, f); f += 8 }
                    if (compressed == MASK32 && f + 8 <= x + 4 + len) { compressed = le64(dir, f); f += 8 }
                    if (offset == MASK32 && f + 8 <= x + 4 + len) { offset = le64(dir, f) }
                }
                x += 4 + len
            }
            out += ZipEntryInfo(name, method, compressed, size, offset)
            p = end
        }
        if (out.size.toLong() != count && count != 0L) throw BundleError.Corrupt("the central directory lists ${out.size} of $count files")
        return out
    }

    private fun readFully(position: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = source.read(position + done, buf, done, len - done)
            if (n < 0) throw BundleError.Corrupt("the file ends before byte ${position + len}")
            done += n
        }
        return buf
    }

    /** [length] bytes of [source] from [start]; with [pad] one extra zero byte follows, which a raw inflater may need to finish. */
    private class Bounded(private val source: RandomAccess, start: Long, private val length: Long, private val pad: Boolean) : InputStream() {
        private var position = start
        private var left = length
        private var padded = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) {
                if (pad && !padded) {
                    padded = true
                    b[off] = 0
                    return 1
                }
                return -1
            }
            val n = source.read(position, b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("the file ends inside an entry")
            position += n
            left -= n
            return n
        }
    }

    private companion object {
        const val EOCD_SIG = 0x06054b50L
        const val EOCD_MIN = 22
        const val MAX_COMMENT = 65_535
        const val LOCATOR_SIG = 0x07064b50L
        const val LOCATOR_LEN = 20
        const val ZIP64_EOCD_SIG = 0x06064b50L
        const val ZIP64_EOCD_MIN = 56
        const val CENTRAL_SIG = 0x02014b50L
        const val CENTRAL_FIXED = 46
        const val LOCAL_SIG = 0x04034b50L
        const val LOCAL_HEADER = 30
        const val ZIP64_EXTRA = 0x0001
        const val MASK32 = 0xFFFFFFFFL
        const val MAX_DIRECTORY = 256L * 1024 * 1024
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val INFLATE_BUFFER = 64 * 1024

        fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        fun le32(b: ByteArray, i: Int) = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)

        fun le64(b: ByteArray, i: Int) = le32(b, i) or (le32(b, i + 4) shl 32)
    }
}
