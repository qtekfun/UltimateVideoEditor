package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.domain.CubeLut
import com.ultimatevideo.uveditor.domain.CubeParser
import com.ultimatevideo.uveditor.domain.LutParseException
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/** A LUT in the library: [key] is what a clip's LUT effect refers to, stable for the same file contents. */
data class LutInfo(val key: Int, val name: String, val size: Int)

/**
 * The app-wide library of imported `.cube` LUTs, one file each under [dir] named `<key>_<size>_<name>.cube`.
 * The key is a 24-bit hash of the file's bytes (a float holds it exactly in the effect's value list), so
 * importing the same file twice is a no-op and a project moved to another device finds its LUT again if the
 * same file is imported there. A clip whose LUT is missing simply renders without it.
 */
class LutStore(private val dir: File) {

    /** Validates [text] as a .cube file and stores it. @throws LutParseException when it cannot be used. */
    fun import(displayName: String, text: String): LutInfo = install(displayName, text, keyOf(text)).info

    /** How [install] placed a LUT: stored under [LutInstall.info]'s key, which may differ from the one asked for. */
    enum class InstallStatus { ADDED, ALREADY_PRESENT, REKEYED }

    class LutInstall(val info: LutInfo, val status: InstallStatus)

    /**
     * Validates [text] and stores it under [desiredKey] (the key a project already refers to). Keys are a 24-bit
     * hash, so two different LUTs can share one: the content under the key is compared, and a different LUT
     * moves the new one to the next free key (the caller rewrites its references when the key changed). A LUT
     * whose bytes are already stored, under this key or one of the next ones, is not stored twice.
     * @throws LutParseException when [text] cannot be used, @throws IOException when it cannot be written.
     */
    fun install(displayName: String, text: String, desiredKey: Int): LutInstall {
        val lut = CubeParser.parse(text)
        val name = displayName.removeSuffix(".cube").ifBlank { lut.title ?: "LUT" }.let(::safeName)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        var key = desiredKey.coerceIn(1, MAX_KEY)
        val first = key
        repeat(MAX_PROBES) {
            val found = existing(key)
            if (found == null) {
                val target = File(dir, "${key}_${lut.size}_$name.cube")
                val temp = File(dir, "${target.name}.tmp")
                temp.writeText(text)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IOException("Cannot save the LUT")
                }
                return LutInstall(LutInfo(key, name, lut.size), if (key == first) InstallStatus.ADDED else InstallStatus.REKEYED)
            }
            if (sameText(found.second, text)) return LutInstall(found.first, InstallStatus.ALREADY_PRESENT)
            key = if (key >= MAX_KEY) 1 else key + 1
        }
        throw IOException("No free key for this LUT")
    }

    /** The text of the LUT stored under [key], or null when it is not in the library. */
    fun readText(key: Int): String? = existing(key)?.second?.let { file ->
        try {
            file.readText()
        } catch (e: IOException) {
            null
        }
    }

    /** The library entry for [key], or null. */
    fun info(key: Int): LutInfo? = existing(key)?.first

    private fun sameText(file: File, text: String): Boolean = try {
        file.readText() == text
    } catch (e: IOException) {
        false
    }

    fun list(): List<LutInfo> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".cube") }.orEmpty().mapNotNull(::infoOf).sortedBy { it.name.lowercase() }

    /** The LUT for [key], or null when it is not in the library or its file no longer parses. */
    fun load(key: Int): CubeLut? = existing(key)?.second?.let { file ->
        try {
            CubeParser.parse(file.readText())
        } catch (e: LutParseException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    fun delete(key: Int): Boolean = existing(key)?.second?.delete() ?: false

    private fun existing(key: Int): Pair<LutInfo, File>? =
        dir.listFiles { f -> f.isFile && f.name.startsWith("${key}_") && f.name.endsWith(".cube") }.orEmpty()
            .firstNotNullOfOrNull { f -> infoOf(f)?.let { it to f } }

    private fun infoOf(file: File): LutInfo? {
        val parts = file.name.removeSuffix(".cube").split('_', limit = 3)
        if (parts.size < 3) return null
        val key = parts[0].toIntOrNull() ?: return null
        val size = parts[1].toIntOrNull() ?: return null
        return LutInfo(key, parts[2], size)
    }

    companion object {
        private const val MAX_KEY = 16_777_215
        private const val MAX_PROBES = 64

        /** A positive 24-bit key from the file's content. */
        fun keyOf(text: String): Int {
            val crc = CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }
            return ((crc.value and 0xFFFFFFL).toInt()).coerceAtLeast(1)
        }

        private fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '.') it else '-' }.joinToString("").trim().take(48).ifBlank { "LUT" }
    }
}
