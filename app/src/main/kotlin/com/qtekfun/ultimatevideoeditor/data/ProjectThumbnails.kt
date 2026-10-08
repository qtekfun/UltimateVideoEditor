package com.qtekfun.ultimatevideoeditor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The picture shown on a project card. Everything happens on this device. */
interface ProjectThumbnails {
    /** The cached card picture of [projectId], made from [source] if needed; null when there is none or it cannot be read. */
    suspend fun load(projectId: String, source: ThumbnailSource?): Bitmap?
}

/**
 * Cards show one frame of the project's first clip, scaled down to [WIDTH_PX] and cached as a JPEG in the
 * app cache (the system may clear it; it is simply made again). The file name carries a hash of the source
 * and time, so a changed first clip makes a new file and the old ones of that project are removed.
 */
class AndroidProjectThumbnails(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectThumbnails {

    private val dir = File(context.cacheDir, "project-thumbs")

    override suspend fun load(projectId: String, source: ThumbnailSource?): Bitmap? = withContext(ioDispatcher) {
        if (source == null) return@withContext null
        val file = File(dir, "$projectId-${keyOf(source)}.jpg")
        if (file.isFile) BitmapFactory.decodeFile(file.path)?.let { return@withContext it }
        val bitmap = try {
            if (source.isImage) imageFrame(source) else videoFrame(source)
        } catch (e: IOException) {
            Log.w(TAG, "No thumbnail for $projectId: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for the thumbnail of $projectId")
            null
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever reports an unreadable file as a RuntimeException.
            Log.w(TAG, "Cannot read a frame for $projectId: ${e.message}")
            null
        } ?: return@withContext null
        store(projectId, file, bitmap)
        bitmap
    }

    private fun videoFrame(source: ThumbnailSource): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(source.uri))
            val frame = retriever.getFrameAtTime(source.timeMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return scaled(frame)
        } finally {
            retriever.release()
        }
    }

    private fun imageFrame(source: ThumbnailSource): Bitmap? {
        val uri = Uri.parse(source.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return scaled(decoded)
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= WIDTH_PX) return bitmap
        val height = (bitmap.height.toLong() * WIDTH_PX / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, WIDTH_PX, height, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun store(projectId: String, file: File, bitmap: Bitmap) {
        try {
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("$projectId-") }?.forEach { it.delete() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } catch (e: IOException) {
            // A cache that cannot be written only costs a regeneration next time.
            Log.w(TAG, "Cannot cache the thumbnail of $projectId: ${e.message}")
        }
    }

    private fun keyOf(source: ThumbnailSource): String =
        Integer.toHexString("${source.uri}|${source.timeMicros}|${source.isImage}".hashCode())

    private companion object {
        const val TAG = "ProjectThumbnails"
        const val WIDTH_PX = 320
        const val QUALITY = 80
    }
}
