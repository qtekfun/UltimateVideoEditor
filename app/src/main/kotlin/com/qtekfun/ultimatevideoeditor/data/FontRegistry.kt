package com.qtekfun.ultimatevideoeditor.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A font file that is not a usable TrueType/OpenType font. The message is fit to show to the user. */
class FontException(message: String) : Exception(message)

/** What the registry needs to know about a font file: the family name it presents. */
data class FontMeta(val family: String, val isOpenTypeCff: Boolean)

/**
 * Reads just enough of an sfnt (TrueType or OpenType) file to validate it and find its family name:
 * the table directory and the `name` table. Pure Kotlin on a byte array, so it is unit-testable.
 */
object FontMetaParser {
    const val MAX_BYTES = 25 * 1024 * 1024

    private const val TRUETYPE = 0x00010000
    private const val OPENTYPE = 0x4F54544F // 'OTTO'
    private const val APPLE_TRUE = 0x74727565 // 'true'
    private const val COLLECTION = 0x74746366 // 'ttcf'
    private const val TAG_NAME = 0x6E616D65 // 'name'
    private const val TAG_HEAD = 0x68656164 // 'head'
    private const val TAG_CMAP = 0x636D6170 // 'cmap'
    private const val TAG_GLYF = 0x676C7966 // 'glyf'
    private const val TAG_CFF = 0x43464620 // 'CFF '
    private const val TAG_CFF2 = 0x43464632 // 'CFF2'
    private const val NAME_FAMILY = 1
    private const val NAME_TYPOGRAPHIC_FAMILY = 16
    private const val PLATFORM_MAC = 1
    private const val PLATFORM_WINDOWS = 3
    private const val DIRECTORY_START = 12
    private const val RECORD_SIZE = 16

    /** @throws FontException when [bytes] is not a font this app can use. */
    fun parse(bytes: ByteArray): FontMeta {
        if (bytes.size > MAX_BYTES) throw FontException("This font file is too large (over ${MAX_BYTES / (1024 * 1024)} MB)")
        if (bytes.size < DIRECTORY_START) throw FontException("This is not a font file")
        val magic = u32(bytes, 0)
        if (magic == COLLECTION) throw FontException("Font collections (.ttc) are not supported; import a single font")
        if (magic != TRUETYPE && magic != OPENTYPE && magic != APPLE_TRUE) throw FontException("This is not a TrueType or OpenType font")
        val count = u16(bytes, 4)
        if (count == 0 || DIRECTORY_START + count * RECORD_SIZE > bytes.size) throw FontException("This font file is damaged")
        val tables = HashMap<Int, IntArray>()
        for (i in 0 until count) {
            val at = DIRECTORY_START + i * RECORD_SIZE
            val offset = u32(bytes, at + 8)
            val length = u32(bytes, at + 12)
            if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) throw FontException("This font file is damaged")
            tables[u32(bytes, at)] = intArrayOf(offset, length)
        }
        if (TAG_HEAD !in tables || TAG_CMAP !in tables || (TAG_GLYF !in tables && TAG_CFF !in tables && TAG_CFF2 !in tables)) {
            throw FontException("This font file has no usable glyphs")
        }
        val name = tables[TAG_NAME] ?: throw FontException("This font has no name")
        val family = familyName(bytes, name[0], name[1]) ?: throw FontException("This font has no family name")
        return FontMeta(family, TAG_GLYF !in tables)
    }

    private fun familyName(bytes: ByteArray, start: Int, length: Int): String? {
        if (length < 6) return null
        val count = u16(bytes, start + 2)
        val stringsAt = start + u16(bytes, start + 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val at = start + 6 + i * 12
            if (at + 12 > start + length) break
            val platform = u16(bytes, at)
            val nameId = u16(bytes, at + 6)
            if (nameId != NAME_FAMILY && nameId != NAME_TYPOGRAPHIC_FAMILY) continue
            if (platform != PLATFORM_WINDOWS && platform != PLATFORM_MAC) continue
            val len = u16(bytes, at + 8)
            val off = stringsAt + u16(bytes, at + 10)
            if (off < 0 || off + len > bytes.size) continue
            val text = if (platform == PLATFORM_WINDOWS) String(bytes, off, len, Charsets.UTF_16BE) else String(bytes, off, len, Charsets.ISO_8859_1)
            if (text.isBlank()) continue
            // Prefer the typographic family, then Windows strings (Unicode) over Mac ones.
            val rank = (if (nameId == NAME_TYPOGRAPHIC_FAMILY) 0 else 2) + (if (platform == PLATFORM_WINDOWS) 0 else 1)
            if (rank < bestRank) {
                best = text.trim()
                bestRank = rank
            }
        }
        return best
    }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}

/** A font the user imported: [id] is a hash of the file (so the same file has the same id on every device). */
data class FontEntry(val id: String, val family: String, val file: File)

/**
 * The app-wide library of imported fonts: one file per font under [dir], named `<id>.ttf` or `<id>.otf`
 * where the id is the first 16 hex digits of the file's SHA-256. There is no index to corrupt: the
 * family name is read back from each file. Fonts come only from files the user picks; nothing is
 * downloaded. Importing the same file twice is a no-op that returns the existing entry.
 */
class FontRegistry(private val dir: File) {

    /** Validates [bytes] and stores them. @throws FontException for a bad font, @throws IOException when it cannot be written. */
    fun import(bytes: ByteArray): FontEntry {
        val meta = FontMetaParser.parse(bytes)
        val id = idOf(bytes)
        val existing = file(id)
        if (existing != null) return FontEntry(id, meta.family, existing)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val target = File(dir, id + if (meta.isOpenTypeCff) ".otf" else ".ttf")
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot save the font")
        }
        return FontEntry(id, meta.family, target)
    }

    /** How [install] treated a font: stored now, already there, or refused because the id is taken by different bytes. */
    enum class InstallStatus { ADDED, ALREADY_PRESENT, CONFLICT }

    class FontInstall(val entry: FontEntry, val status: InstallStatus)

    /**
     * Like [import], and says whether the font was new. A font whose id is already stored with different
     * bytes (a 64-bit hash clash, which is not expected to happen) is [InstallStatus.CONFLICT] and is not stored.
     * @throws FontException for a bad font, @throws IOException when it cannot be written.
     */
    fun install(bytes: ByteArray): FontInstall {
        val meta = FontMetaParser.parse(bytes)
        val id = idOf(bytes)
        val existing = file(id)
        if (existing != null) {
            val same = try {
                existing.readBytes().contentEquals(bytes)
            } catch (e: IOException) {
                false
            }
            return FontInstall(FontEntry(id, meta.family, existing), if (same) InstallStatus.ALREADY_PRESENT else InstallStatus.CONFLICT)
        }
        return FontInstall(import(bytes), InstallStatus.ADDED)
    }

    /** The family name of font [id], or null when it is not imported or not readable. */
    fun family(id: String): String? = file(id)?.let { f ->
        try {
            FontMetaParser.parse(f.readBytes()).family
        } catch (e: FontException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /** All fonts that are still readable, sorted by family name. A damaged file is skipped, not an error. */
    fun list(): List<FontEntry> = dir.listFiles { f -> f.isFile && (f.name.endsWith(".ttf") || f.name.endsWith(".otf")) }.orEmpty()
        .mapNotNull { file ->
            try {
                FontEntry(file.nameWithoutExtension, FontMetaParser.parse(file.readBytes()).family, file)
            } catch (e: FontException) {
                null
            } catch (e: IOException) {
                null
            }
        }
        .sortedBy { it.family.lowercase() }

    /** Ids of the fonts that are present (cheap: file names only). */
    fun ids(): Set<String> = dir.listFiles { f -> f.isFile && (f.name.endsWith(".ttf") || f.name.endsWith(".otf")) }.orEmpty()
        .mapTo(LinkedHashSet()) { it.nameWithoutExtension }

    /** The file of font [id], or null when it is not imported. */
    fun file(id: String): File? {
        if (!id.matches(ID_PATTERN)) return null
        return listOf("ttf", "otf").map { File(dir, "$id.$it") }.firstOrNull { it.isFile }
    }

    fun remove(id: String): Boolean = file(id)?.delete() ?: false

    companion object {
        private val ID_PATTERN = Regex("[0-9a-f]{16}")

        /** The id of a font file: the first 8 bytes of its SHA-256, in hex. */
        fun idOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    }
}
