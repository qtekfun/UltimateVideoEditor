package com.qtekfun.ultimatevideoeditor.data.folders

/** A folder the user linked: [treeUri] is the document-tree URI the picker returned, [label] the name shown for it. */
data class LinkedFolder(val id: String, val treeUri: String, val label: String)

/** Where the linked folders are kept; the list belongs to the app, not to a project. */
interface LinkedFoldersStore {
    fun load(): List<LinkedFolder>

    fun save(folders: List<LinkedFolder>)
}

/** Remembers nothing; for tests and as the fallback when preferences are unavailable. */
object NoLinkedFoldersStore : LinkedFoldersStore {
    override fun load(): List<LinkedFolder> = emptyList()

    override fun save(folders: List<LinkedFolder>) = Unit
}

/** The pure operations on the list of linked folders. Each returns the new list; none changes its input. */
object LinkedFolders {
    /**
     * [folder] added at the end. A tree that is already linked (the same URI, or the same folder under another URI) is not added
     * twice: the list comes back unchanged, so linking a folder again is harmless.
     */
    fun add(folders: List<LinkedFolder>, folder: LinkedFolder): List<LinkedFolder> =
        if (folders.any { sameTree(it.treeUri, folder.treeUri) || it.id == folder.id }) folders else folders + folder

    fun remove(folders: List<LinkedFolder>, id: String): List<LinkedFolder> = folders.filterNot { it.id == id }

    /** A blank label keeps the old one. */
    fun rename(folders: List<LinkedFolder>, id: String, label: String): List<LinkedFolder> {
        val clean = label.trim()
        if (clean.isEmpty()) return folders
        return folders.map { if (it.id == id) it.copy(label = clean) else it }
    }

    /** The label a folder gets when the user does not name it: its own name, not the whole path. */
    fun defaultLabel(treeUri: String): String =
        DocumentKey.treeIdOf(treeUri)?.let(DocumentKey::lastNameOf) ?: treeUri.substringAfterLast('/')

    /** Whether two tree URIs name the same folder of the same provider. */
    fun sameTree(a: String, b: String): Boolean {
        if (a == b) return true
        val keyA = DocumentKey.treeIdOf(a)
        val keyB = DocumentKey.treeIdOf(b)
        return keyA != null && keyA == keyB && authorityOf(a) == authorityOf(b)
    }

    /** The tree URIs, for the permissions that must be kept whatever the projects use. */
    fun treeUris(folders: List<LinkedFolder>): Set<String> = folders.mapTo(LinkedHashSet()) { it.treeUri }

    private fun authorityOf(uri: String) = uri.removePrefix("content://").substringBefore('/')
}

/**
 * The list as text, one folder per line: `id TAB tree-uri TAB label`, with `%`, tab and line breaks written as `%25`, `%09`, `%0A`,
 * `%0D`. Reading is forgiving: a bad line is skipped, so a file from another version never breaks the editor.
 */
object LinkedFoldersCodec {
    fun encode(folders: List<LinkedFolder>): String =
        folders.joinToString("\n") { listOf(it.id, it.treeUri, it.label).joinToString("\t") { field -> escape(field) } }

    fun decode(text: String?): List<LinkedFolder> {
        if (text.isNullOrBlank()) return emptyList()
        var out = emptyList<LinkedFolder>()
        for (line in text.split('\n')) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val folder = LinkedFolder(unescape(parts[0]), unescape(parts[1]), unescape(parts[2]))
            if (folder.id.isEmpty() || !folder.treeUri.startsWith("content://")) continue
            out = LinkedFolders.add(out, folder)
        }
        return out
    }

    private fun escape(text: String) = text.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

    private fun unescape(text: String) = text.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
}
