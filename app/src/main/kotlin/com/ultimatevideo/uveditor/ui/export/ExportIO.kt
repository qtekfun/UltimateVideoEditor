package com.ultimatevideo.uveditor.ui.export

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/**
 * File access for an export, as raw descriptors so the view model stays free of Android types.
 * Every returned descriptor is detached: the caller owns it and must hand it to the engine or
 * [close] it.
 */
interface ExportIO {
    /** @throws IOException if the media cannot be read. */
    fun openAsset(uri: String): Int

    /** Opens the chosen document for writing; seekable, truncated. @throws IOException on failure. */
    fun openOutput(uri: String): Int

    fun close(fd: Int)

    /** The name the document has on disk (the user may have renamed it in the picker), or null when unknown. */
    fun displayName(uri: String): String? = null

    /** Removes a partly written output; failures are ignored (the file may already be gone). */
    fun deleteOutput(uri: String)
}

class ContentResolverExportIO(private val context: Context) : ExportIO {
    override fun openAsset(uri: String): Int = open(uri, "r")

    override fun openOutput(uri: String): Int = open(uri, "rwt")

    private fun open(uri: String, mode: String): Int {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), mode)
        } catch (e: SecurityException) {
            throw IOException("No permission to open $uri", e)
        }
        return (descriptor ?: throw FileNotFoundException(uri)).detachFd()
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

    override fun close(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: IOException) {
            // Closing failed: the descriptor is gone either way and there is nothing to retry.
        }
    }

    override fun deleteOutput(uri: String) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
        } catch (e: FileNotFoundException) {
            // Already gone: that is the outcome we wanted.
        } catch (e: SecurityException) {
            // The provider refuses deletion; the user can remove the partial file themselves.
        } catch (e: IllegalArgumentException) {
            // The provider does not support deleting this document.
        }
    }
}
