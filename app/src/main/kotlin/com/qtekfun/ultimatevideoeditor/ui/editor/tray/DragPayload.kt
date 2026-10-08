package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log

private const val TAG = "UVTray"

/** The kind of media a MIME type stands for, or null for anything the editor cannot place. */
fun kindForMime(mime: String?): AssetKind? = when {
    mime == null -> null
    mime.startsWith("video/") -> AssetKind.VIDEO
    mime.startsWith("image/") -> AssetKind.PHOTO
    mime.startsWith("audio/") -> AssetKind.AUDIO
    else -> null
}

/** The placeable kinds among the MIME types of a drag, in order (a drag of plain text has none). */
fun kindsOfMimes(mimes: List<String>): List<AssetKind> = mimes.mapNotNull(::kindForMime)

/** The MIME types a drag declares. */
fun ClipDescription.mimes(): List<String> = (0 until mimeTypeCount).map { getMimeType(it) }

/** Every URI of a drag from another app, in order. */
fun ClipData.uris(): List<String> = (0 until itemCount).mapNotNull { getItemAt(it).uri?.toString() }

/**
 * Keeps read access to a dropped file after the app restarts when the source offers it. Many providers only
 * grant access for the lifetime of the drop; those files still import and edit, but would need relinking
 * after a restart, so the caller is told (returns false) and can say so.
 */
fun persistReadAccess(context: Context, uri: String): Boolean = try {
    context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    true
} catch (e: SecurityException) {
    Log.i(TAG, "No persistable access for $uri: ${e.message}")
    false
} catch (e: IllegalArgumentException) {
    Log.i(TAG, "Not a persistable URI: $uri")
    false
}

/** The activity behind a context (the editor's views may be created with a wrapper), or null. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
