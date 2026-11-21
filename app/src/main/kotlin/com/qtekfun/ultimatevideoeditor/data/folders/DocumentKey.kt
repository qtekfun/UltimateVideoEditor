package com.qtekfun.ultimatevideoeditor.data.folders

import java.net.URLDecoder

/**
 * The identity of a file in a document provider: the provider's authority and the file's document id. The same file has
 * different URIs depending on how it was reached (`content://a/document/x` from the file picker, `content://a/tree/t/document/x`
 * from a folder the user linked), but one key. Pure string handling, so it is tested without Android.
 */
data class DocumentKey(val authority: String, val documentId: String) {
    companion object {
        private const val SCHEME = "content://"

        /** The key of a document URI (with or without a tree part); null for anything else, such as a `file://` URI or a bare tree. */
        fun of(uri: String): DocumentKey? {
            if (!uri.startsWith(SCHEME)) return null
            val rest = uri.removePrefix(SCHEME).substringBefore('?').substringBefore('#')
            val authority = rest.substringBefore('/')
            val segments = rest.substringAfter('/', "").split('/')
            val at = segments.lastIndexOf("document")
            if (authority.isEmpty() || at < 0 || at + 1 >= segments.size) return null
            val id = decode(segments[at + 1])
            return if (id.isEmpty()) null else DocumentKey(authority, id)
        }

        /** True for `content://<authority>/tree/<id>/document/<id>`: readable through the permission held on the tree. */
        fun isTreeDocument(uri: String): Boolean {
            if (!uri.startsWith(SCHEME)) return false
            val segments = uri.removePrefix(SCHEME).substringBefore('?').substringBefore('#').split('/').drop(1)
            val tree = segments.indexOf("tree")
            val document = segments.lastIndexOf("document")
            return tree == 0 && document > tree + 1 && document + 1 < segments.size
        }

        /** The tree URI a tree document lives under, `content://<authority>/tree/<id>`; null when [uri] is not a tree document. */
        fun treeOf(uri: String): String? {
            if (!isTreeDocument(uri)) return null
            val authority = uri.removePrefix(SCHEME).substringBefore('/')
            val segments = uri.removePrefix(SCHEME).substringBefore('?').substringBefore('#').split('/').drop(1)
            return "$SCHEME$authority/tree/${segments[1]}"
        }

        /** The last piece of a tree's document id (`primary:Movies/Trips` gives `Trips`), for a default label. */
        fun lastNameOf(documentId: String): String =
            documentId.substringAfter(':').trimEnd('/').substringAfterLast('/').ifEmpty { documentId.trimEnd(':') }

        /** A tree URI's own document id, decoded; null when [treeUri] is not a tree URI. */
        fun treeIdOf(treeUri: String): String? {
            if (!treeUri.startsWith(SCHEME)) return null
            val segments = treeUri.removePrefix(SCHEME).substringBefore('?').substringBefore('#').split('/').drop(1)
            if (segments.size < 2 || segments[0] != "tree") return null
            return decode(segments[1]).ifEmpty { null }
        }

        // Uri.encode writes a space as %20 and a plus as %2B, so a plus in the text is never a space.
        private fun decode(piece: String): String = try {
            URLDecoder.decode(piece.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            piece
        }
    }
}
