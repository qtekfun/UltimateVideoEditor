package com.qtekfun.ultimatevideoeditor.data.folders

import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind

/**
 * One entry of a linked folder. [uri] opens the entry through the permission held on the linked tree; a folder's [documentId]
 * is what [FolderBrowser.list] takes to open it. [kind] is null for a folder and for a file that is not media (those are not listed).
 */
data class FolderEntry(
    val documentId: String,
    val name: String,
    val isFolder: Boolean,
    val kind: RelinkKind?,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
    val uri: String,
)

/** What one folder holds, sorted for showing: [folders] and [files] each in natural name order. Files that are not media are left out. */
data class FolderPage(val folders: List<FolderEntry>, val files: List<FolderEntry>) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}

/** Why a folder could not be listed. */
enum class BrowseProblem {
    /** The permission on the linked folder was removed, or the volume is gone. The user can grant access again or unlink. */
    ACCESS_LOST,

    /** The provider could not answer right now. */
    UNAVAILABLE,
}

class FolderBrowseException(val problem: BrowseProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * Lists one level of a linked folder, lazily: a folder is read when it is opened, never the whole tree. An interface so the browser
 * is tested without the Storage Access Framework; [TreeFolderBrowser] is the Android implementation.
 */
interface FolderBrowser {
    /** The document id of the folder the tree URI points at (its top), or null when [treeUri] is not a tree URI. */
    fun rootId(treeUri: String): String?

    /** The entries of [documentId] in [treeUri]. Does its I/O off the caller's thread. @throws FolderBrowseException */
    suspend fun list(treeUri: String, documentId: String): FolderPage
}

/** Used where no folder access is wired: every folder reports that access is lost. */
object NoFolderBrowser : FolderBrowser {
    override fun rootId(treeUri: String): String? = null

    override suspend fun list(treeUri: String, documentId: String): FolderPage = throw FolderBrowseException(BrowseProblem.UNAVAILABLE)
}

/** The raw facts about an entry, before it is sorted and filtered; [mime] `null` when the provider gave none. */
class RawEntry(
    val documentId: String,
    val name: String,
    val mime: String?,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

/** Sorting and filtering of a folder's entries; pure. */
object FolderPages {
    /**
     * Folders first, then media files, each in natural order ignoring case (`clip2` before `clip10`). Entries whose name starts with a dot
     * are hidden, as are files that are not video, audio or pictures. [uriOf] builds the URI of an entry from its document id.
     */
    fun build(raw: List<RawEntry>, uriOf: (String) -> String): FolderPage {
        val visible = raw.filterNot { it.name.startsWith('.') }
        val folders = visible.filter { it.isDirectory }.map { entry(it, null, uriOf) }
        val files = visible.filter { !it.isDirectory }.mapNotNull { e ->
            RelinkKind.fromFile(e.mime, e.name)?.let { entry(e, it, uriOf) }
        }
        return FolderPage(folders.sortedWith(NATURAL), files.sortedWith(NATURAL))
    }

    private fun entry(e: RawEntry, kind: RelinkKind?, uriOf: (String) -> String) =
        FolderEntry(e.documentId, e.name, e.isDirectory, kind, e.sizeBytes, e.modifiedMillis, uriOf(e.documentId))

    /** Names compared piece by piece: runs of digits by their number, everything else without regard to case. */
    val NATURAL: Comparator<FolderEntry> = Comparator { a, b -> compareNatural(a.name, b.name) }

    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val x = a.substring(i, ei).trimStart('0')
                val y = b.substring(j, ej).trimStart('0')
                // A longer number (once the leading zeros are gone) is the bigger one; same length compares digit by digit.
                val byValue = if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
                if (byValue != 0) return byValue
                i = ei
                j = ej
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val byLength = (a.length - i).compareTo(b.length - j)
        // Two names equal in every way that matters keep a fixed order, so a list never shuffles between reads.
        return if (byLength != 0) byLength else a.compareTo(b)
    }
}
