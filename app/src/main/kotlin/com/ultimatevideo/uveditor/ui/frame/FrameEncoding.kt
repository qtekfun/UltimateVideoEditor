package com.ultimatevideo.uveditor.ui.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.chooseJpegQuality
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/** A finished image file in memory. [quality] is the JPEG quality used (null for PNG); [fitsLimit] is false when a size limit was not met. */
class EncodedFrame(val bytes: ByteArray, val quality: Int?, val fitsLimit: Boolean)

fun interface FrameEncoder {
    /** Blocking and heavy: call off the main thread. [maxBytes] limits a JPEG by lowering its quality; a PNG ignores both. @throws IOException */
    fun encode(frame: RenderedFrame, format: FrameFormat, quality: Int, maxBytes: Long?): EncodedFrame
}

/**
 * Encodes with [Bitmap.compress] from a bitmap tagged sRGB: the compositor's SDR output is Rec.709 primaries with the
 * sRGB-like display transfer the preview shows, so the tag says what the pixels are (an HLG project is tone-mapped to it
 * by the engine first). No third-party encoder.
 */
class BitmapFrameEncoder : FrameEncoder {
    override fun encode(frame: RenderedFrame, format: FrameFormat, quality: Int, maxBytes: Long?): EncodedFrame {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            frame.rgba.rewind()
            bitmap.copyPixelsFromBuffer(frame.rgba)
            return when (format) {
                FrameFormat.PNG -> EncodedFrame(compress(bitmap, Bitmap.CompressFormat.PNG, PNG_QUALITY), null, true)
                FrameFormat.JPEG -> {
                    val encoded = HashMap<Int, ByteArray>()
                    val choice = chooseJpegQuality(quality, maxBytes) { q ->
                        encoded.getOrPut(q) { compress(bitmap, Bitmap.CompressFormat.JPEG, q) }.size.toLong()
                    }
                    EncodedFrame(checkNotNull(encoded[choice.quality]), choice.quality, choice.fits)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out)) throw IOException("The picture could not be encoded as $format")
        return out.toByteArray()
    }

    private companion object {
        const val PNG_QUALITY = 100 // ignored for PNG
    }
}

/** Where the finished file goes: the document the user chose in the system picker. */
interface FrameSink {
    /** Replaces the content of the document. @throws IOException */
    fun write(uri: String, bytes: ByteArray)

    /** Removes a document that was created for a picture that could not be made; true when it is gone. */
    fun delete(uri: String): Boolean

    /** The name the document has on disk (the user may have renamed it in the picker). */
    fun displayName(uri: String): String?
}

class ContentResolverFrameSink(private val context: Context) : FrameSink {
    override fun write(uri: String, bytes: ByteArray) {
        val stream = try {
            context.contentResolver.openOutputStream(Uri.parse(uri), "rwt")
        } catch (e: SecurityException) {
            throw IOException("No permission to write $uri", e)
        } ?: throw FileNotFoundException(uri)
        stream.use { it.write(bytes) }
    }

    override fun delete(uri: String): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
    } catch (e: FileNotFoundException) {
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    override fun displayName(uri: String): String? = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
