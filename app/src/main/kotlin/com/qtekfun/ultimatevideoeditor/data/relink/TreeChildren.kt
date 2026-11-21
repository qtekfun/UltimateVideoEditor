package com.qtekfun.ultimatevideoeditor.data.relink

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** One entry of a folder in a document tree, as the provider reports it. */
internal class TreeChild(
    val documentId: String,
    val name: String,
    val mime: String?,
    val size: Long?,
    val modifiedMillis: Long?,
) {
    val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/** Reads one level of a Storage Access Framework tree. Shared by the recursive scan (relink) and the folder browser. */
internal object TreeChildren {
    /** The entries directly inside [parentId] of [tree]. @throws FolderScanException when access was removed or the folder is gone. */
    fun list(context: Context, tree: Uri, parentId: String): List<TreeChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(uri, columns, null, null, null)
        } catch (e: SecurityException) {
            throw FolderScanException("Access to the folder was removed", e)
        } catch (e: IllegalArgumentException) {
            throw FolderScanException("The folder is not available", e)
        } ?: throw FolderScanException("The folder is not available")
        return cursor.use {
            val out = ArrayList<TreeChild>()
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                out += TreeChild(id, name, it.getString(2), if (it.isNull(3)) null else it.getLong(3), if (it.isNull(4)) null else it.getLong(4))
            }
            out
        }
    }
}
