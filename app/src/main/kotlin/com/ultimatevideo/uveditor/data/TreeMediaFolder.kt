package com.ultimatevideo.uveditor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.ultimatevideo.uveditor.data.interchange.LayoutSummary
import com.ultimatevideo.uveditor.data.interchange.MediaChild
import com.ultimatevideo.uveditor.data.interchange.MediaFolder
import com.ultimatevideo.uveditor.data.interchange.MediaFolderSettings
import com.ultimatevideo.uveditor.data.interchange.MediaLayout
import com.ultimatevideo.uveditor.data.interchange.MediaTarget
import java.io.IOException
import java.io.OutputStream

/**
 * A folder of a Storage Access Framework tree (the one the user picked, or a folder inside it); files and folders are
 * created and read through the document provider.
 */
class TreeMediaFolder private constructor(
    private val context: Context,
    private val tree: Uri,
    private val documentId: String,
) : MediaFolder {
    /** The tree the user picked, at its top. */
    constructor(context: Context, tree: Uri) : this(context, tree, DocumentsContract.getTreeDocumentId(tree))

    private val resolver = context.contentResolver

    /** The address of this folder's document, usable as the starting point of a document picker. */
    val documentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    override val name: String? get() = label()

    override fun children(): List<MediaChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = try {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )
        } catch (e: SecurityException) {
            throw IOException("access to the media folder was revoked", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the media folder is not available", e)
        } ?: throw IOException("the media folder is not available")
        return cursor.use {
            val list = ArrayList<MediaChild>()
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                list += MediaChild(n, it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
            list
        }
    }

    override fun openFolder(name: String): MediaFolder {
        val id = childId(name) ?: throw IOException("the folder $name is not in the media folder")
        return TreeMediaFolder(context, tree, id)
    }

    override fun createFolder(name: String): MediaFolder {
        val doc = createChild(DocumentsContract.Document.MIME_TYPE_DIR, name)
        return TreeMediaFolder(context, tree, DocumentsContract.getDocumentId(doc))
    }

    override fun delete(): Boolean = deleteDocument(documentUri, "the folder")

    /**
     * Removes [doc]. The provider throws IllegalStateException ("Failed to delete") when the media scanner has the file open
     * at that moment (seen right after a cancelled copy on a Pixel 8), so a failure is retried a few times before giving up.
     */
    private fun deleteDocument(doc: Uri, what: String): Boolean {
        // A cancelled import cleans up on a thread that may still carry the interrupt: that would fail the provider call
        // and the sleep between attempts, and leave the file behind. The flag is put back afterwards.
        val interrupted = Thread.interrupted()
        try {
            repeat(DELETE_ATTEMPTS) { attempt ->
                try {
                    return DocumentsContract.deleteDocument(resolver, doc)
                } catch (e: IOException) {
                    Log.w(TAG, "could not remove $what: ${e.message}")
                    return false
                } catch (e: SecurityException) {
                    Log.w(TAG, "could not remove $what: ${e.message}")
                    return false
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "could not remove $what (attempt ${attempt + 1}): ${e.message}")
                    if (attempt < DELETE_ATTEMPTS - 1) Thread.sleep(DELETE_RETRY_MS)
                } catch (e: RuntimeException) {
                    // After a failed attempt the document may be gone already (the provider then says it is unknown): the
                    // caller checks what is left, so this must not escape and skip the rest of a cleanup.
                    Log.w(TAG, "could not remove $what (attempt ${attempt + 1}): ${e.javaClass.simpleName} ${e.message}")
                    return false
                }
            }
            return false
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun childId(name: String): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = try {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )
        } catch (e: SecurityException) {
            throw IOException("access to the media folder was revoked", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the media folder is not available", e)
        } ?: throw IOException("the media folder is not available")
        return cursor.use {
            var found: String? = null
            while (found == null && it.moveToNext()) if (it.getString(1) == name) found = it.getString(0)
            found
        }
    }

    private fun createChild(mimeType: String, name: String): Uri = try {
        DocumentsContract.createDocument(resolver, documentUri, mimeType, name)
    } catch (e: SecurityException) {
        throw IOException("access to the media folder was revoked", e)
    } catch (e: IllegalStateException) {
        throw IOException("the media folder refused $name", e)
    } ?: throw IOException("the media folder could not create $name")

    override fun create(name: String, mimeType: String): MediaTarget {
        val doc = createChild(mimeType, name)
        return object : MediaTarget {
            override val uri: String = doc.toString()

            override fun openOutput(): OutputStream = resolver.openOutputStream(doc, "w") ?: throw IOException("cannot write $name")

            override fun freeBytes(): Long? = try {
                resolver.openFileDescriptor(doc, "w")?.use { pfd ->
                    val stat = Os.fstatvfs(pfd.fileDescriptor)
                    stat.f_bavail * stat.f_frsize
                }
            } catch (e: ErrnoException) {
                // Some providers hand out descriptors that do not belong to a file system: free space is then unknown.
                Log.i(TAG, "free space of the media folder is unknown: ${e.message}")
                null
            } catch (e: IOException) {
                Log.i(TAG, "free space of the media folder is unknown: ${e.message}")
                null
            }

            override fun delete(): Boolean = deleteDocument(doc, "the partial file $name")
        }
    }

    /** The name of the folder, or null when it cannot be read. */
    fun label(): String? = try {
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "cannot read the media folder name: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "cannot read the media folder name: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "MediaFolder"
        const val DELETE_ATTEMPTS = 5
        const val DELETE_RETRY_MS = 300L
    }
}

/** The chosen folder lives in preferences; the app keeps read and write permission to it across restarts. */
class PreferencesMediaFolderSettings(private val context: Context) : MediaFolderSettings {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Folders created only to open the backup picker there; removed again when nothing was saved. */
    private var backupsCreated: List<MediaFolder> = emptyList()

    override fun treeUri(): String? = prefs.getString(KEY_TREE, null)

    override fun label(): String? = treeUri()?.let { TreeMediaFolder(context, Uri.parse(it)).label() }

    override fun set(treeUri: String) {
        val uri = Uri.parse(treeUri)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The old folder's permission is kept: projects imported earlier still point at files in it.
        prefs.edit().putString(KEY_TREE, treeUri).apply()
    }

    /** The folder as an opener for the importer, or null when none is chosen. */
    fun folder(): MediaFolder? = treeUri()?.let { TreeMediaFolder(context, Uri.parse(it)) }

    override fun summary(): LayoutSummary? {
        val chosen = folder() ?: return null
        return try {
            MediaLayout.describe(chosen)
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the media folder: ${e.message}")
            null
        }
    }

    override fun backupsPickerUri(): String? {
        val chosen = folder() ?: return null
        return try {
            val created = ArrayList<MediaFolder>()
            val backups = MediaLayout.path(chosen, MediaLayout.PROJECT_BACKUPS).ensure(created)
            backupsCreated = created
            (backups as? TreeMediaFolder)?.documentUri?.toString()
        } catch (e: IOException) {
            // The picker still works, it just opens where it did last.
            Log.w(TAG, "cannot prepare the backups folder: ${e.message}")
            null
        }
    }

    override fun backupsPickerDone(saved: Boolean) {
        val created = backupsCreated
        backupsCreated = emptyList()
        if (!saved) MediaLayout.discardEmpty(created)
    }

    private companion object {
        const val TAG = "MediaFolder"
        const val FILE = "media_folder"
        const val KEY_TREE = "tree_uri"
    }
}
