package com.qtekfun.ultimatevideoeditor.proxy

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * File access for making proxies, as raw descriptors so the generator stays free of Android types. Every
 * descriptor returned is detached: the caller owns it and must hand it to the engine or [close] it.
 */
interface ProxyMediaAccess {
    /** @throws IOException if the source cannot be read. */
    fun openSource(uri: String): Int

    /** Opens [file] for writing, created and truncated, seekable. @throws IOException on failure. */
    fun openOutput(file: File): Int

    fun close(fd: Int)

    /** Length of the source in bytes, or -1 when it is not known. */
    fun sizeOf(uri: String): Long

    /** @throws ProxyException with [ProxyErrorCode.SOURCE_UNREADABLE] if the file cannot be probed as video. */
    fun probe(uri: String): SourceInfo
}

class AndroidProxyMediaAccess(private val context: Context) : ProxyMediaAccess {
    override fun openSource(uri: String): Int {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        } catch (e: SecurityException) {
            throw IOException("No permission to read $uri", e)
        }
        return (descriptor ?: throw FileNotFoundException(uri)).detachFd()
    }

    override fun openOutput(file: File): Int =
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()

    override fun close(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: IOException) {
            // The descriptor is gone either way and there is nothing to retry.
        }
    }

    override fun sizeOf(uri: String): Long = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: -1L
    } catch (e: FileNotFoundException) {
        -1L
    } catch (e: SecurityException) {
        -1L
    }

    override fun probe(uri: String): SourceInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
            val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            if (width == null || height == null || width <= 0 || height <= 0) {
                throw ProxyException(ProxyErrorCode.SOURCE_UNREADABLE, "The file has no video picture to make a proxy of")
            }
            return SourceInfo(
                width = width,
                height = height,
                rotationDegrees = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0,
                bitrateBps = number(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: 0L,
                sizeBytes = sizeOf(uri),
            )
        } catch (e: RuntimeException) {
            throw ProxyException(ProxyErrorCode.SOURCE_UNREADABLE, "The source cannot be read: ${e.message}", e)
        } finally {
            retriever.release()
        }
    }
}
