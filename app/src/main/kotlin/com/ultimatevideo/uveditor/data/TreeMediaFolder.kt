package com.ultimatevideo.uveditor.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.ultimatevideo.uveditor.data.interchange.MediaFolder
import com.ultimatevideo.uveditor.data.interchange.MediaFolderSettings
import com.ultimatevideo.uveditor.data.interchange.MediaTarget
import java.io.IOException
import java.io.OutputStream

/** A folder picked with the system folder picker; files are created and read through the document provider. */
class TreeMediaFolder(context: Context, private val tree: Uri) : MediaFolder {
    private val resolver = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)

    override fun fileNames(): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val cursor = try {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        } catch (e: SecurityException) {
            throw IOException("access to the media folder was revoked", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the media folder is not available", e)
        } ?: throw IOException("the media folder is not available")
        return cursor.use {
            val names = HashSet<String>()
            while (it.moveToNext()) it.getString(0)?.let(names::add)
            names
        }
    }

    override fun create(name: String, mimeType: String): MediaTarget {
        val doc = try {
            DocumentsContract.createDocument(resolver, root, mimeType, name)
        } catch (e: SecurityException) {
            throw IOException("access to the media folder was revoked", e)
        } catch (e: IllegalStateException) {
            throw IOException("the media folder refused the file $name", e)
        } ?: throw IOException("the media folder could not create $name")
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

            override fun delete(): Boolean = try {
                DocumentsContract.deleteDocument(resolver, doc)
            } catch (e: IOException) {
                Log.w(TAG, "could not remove the partial file $name: ${e.message}")
                false
            }
        }
    }

    /** The name of the folder, or null when it cannot be read. */
    fun label(): String? = try {
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
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
    }
}

/** The chosen folder lives in preferences; the app keeps read and write permission to it across restarts. */
class PreferencesMediaFolderSettings(private val context: Context) : MediaFolderSettings {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    private companion object {
        const val FILE = "media_folder"
        const val KEY_TREE = "tree_uri"
    }
}
