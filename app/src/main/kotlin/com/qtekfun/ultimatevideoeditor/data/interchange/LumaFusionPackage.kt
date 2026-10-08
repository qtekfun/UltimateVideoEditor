package com.qtekfun.ultimatevideoeditor.data.interchange

import java.io.IOException
import java.io.OutputStream

/**
 * An `.lfpackage` is a zip that holds the `.lfarchive` and the footage, stored without compression. It is read through its
 * central directory by position ([ZipReader], zip64 included) on the descriptor the system picker handed over, so a
 * multi-gigabyte file is never loaded in memory, never re-opened by path, and only the entries wanted are copied, one at a
 * time, with progress and a cancel check.
 */
object LumaFusionPackage {
    /** The archive entry of an open package: the first file named `*.lfarchive` (any folder), or null. */
    fun archiveEntry(zip: ZipReader): ZipEntryInfo? =
        zip.entries.firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(LumaFusionImport.ARCHIVE_EXTENSION) }

    /** Reads the archive text; bounded by [limit] bytes. */
    @Throws(IOException::class, BundleError::class)
    fun readArchive(zip: ZipReader, entry: ZipEntryInfo, limit: Long = BundleLimits().maxJsonBytes): String {
        if (entry.size > limit) throw BundleError.TooLarge("the project file is bigger than ${limit / (1024 * 1024)} MB")
        val bytes = zip.open(entry).use { it.readBytes() }
        if (bytes.size.toLong() != entry.size) throw BundleError.Corrupt("the project file is ${bytes.size} bytes, expected ${entry.size}")
        return bytes.toString(Charsets.UTF_8)
    }

    /** The footage entries by lower-case file name (the part after the last `/`); the first of equal names wins. */
    fun mediaEntries(zip: ZipReader, archive: ZipEntryInfo): Map<String, ZipEntryInfo> {
        val out = LinkedHashMap<String, ZipEntryInfo>()
        for (e in zip.entries) {
            if (e.isDirectory || e.name == archive.name) continue
            val leaf = e.name.substringAfterLast('/')
            if (leaf.isEmpty() || leaf.startsWith(".")) continue
            out.putIfAbsent(leaf.lowercase(), e)
        }
        return out
    }

    /**
     * Copies [entry] into [out] in 256 KB steps. [onBytes] gets the bytes copied by each step, [cancelled] is asked between
     * steps and ends the copy with an [ImportCancelled]. A copy that ends with another size than the entry declares is
     * [BundleError.Corrupt]. The caller owns [out] and removes what was written when this throws.
     */
    @Throws(IOException::class, BundleError::class)
    fun copyEntry(zip: ZipReader, entry: ZipEntryInfo, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        zip.open(entry).use { input ->
            val buffer = ByteArray(STEP)
            var total = 0L
            while (true) {
                if (cancelled()) throw ImportCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
                onBytes(n.toLong())
            }
            if (total != entry.size) throw BundleError.Corrupt("${entry.name.takeLast(60)} ended after $total of ${entry.size} bytes")
        }
    }

    private const val STEP = 256 * 1024
}
