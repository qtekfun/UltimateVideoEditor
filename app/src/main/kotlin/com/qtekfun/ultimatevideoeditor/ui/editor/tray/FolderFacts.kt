package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The length of a file of a linked folder, read only for the tiles that are on screen. Nothing is stored on disk. */
internal object FolderFacts {
    private const val UNKNOWN = -1L
    private val cache = LruCache<String, Long>(1024)

    /** Milliseconds, or null for a picture or a file whose length cannot be read. A failure is remembered for the session, so a scroll does not retry it. */
    suspend fun durationMillis(context: Context, uri: String, kind: AssetKind): Long? {
        if (kind == AssetKind.PHOTO) return null
        cache.get(uri)?.let { return it.takeIf { ms -> ms != UNKNOWN } }
        val read = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, Uri.parse(uri))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: UNKNOWN
                }
            } catch (e: RuntimeException) {
                // The retriever reports an unreadable file as a runtime exception.
                Log.w("UVTray", "No length for ${uri.substringAfterLast('/')}: ${e.message}")
                UNKNOWN
            } catch (e: java.io.IOException) {
                Log.w("UVTray", "No length for ${uri.substringAfterLast('/')}: ${e.message}")
                UNKNOWN
            }
        }
        cache.put(uri, read)
        return read.takeIf { it != UNKNOWN }
    }
}

/** `m:ss`, or `h:mm:ss` from an hour up; fractions of a second are dropped. Never negative. */
internal fun clockText(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
