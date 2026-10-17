package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.serialization.json.Json
import java.io.IOException

/** What looking at a finished `.uvbundle` found. A bundle is never reported as fine without having been looked at. */
sealed interface BundleVerification {
    /** The directory is readable, every entry is there with the size that was written, and the project data parses. */
    data class Verified(val entryCount: Int, val fileBytes: Long) : BundleVerification

    /** The file is kept, but something is wrong with it: [problems] says what, in words for the user. */
    data class Warning(val problems: List<UiText>) : BundleVerification

    /** The check itself could not run (the file could not be opened again); nothing is known either way. */
    data class CouldNotVerify(val reason: UiText) : BundleVerification

    /** The user cancelled the check. */
    data object Skipped : BundleVerification
}

/**
 * Checks the zip a backup wrote, using the same [ZipReader] an import uses: the central directory at the end of the file is
 * the part a cut-short or silently failed write loses, so reading it back proves the file is complete. The check is quick on a
 * multi-gigabyte file: it reads the directory and the two small JSON entries, not the media.
 */
object BundleChecker {
    private val json = Json { ignoreUnknownKeys = true }

    fun check(source: RandomAccess, written: BundleWriteResult): BundleVerification {
        val problems = ArrayList<UiText>()
        if (written.bytesWritten > 0 && source.size != written.bytesWritten) {
            problems += UiText.res(R.string.bv_size, source.size, written.bytesWritten)
        }
        val reader = try {
            ZipReader(source)
        } catch (e: BundleError) {
            problems += UiText.res(R.string.bv_toc, e.detail)
            return BundleVerification.Warning(problems)
        } catch (e: IOException) {
            return BundleVerification.CouldNotVerify(UiText.res(R.string.verify_reason_bundle_unreadable, e.javaClass.simpleName, e.message.orEmpty()))
        }
        val byName = reader.entries.filter { !it.isDirectory }.associateBy { it.name }
        if (reader.entries.size != written.entries.size) {
            problems += UiText.res(R.string.bv_count, reader.entries.size, written.entries.size)
        }
        for (name in listOf(ProjectBundle.MANIFEST, ProjectBundle.PROJECT)) {
            if (name !in byName) problems += UiText.res(R.string.bv_missing, name)
        }
        for (entry in written.entries) {
            val found = byName[entry.name]
            if (found == null) {
                if (entry.name != ProjectBundle.MANIFEST && entry.name != ProjectBundle.PROJECT) problems += UiText.res(R.string.bv_missing, entry.name.substringAfterLast('/').take(60))
            } else if (found.size != entry.size) {
                problems += UiText.res(R.string.bv_entry_size, entry.name.substringAfterLast('/').take(60), found.size, entry.size)
            }
        }
        try {
            readJson(reader, byName[ProjectBundle.MANIFEST])?.let { text ->
                val manifest = json.decodeFromString<BundleManifest>(text)
                for (media in manifest.media) {
                    val entry = media.entry ?: continue
                    if (entry !in byName) problems += UiText.res(R.string.bv_listed, media.name.take(60))
                }
            }
            readJson(reader, byName[ProjectBundle.PROJECT])
        } catch (e: BundleError) {
            problems += UiText.res(R.string.bv_readback, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            problems += UiText.res(R.string.bv_json)
        } catch (e: IOException) {
            problems += UiText.res(R.string.bv_readback, e.message.orEmpty())
        }
        return if (problems.isEmpty()) BundleVerification.Verified(reader.entries.size, source.size) else BundleVerification.Warning(problems)
    }

    /** The whole text of a small entry; reading to the end also proves its compressed data is intact. Null when the entry is absent. */
    private fun readJson(reader: ZipReader, entry: ZipEntryInfo?): String? {
        if (entry == null) return null
        if (entry.size > MAX_JSON) throw BundleError.TooLarge("${entry.name} is larger than expected")
        val bytes = reader.open(entry).use { it.readBytes() }
        if (bytes.size.toLong() != entry.size) throw BundleError.Corrupt("${entry.name} has ${bytes.size} bytes, expected ${entry.size}")
        return bytes.toString(Charsets.UTF_8)
    }

    private const val MAX_JSON = 64L * 1024 * 1024
}
