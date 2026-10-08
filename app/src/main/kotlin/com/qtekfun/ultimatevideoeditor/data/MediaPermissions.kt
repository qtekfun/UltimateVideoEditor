package com.qtekfun.ultimatevideoeditor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** The persisted read permissions this app holds on documents picked by the user. */
interface PersistedUris {
    /** How many the platform allows before it starts dropping the oldest. */
    val limit: Int

    fun held(): List<String>

    fun release(uri: String)
}

/** Decides which persisted permissions are worth keeping; pure so it can be tested without Android. */
object PermissionTrim {
    /** Fraction of the limit above which housekeeping runs. */
    private const val NEAR_LIMIT = 0.8

    fun nearLimit(held: Int, limit: Int): Boolean = limit > 0 && held >= (limit * NEAR_LIMIT).toInt()

    /** Held permissions that no project refers to any more; releasing them frees slots without losing any media. */
    fun unused(held: List<String>, referenced: Set<String>): List<String> = held.filter { it !in referenced }
}

/**
 * Android keeps at most [AndroidPersistedUris.LIMIT] persisted permissions per app and silently drops
 * the oldest beyond it, which would make old projects open with missing media. Releasing the ones no
 * project uses (deleted projects, relinked files) keeps real projects safely under the limit.
 * @return how many permissions were released.
 */
fun trimPersistedUris(uris: PersistedUris, referenced: Set<String>, log: (String) -> Unit = {}): Int {
    val held = uris.held()
    if (!PermissionTrim.nearLimit(held.size, uris.limit)) return 0
    val unused = PermissionTrim.unused(held, referenced)
    for (uri in unused) uris.release(uri)
    log("Released ${unused.size} unused permissions (${held.size} of ${uris.limit} were held)")
    return unused.size
}

class AndroidPersistedUris(private val context: Context) : PersistedUris {
    override val limit: Int = LIMIT

    override fun held(): List<String> =
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }

    override fun release(uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone; nothing to release.
            Log.w("MediaPermissions", "Could not release $uri: ${e.message}")
        }
    }

    companion object {
        /** The documented limit since Android 11; this app's minSdk is 33. */
        const val LIMIT = 512
    }
}
