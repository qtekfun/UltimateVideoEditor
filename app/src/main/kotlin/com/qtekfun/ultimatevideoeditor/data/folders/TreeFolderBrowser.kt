package com.qtekfun.ultimatevideoeditor.data.folders

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanException
import com.qtekfun.ultimatevideoeditor.data.relink.TreeChildren
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browses a Storage Access Framework tree one folder at a time. Read-only. */
class TreeFolderBrowser(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FolderBrowser {

    override fun rootId(treeUri: String): String? = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun list(treeUri: String, documentId: String): FolderPage = withContext(ioDispatcher) {
        val tree = Uri.parse(treeUri)
        val children = try {
            TreeChildren.list(context, tree, documentId)
        } catch (e: FolderScanException) {
            // The scanner words its failures for the user; the browser only needs to know which of the two it was.
            val lost = e.cause is SecurityException
            throw FolderBrowseException(if (lost) BrowseProblem.ACCESS_LOST else BrowseProblem.UNAVAILABLE, e)
        }
        FolderPages.build(
            raw = children.map { RawEntry(it.documentId, it.name, it.mime, it.isDirectory, it.size, it.modifiedMillis) },
            uriOf = { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() },
        )
    }
}
