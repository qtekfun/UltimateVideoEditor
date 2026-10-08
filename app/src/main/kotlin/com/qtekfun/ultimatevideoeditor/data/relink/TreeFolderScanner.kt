package com.qtekfun.ultimatevideoeditor.data.relink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.qtekfun.ultimatevideoeditor.domain.relink.FolderFile
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads a Storage Access Framework tree (the folder picked with the document-tree picker). Read-only. */
class TreeFolderScanner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderScanner {

    override fun retainAccess(treeUri: String): Boolean = try {
        context.contentResolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Cannot keep access to $treeUri: ${e.message}")
        false
    }

    override suspend fun scan(treeUri: String, limits: ScanLimits, onProgress: (ScanProgress) -> Unit): FolderListing =
        withContext(ioDispatcher) {
            val tree = Uri.parse(treeUri)
            val rootId = try {
                DocumentsContract.getTreeDocumentId(tree)
            } catch (e: IllegalArgumentException) {
                throw FolderScanException("That is not a folder", e)
            }
            val files = ArrayList<FolderFile>()
            var folders = 0
            var truncated = false
            // Breadth first, so a limit cuts the deepest levels and not whole top-level folders.
            val queue = ArrayDeque<Pair<String, List<String>>>()
            queue.addLast(rootId to emptyList())
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (id, trail) = queue.removeFirst()
                folders++
                for (child in children(tree, id)) {
                    if (child.isDirectory) {
                        if (trail.size + 1 >= limits.maxDepth || folders + queue.size >= limits.maxFolders) truncated = true
                        else queue.addLast(child.documentId to trail + child.name)
                    } else {
                        val kind = RelinkKind.fromFile(child.mime, child.name) ?: continue
                        if (files.size >= limits.maxFiles) {
                            truncated = true
                            continue
                        }
                        files += FolderFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId).toString(),
                            path = trail + child.name,
                            kind = kind,
                            sizeBytes = child.size,
                        )
                    }
                }
                onProgress(ScanProgress(files.size, folders))
            }
            FolderListing(files, folders, truncated)
        }

    private class Child(val documentId: String, val name: String, val mime: String?, val size: Long?) {
        val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = try {
            context.contentResolver.query(uri, columns, null, null, null)
        } catch (e: SecurityException) {
            throw FolderScanException("Access to the folder was removed", e)
        } catch (e: IllegalArgumentException) {
            throw FolderScanException("The folder is not available", e)
        } ?: throw FolderScanException("The folder is not available")
        return cursor.use {
            val out = ArrayList<Child>()
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                out += Child(id, name, it.getString(2), if (it.isNull(3)) null else it.getLong(3))
            }
            out
        }
    }

    private companion object {
        const val TAG = "FolderScanner"
    }
}
