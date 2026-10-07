package com.ultimatevideo.uveditor.ui.frame

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.IOException

fun interface FrameEncoder {
    /** Blocking and heavy: call off the main thread. @throws IOException */
    fun encodeJpeg(frame: RenderedFrame, quality: Int): ByteArray
}

/**
 * Encodes with [Bitmap.compress] from a bitmap tagged sRGB: the compositor's SDR output is Rec.709 primaries with the
 * sRGB-like display transfer the preview shows (an HLG project is tone-mapped to it by the engine first). The JPEG carries
 * the sRGB ICC profile. No third-party encoder.
 */
class BitmapFrameEncoder : FrameEncoder {
    override fun encodeJpeg(frame: RenderedFrame, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            frame.rgba.rewind()
            bitmap.copyPixelsFromBuffer(frame.rgba)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("The picture could not be encoded as JPEG")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}

/** A picture in the public gallery: where it is and the name it ended up with. */
class SavedImage(val uri: String, val displayName: String, val folder: String)

/** Where the finished file goes: the public pictures folder. */
interface FrameSink {
    /** Writes [bytes] as a new JPEG called [displayName]. The final name can differ (the system adds a number if it exists). @throws IOException */
    fun saveJpeg(displayName: String, bytes: ByteArray): SavedImage
}

/**
 * Saves into `Pictures/ultimateVE` through MediaStore: the row is created pending (hidden from other apps), written, then
 * published. Needs no storage permission on Android 10 and later (minSdk is 31), and creates the folder by itself.
 */
class MediaStoreFrameSink(private val context: Context) : FrameSink {
    override fun saveJpeg(displayName: String, bytes: ByteArray): SavedImage {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = try {
            resolver.insert(collection, values)
        } catch (e: SecurityException) {
            throw IOException("The gallery refused the new picture: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("The gallery refused the new picture: ${e.message}", e)
        } ?: throw IOException("The gallery could not create $FOLDER/$displayName")
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write $displayName in the gallery")
            stream.use { it.write(bytes) }
            val publish = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) < 1) throw IOException("The gallery did not accept $displayName")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: RuntimeException) {
            resolver.delete(uri, null, null)
            throw IOException("Saving $displayName failed: ${e.message}", e)
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
        } ?: displayName
        return SavedImage(uri.toString(), finalName, FOLDER)
    }

    companion object {
        const val FOLDER = "Pictures/ultimateVE"
        const val MIME_JPEG = "image/jpeg"
    }
}
