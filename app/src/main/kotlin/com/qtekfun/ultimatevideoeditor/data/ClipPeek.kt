package com.qtekfun.ultimatevideoeditor.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * What a project needs from a clip to be created "like it": the picture size, and for a video its frame
 * rate and colour space ([fpsNum], [fpsDen] and [colorSpace] are null for a photo).
 */
data class MatchedClip(
    val displayName: String?,
    val width: Int,
    val height: Int,
    val fpsNum: Int?,
    val fpsDen: Int?,
    val colorSpace: String?,
)

/** Reads a clip's format without keeping any permission on it: it is only a model for new project settings. */
fun interface ClipPeeker {
    /** @throws MediaImportException if the file cannot be read or is not something the editor can use. */
    suspend fun peek(uri: String): MatchedClip
}

class AndroidClipPeeker(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClipPeeker {

    override suspend fun peek(uri: String): MatchedClip = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        // The importer's probe reads frame rate, colour space and the name; it does not take a permission.
        val probed = AndroidMediaImporter(context, ioDispatcher).probe(parsed)
        val (width, height) = if (probed.isImage) imageSize(parsed) else videoSize(parsed)
        if (width <= 0 || height <= 0) throw MediaImportException("Cannot read the size of the selected file", problem = MediaProblem.UNSUPPORTED)
        MatchedClip(
            displayName = probed.displayName,
            width = width,
            height = height,
            fpsNum = probed.fpsNum.takeUnless { probed.isImage },
            fpsDen = probed.fpsDen.takeUnless { probed.isImage },
            colorSpace = probed.colorSpace.takeUnless { probed.isImage },
        )
    }

    /** Header only. EXIF orientation is not applied here: a rotated photo may report its sensor orientation. */
    private fun imageSize(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) throw MediaImportException("Cannot open the selected picture")
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: IOException) {
            throw MediaImportException("Cannot open the selected picture", e, MediaProblem.UNREADABLE)
        } catch (e: SecurityException) {
            throw MediaImportException("No permission to read the selected picture", e, MediaProblem.PERMISSION_LOST)
        }
        return options.outWidth to options.outHeight
    }

    /** The displayed size: width and height swap for a clip stored rotated by 90 or 270 degrees. */
    private fun videoSize(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (e: RuntimeException) {
                throw MediaImportException("Cannot open the selected file", e, MediaProblem.UNREADABLE)
            }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rotation == 90 || rotation == 270) h to w else w to h
        } finally {
            retriever.release()
        }
    }
}
