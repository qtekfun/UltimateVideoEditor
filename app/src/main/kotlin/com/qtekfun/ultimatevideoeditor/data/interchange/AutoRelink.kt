package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.mediaOrNull
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A file the app already has access to (an asset of another local project): where it is, what it is called and how big. */
data class RelinkCandidate(val uri: String, val displayName: String, val sizeBytes: Long?)

/**
 * Finds, among the files this device already has access to, the ones a bundle's media stand for: same
 * name (ignoring case) and the same size in bytes. A name alone is never enough, so a different file that
 * happens to share a name is not picked up. Everything stays on the device: no search outside the
 * libraries of the local projects, and no new permission is requested.
 */
object AutoRelink {

    /** Asset id -> URI of the matching candidate, for each wanted file that has one. */
    fun match(wanted: List<BundleMedia>, candidates: List<RelinkCandidate>): Map<String, String> {
        val bySignature = LinkedHashMap<Pair<String, Long>, String>()
        for (c in candidates) {
            val size = c.sizeBytes ?: continue
            if (size < 0 || c.displayName.isBlank()) continue
            bySignature.putIfAbsent(c.displayName.trim().lowercase() to size, c.uri)
        }
        val found = LinkedHashMap<String, String>()
        for (m in wanted) {
            if (m.sizeBytes < 0 || m.name.isBlank()) continue
            bySignature[m.name.trim().lowercase() to m.sizeBytes]?.let { found[m.assetId] = it }
        }
        return found
    }
}

/** Size and bytes of media addressed by URI (SAF in the app, in-memory in tests). */
class ContentResolverMediaAccess(private val resolver: ContentResolver) : BundleMediaSource {
    override fun sizeOf(uri: String): Long? = try {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            File(parsed.path.orEmpty()).takeIf { it.isFile }?.length()
        } else {
            resolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            } ?: resolver.openAssetFileDescriptor(parsed, "r")?.use { it.length.takeIf { len -> len >= 0 } }
        }
    } catch (e: SecurityException) {
        // A permission that was lost is the same as a file that is gone: the caller treats both as unavailable.
        null
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IllegalStateException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    override fun open(uri: String): InputStream? = mediaOrNull { resolver.openInputStream(Uri.parse(uri)) }
}
