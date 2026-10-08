package com.qtekfun.ultimatevideoeditor.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Opens [uri] for reading. A provider that returns no descriptor, a missing file, a vanished volume (ExternalStorageProvider
 * throws IllegalArgumentException "Failed to determine if ... is child of ..." when a USB drive is not mounted) or a provider
 * that died all come out as a [MediaImportException] naming [what]. The caller owns the descriptor and must not call this on
 * the main thread (opening can block on the provider).
 */
fun ContentResolver.openMediaFd(uri: String, what: String): ParcelFileDescriptor =
    guardMedia(what) { openFileDescriptor(Uri.parse(uri), "r") ?: throw FileNotFoundException(uri) }

/**
 * The exceptions a content URI can throw when its file, provider or volume is gone (a USB drive that is not plugged in, a
 * revoked grant, a provider process that died), and what each one means. Everything else is a bug and is left to surface.
 */
object MediaFailure {
    /**
     * The problem [e] stands for, or null when it is not one of the I/O-boundary failures a missing file explains.
     * Binder carries IllegalArgumentException ("Unknown URI", "Missing root"), IllegalStateException and
     * UnsupportedOperationException across from providers, so they are as likely as FileNotFoundException.
     */
    fun classify(e: Throwable): MediaProblem? = when (e) {
        is SecurityException -> MediaProblem.PERMISSION_LOST
        is IOException, is IllegalArgumentException, is IllegalStateException, is UnsupportedOperationException, is RemoteException ->
            MediaProblem.UNREADABLE
        else -> null
    }

    /** The user-facing sentence for [problem] about the file called [name]. */
    fun describe(problem: MediaProblem, name: String): String = when (problem) {
        MediaProblem.UNREADABLE -> "Cannot open $name. If it is on a USB drive or SD card, connect it again"
        MediaProblem.PERMISSION_LOST -> "No permission to read $name"
        MediaProblem.UNSUPPORTED -> "$name is not a file the editor can use"
    }
}

/**
 * Runs a read of a media file and turns the failures of [MediaFailure] into a [MediaImportException] naming [what]; the
 * single place the I/O boundary of a content URI is caught. A [MediaImportException] thrown inside passes through, and an
 * exception that is not an I/O failure is rethrown untouched (it is a bug).
 */
inline fun <T> guardMedia(what: String, block: () -> T): T {
    try {
        return block()
    } catch (e: MediaImportException) {
        throw e
    } catch (e: Exception) {
        val problem = MediaFailure.classify(e) ?: throw e
        throw MediaImportException(MediaFailure.describe(problem, what), e, problem)
    }
}

/** [block]'s result, or null when it failed the way an absent file does ([MediaFailure]); other exceptions are bugs and propagate. */
inline fun <T : Any> mediaOrNull(block: () -> T?): T? {
    try {
        return block()
    } catch (e: Exception) {
        if (MediaFailure.classify(e) == null) throw e
        return null
    }
}

/** Runs [block] for callers that already handle [IOException]: provider-gone exceptions that are not one are re-thrown as one. */
inline fun <T> asIoFailure(uri: String, block: () -> T): T {
    try {
        return block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        if (MediaFailure.classify(e) == null) throw e
        throw IOException("Cannot read $uri: ${e.message}", e)
    }
}
