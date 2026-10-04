package com.ultimatevideo.uveditor.engine.still

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import com.ultimatevideo.uveditor.domain.ImageLayer
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.title.LayerImages
import java.io.IOException
import kotlin.math.max

/**
 * Loads the pictures of a title's image layers: photos from their files with [ImageDecoder] (already
 * scaled to about the size they will be drawn at, EXIF orientation applied) and stickers from the
 * built-in art. Small recently used pictures are kept so redrawing a title does not decode again. A
 * picture that is missing, unreadable or unsupported gives null: the layer is skipped, never an error.
 */
class AndroidLayerImages(private val context: Context) : LayerImages {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun load(layer: ImageLayer, targetLongSidePx: Int): Bitmap? {
        val source = if (layer.kind == StillKind.PHOTO) layer.resolvedUri ?: return null else layer.id
        val key = "${layer.kind}:$source:$targetLongSidePx"
        cache.get(key)?.let { return it }
        val bitmap = when (layer.kind) {
            StillKind.PHOTO -> decodePhoto(source, targetLongSidePx)
            StillKind.STICKER -> drawSticker(source, targetLongSidePx)
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodePhoto(uri: String, target: Int): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val longSide = max(info.size.width, info.size.height)
                if (longSide > target) {
                    val scale = target.toDouble() / longSide
                    decoder.setTargetSize(max((info.size.width * scale).toInt(), 1), max((info.size.height * scale).toInt(), 1))
                }
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        return if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }

    private fun drawSticker(id: String, side: Int): Bitmap? {
        val art = StickerArt.find(id) ?: return null
        val size = side.coerceAtLeast(MIN_STICKER_SIDE)
        return try {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { art.draw(Canvas(it), size.toFloat()) }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private companion object {
        /** About 24 MB of decoded pictures: a handful of layer-sized photos. */
        const val CACHE_BYTES = 24 * 1024 * 1024
        const val MIN_STICKER_SIDE = 32
    }
}
