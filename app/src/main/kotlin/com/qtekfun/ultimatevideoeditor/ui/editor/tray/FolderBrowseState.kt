package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.data.folders.FolderEntry
import com.qtekfun.ultimatevideoeditor.data.folders.FolderPage
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind

/** Which kind of file the folder view shows. */
enum class FolderFilter {
    ALL,
    VIDEO,
    PHOTO,
    AUDIO,
    ;

    fun accepts(kind: RelinkKind?): Boolean = when (this) {
        ALL -> true
        VIDEO -> kind == RelinkKind.VIDEO
        PHOTO -> kind == RelinkKind.IMAGE
        AUDIO -> kind == RelinkKind.AUDIO
    }
}

/** How the files of a folder are ordered; folders are always by name. */
enum class FolderSort {
    NAME,
    NEWEST,
    LARGEST,
    ;

    /** The next way, for a button that cycles through them. */
    fun next(): FolderSort = entries[(ordinal + 1) % entries.size]
}

/** One step of the path the person walked: the folder's document id and the name shown for it in the path bar. */
data class Crumb(val documentId: String, val name: String)

/**
 * Where the person is in the folder view: which linked folder ([linkedId], null on the list of linked folders), the path down from
 * its top ([trail], whose first piece is that top), and the filter and search that apply to the folder on show. Pure; every change
 * returns a new state, and the filter and search are forgotten when a folder is opened or left, so a new folder is never shown empty
 * by a search that belonged to the last one.
 */
data class FolderBrowseState(
    val linkedId: String? = null,
    val trail: List<Crumb> = emptyList(),
    val filter: FolderFilter = FolderFilter.ALL,
    val query: String = "",
    val sort: FolderSort = FolderSort.NAME,
    /** The person is choosing several files to add together: a tap numbers a tile instead of adding it. */
    val picking: Boolean = false,
    /** The chosen files' URIs in the order they were chosen. They stay chosen while the person walks into other folders. */
    val picked: List<String> = emptyList(),
) {
    /** The folder on show: the last piece of the path; null on the list of linked folders. */
    val current: Crumb? get() = trail.lastOrNull()

    val atTop: Boolean get() = linkedId == null

    /** Opens a linked folder at its top. */
    fun openLinked(linkedId: String, top: Crumb): FolderBrowseState = copy(linkedId = linkedId, trail = listOf(top), query = "")

    /** Opens a subfolder of the folder on show. Does nothing on the list of linked folders. */
    fun enter(folder: Crumb): FolderBrowseState =
        if (linkedId == null) this else copy(trail = trail + folder, query = "")

    /** One folder up; from the top of a linked folder, back to the list of linked folders. */
    fun up(): FolderBrowseState = when {
        linkedId == null -> this
        trail.size <= 1 -> copy(linkedId = null, trail = emptyList(), query = "")
        else -> copy(trail = trail.dropLast(1), query = "")
    }

    /** Jumps to the piece [index] of the path (0 is the top of the linked folder); a piece that does not exist leaves the state as it is. */
    fun jumpTo(index: Int): FolderBrowseState =
        if (linkedId == null || index !in trail.indices) this else copy(trail = trail.take(index + 1), query = "")

    fun withFilter(filter: FolderFilter): FolderBrowseState = copy(filter = filter)

    /** Turns choosing on or off; leaving it forgets the choice. */
    fun withPicking(on: Boolean): FolderBrowseState = copy(picking = on, picked = if (on) picked else emptyList())

    /** A tap on the file at [uri] while choosing: a chosen file loses its place (the later ones renumber down), others go last. */
    fun togglePicked(uri: String): FolderBrowseState = copy(picked = if (uri in picked) picked - uri else picked + uri)

    /** The number shown on a tile (1 for the first chosen), or 0 when it is not chosen. */
    fun pickNumber(uri: String): Int = picked.indexOf(uri) + 1

    /** What the view shows once the choice was added: choosing is over. */
    fun afterAdding(): FolderBrowseState = withPicking(false)

    fun withQuery(query: String): FolderBrowseState = copy(query = query)

    fun withSort(sort: FolderSort): FolderBrowseState = copy(sort = sort)

    /** [page] as it is shown: the folders that match the search and the files that match the search and the filter. */
    fun visible(page: FolderPage): FolderPage {
        val needle = query.trim()
        fun matches(e: FolderEntry) = needle.isEmpty() || e.name.contains(needle, ignoreCase = true)
        val files = page.files.filter { filter.accepts(it.kind) && matches(it) }
        return FolderPage(
            folders = page.folders.filter(::matches),
            // The page arrives in name order. A file with no date or size goes last, and equal ones keep that order.
            files = when (sort) {
                FolderSort.NAME -> files
                FolderSort.NEWEST -> files.sortedByDescending { it.modifiedMillis ?: Long.MIN_VALUE }
                FolderSort.LARGEST -> files.sortedByDescending { it.sizeBytes ?: Long.MIN_VALUE }
            },
        )
    }
}
