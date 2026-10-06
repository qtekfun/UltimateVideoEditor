package com.ultimatevideo.uveditor.data.interchange


import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Progress of an import that copies big files: [doneBytes] of [totalBytes] of the footage, and [what] is being copied. */
data class ImportProgress(val what: String, val doneBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * An `.lfpackage` is a zip that holds the `.lfarchive` and the footage, stored without compression. It is read
 * through its central directory (random access, zip64 included) so that a multi-gigabyte file is never loaded in
 * memory and only the entries wanted are copied, one at a time, with progress and a cancel check.
 */
object LumaFusionPackage {
    /** The archive entry of an open package: the first file named `*.lfarchive` (any folder), or null. */
    fun archiveEntry(zip: ZipFile): ZipEntry? =
        zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(LumaFusionImport.ARCHIVE_EXTENSION) }

    /** Reads the archive text; bounded by [limit] bytes. */
    @Throws(IOException::class, BundleError::class)
    fun readArchive(zip: ZipFile, entry: ZipEntry, limit: Long = BundleLimits().maxJsonBytes): String {
        if (entry.size > limit) throw BundleError.TooLarge("the project file is bigger than ${limit / (1024 * 1024)} MB")
        return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    /** The footage entries by lower-case file name (the part after the last `/`); the first of equal names wins. */
    fun mediaEntries(zip: ZipFile, archive: ZipEntry): Map<String, ZipEntry> {
        val out = LinkedHashMap<String, ZipEntry>()
        for (e in zip.entries()) {
            if (e.isDirectory || e.name == archive.name) continue
            val leaf = e.name.substringAfterLast('/')
            if (leaf.isEmpty() || leaf.startsWith(".")) continue
            out.putIfAbsent(leaf.lowercase(), e)
        }
        return out
    }

    /**
     * Copies [entry] into [out] in 1 MB steps. [onBytes] gets the bytes copied by each step, [cancelled] is asked between
     * steps and ends the copy with a [CancellationException]. A copy that ends with another size than the entry declares is
     * [BundleError.Corrupt]. The caller owns [out] and removes what was written when this throws.
     */
    @Throws(IOException::class, BundleError::class)
    fun copyEntry(zip: ZipFile, entry: ZipEntry, out: OutputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        try {
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(STEP)
                var total = 0L
                while (true) {
                    if (cancelled()) throw CancellationException("import cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onBytes(n.toLong())
                }
                if (entry.size >= 0 && total != entry.size) throw BundleError.Corrupt("${entry.name.takeLast(60)} ended after $total of ${entry.size} bytes")
            }
        } catch (e: ZipException) {
            throw BundleError.Corrupt(e.message ?: "unreadable package", e)
        }
    }

    private const val STEP = 1024 * 1024
}
