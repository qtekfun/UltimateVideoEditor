package com.ultimatevideo.uveditor.data.interchange

import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * The folder the user chose for footage unpacked from LumaFusion packages. In the app it is a Storage Access Framework
 * tree (so the files are visible to the user and can sit on a USB drive or an SD card); in tests it is a directory.
 */
interface MediaFolder {
    /** Names of the files already in the folder. @throws IOException when the folder cannot be read (removed drive, permission revoked). */
    @Throws(IOException::class)
    fun fileNames(): Set<String>

    /** Creates an empty file called [name] (already unique in the folder). @throws IOException when it cannot be created. */
    @Throws(IOException::class)
    fun create(name: String, mimeType: String): MediaTarget
}

/** A file created in a [MediaFolder]. */
interface MediaTarget {
    /** The address projects use for the file (`content://` for a tree). */
    val uri: String

    @Throws(IOException::class)
    fun openOutput(): OutputStream

    /** Free bytes on the volume the file lives on, or null when the system does not say. */
    fun freeBytes(): Long?

    /** Removes the file; false when that failed. */
    fun delete(): Boolean
}

/** Names for files put in the user's folder: safe, and never the name of a file that is already there. */
object MediaFileNames {
    private const val MAX_LENGTH = 120

    /** [name] without folders, control characters or characters file systems refuse; never empty. */
    fun clean(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.map { if (it.isISOControl() || it in "<>:\"|?*") '_' else it }.joinToString("").trim().trim('.')
        if (cleaned.isEmpty()) return "media"
        if (cleaned.length <= MAX_LENGTH) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 12) cleaned.substring(dot) else ""
        return cleaned.take(MAX_LENGTH - ext.length) + ext
    }

    /** [wanted], or "name (2).ext", "name (3).ext" ... when [taken] holds it (names compare ignoring case). */
    fun unique(wanted: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (wanted.lowercase(Locale.ROOT) !in lower) return wanted
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in lower) return candidate
            n++
        }
    }

    /** A media type for a footage file name, so a document provider keeps the extension. */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mov" -> "video/quicktime"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/x-wav"
        else -> "application/octet-stream"
    }
}

/** Where the user's media folder is stored and chosen; the app implements it with preferences and a persisted tree permission. */
interface MediaFolderSettings {
    /** The tree address of the folder, or null when none is chosen. */
    fun treeUri(): String?

    /** The folder's name as a person knows it, or null when none is chosen or it cannot be read now. */
    fun label(): String?

    /** Remembers [treeUri] as the folder (and keeps permission to it). */
    fun set(treeUri: String)
}
