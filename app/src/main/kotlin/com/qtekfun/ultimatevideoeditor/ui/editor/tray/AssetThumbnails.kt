package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "UVTray"
private const val CACHE_BYTES = 12 * 1024 * 1024

/**
 * Small preview pictures for the tray: the first frame of a video, a downscaled photo. Everything is
 * decoded on this device from the file the project refers to; nothing is uploaded or fetched. Failures
 * return null (the tile shows a placeholder) and are logged, never thrown at the UI.
 */
internal object AssetThumbnails {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, asset: MediaAssetDto, sizePx: Int): Bitmap? {
        if (asset.kind == AssetKind.AUDIO) return null
        val key = "${asset.id}|${asset.uri}|$sizePx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                when (asset.kind) {
                    AssetKind.VIDEO -> videoFrame(context, Uri.parse(asset.uri), sizePx)
                    AssetKind.PHOTO -> photo(context, Uri.parse(asset.uri), sizePx)
                    AssetKind.AUDIO -> null
                }
            } catch (e: IOException) {
                Log.w(TAG, "No thumbnail for ${asset.id}: ${e.message}")
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to ${asset.id}: ${e.message}")
                null
            } catch (e: RuntimeException) {
                // MediaMetadataRetriever and ImageDecoder report unreadable files as runtime exceptions.
                Log.w(TAG, "Could not decode a thumbnail for ${asset.id}: ${e.message}")
                null
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun videoFrame(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
            val (tw, th) = fit(w, h, sizePx)
            return retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        }
    }

    private fun photo(context: Context, uri: Uri, sizePx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val (tw, th) = fit(info.size.width, info.size.height, sizePx)
            decoder.setTargetSize(tw, th)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** [width] x [height] scaled down to fit in a [limit] square (never up), at least 1 pixel each way. */
    internal fun fit(width: Int, height: Int, limit: Int): Pair<Int, Int> {
        val longest = max(width, height).coerceAtLeast(1)
        if (longest <= limit) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val scale = limit.toFloat() / longest
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}
