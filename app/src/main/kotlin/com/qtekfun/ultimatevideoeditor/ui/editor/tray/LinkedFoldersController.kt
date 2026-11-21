package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatevideoeditor.data.folders.BrowsePosition
import com.qtekfun.ultimatevideoeditor.data.folders.BrowsePositionStore
import com.qtekfun.ultimatevideoeditor.data.folders.BrowseProblem
import com.qtekfun.ultimatevideoeditor.data.folders.NoBrowsePositionStore
import com.qtekfun.ultimatevideoeditor.data.folders.FolderBrowseException
import com.qtekfun.ultimatevideoeditor.data.folders.FolderBrowser
import com.qtekfun.ultimatevideoeditor.data.folders.FolderEntry
import com.qtekfun.ultimatevideoeditor.data.folders.FolderPage
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFolder
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFolders
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFoldersStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the folder on show is doing: being read, read, or not readable. */
sealed interface FolderLoad {
    data object Idle : FolderLoad

    data object Loading : FolderLoad

    data class Loaded(val page: FolderPage) : FolderLoad

    data class Failed(val problem: BrowseProblem) : FolderLoad
}

/**
 * The folders the user linked and where they are in them. [folders] is kept in the [store]; [browse] is pure navigation state;
 * [load] is the folder on show. A folder is read when it is opened and kept in memory until [refresh], so going back is instant. Compose
 * state, so the tab updates by reading it, and tested on the JVM with a fake [FolderBrowser].
 *
 * Linking asks the platform to keep read access to the folder ([retainAccess]); if it refuses, the folder is not linked (it would be gone
 * after a restart) and [linkRefused] says so. Unlinking only forgets the folder: the permission is left to the housekeeping that keeps
 * every permission a project still needs, so a clip already used from the folder keeps playing.
 */
@Stable
class LinkedFoldersController(
    private val store: LinkedFoldersStore,
    private val browser: FolderBrowser,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val retainAccess: (String) -> Boolean = { true },
    private val positions: BrowsePositionStore = NoBrowsePositionStore,
) {
    var folders: List<LinkedFolder> by mutableStateOf(store.load())
        private set

    var browse: FolderBrowseState by mutableStateOf(FolderBrowseState())
        private set

    var load: FolderLoad by mutableStateOf(FolderLoad.Idle)
        private set

    /** True after the last attempt to link a folder was refused by the platform; cleared by the next action. */
    var linkRefused: Boolean by mutableStateOf(false)
        private set

    private val cache = HashMap<String, FolderPage>()
    private var job: Job? = null

    init {
        // The tab opens where it was left, if that folder is still linked.
        positions.load()?.takeIf { saved -> folders.any { it.id == saved.linkedId } }?.let { saved ->
            val crumbs = saved.trail.map { (id, name) -> Crumb(id, name) }
            browse = browse.openLinked(saved.linkedId, crumbs.first()).let { base -> crumbs.drop(1).fold(base) { acc, c -> acc.enter(c) } }
            reload()
        }
    }

    /** The linked folder being browsed, or null on the list of linked folders. */
    val current: LinkedFolder? get() = browse.linkedId?.let { id -> folders.firstOrNull { it.id == id } }

    /** What the tab shows for the folder on show, after the filter and the search. */
    val visible: FolderPage? get() = (load as? FolderLoad.Loaded)?.page?.let(browse::visible)

    /** Links the folder the picker returned. Linking one that is already linked is harmless and opens it. */
    fun link(treeUri: String) {
        linkRefused = false
        val existing = folders.firstOrNull { LinkedFolders.sameTree(it.treeUri, treeUri) }
        if (existing != null) {
            // Choosing it again is also how access is given back after it was removed.
            retainAccess(treeUri)
            cache.keys.removeAll { it.startsWith("${existing.treeUri}|") }
            open(existing)
            return
        }
        if (browser.rootId(treeUri) == null) {
            linkRefused = true
            return
        }
        if (!retainAccess(treeUri)) {
            linkRefused = true
            return
        }
        val folder = LinkedFolder(newId(), treeUri, LinkedFolders.defaultLabel(treeUri))
        folders = LinkedFolders.add(folders, folder)
        store.save(folders)
        open(folder)
    }

    fun unlink(id: String) {
        val tree = folders.firstOrNull { it.id == id }?.treeUri ?: return
        folders = LinkedFolders.remove(folders, id)
        store.save(folders)
        cache.keys.removeAll { it.startsWith("$tree|") }
        if (browse.linkedId == id) {
            job?.cancel()
            browse = browse.copy(linkedId = null, trail = emptyList(), query = "")
            load = FolderLoad.Idle
            remember()
        }
    }

    fun rename(id: String, label: String) {
        val updated = LinkedFolders.rename(folders, id, label)
        if (updated == folders) return
        folders = updated
        store.save(folders)
        // The path bar shows the name of the top; keep it in step.
        if (browse.linkedId == id) browse = browse.copy(trail = browse.trail.mapIndexed { i, c -> if (i == 0) c.copy(name = updated.first { it.id == id }.label) else c })
    }

    /** Opens a linked folder at its top. */
    fun open(folder: LinkedFolder) {
        linkRefused = false
        val rootId = browser.rootId(folder.treeUri)
        if (rootId == null) {
            browse = browse.openLinked(folder.id, Crumb("", folder.label))
            load = FolderLoad.Failed(BrowseProblem.UNAVAILABLE)
            return
        }
        browse = browse.openLinked(folder.id, Crumb(rootId, folder.label))
        reload()
    }

    fun enter(entry: FolderEntry) {
        if (!entry.isFolder) return
        browse = browse.enter(Crumb(entry.documentId, entry.name))
        reload()
    }

    fun up() {
        browse = browse.up()
        reload()
    }

    fun jumpTo(index: Int) {
        val next = browse.jumpTo(index)
        if (next === browse) return
        browse = next
        reload()
    }

    /** Reads the folder on show again, for files that were added or removed outside the app. */
    fun refresh() {
        cacheKey()?.let(cache::remove)
        reload()
    }

    fun setSort(sort: FolderSort) {
        browse = browse.withSort(sort)
    }

    fun setFilter(filter: FolderFilter) {
        browse = browse.withFilter(filter)
    }

    fun setQuery(query: String) {
        browse = browse.withQuery(query)
    }

    fun setPicking(on: Boolean) {
        browse = browse.withPicking(on)
    }

    fun togglePicked(uri: String) {
        browse = browse.togglePicked(uri)
    }

    fun clearPicked() {
        browse = browse.withPicking(false).withPicking(true)
    }

    fun finishPicking() {
        browse = browse.afterAdding()
    }

    /** Writes down where the person is, so the tab opens there next time. */
    private fun remember() {
        val id = browse.linkedId
        positions.save(if (id == null) null else BrowsePosition(id, browse.trail.map { it.documentId to it.name }))
    }

    private fun cacheKey(): String? {
        val folder = current ?: return null
        val at = browse.current ?: return null
        return "${folder.treeUri}|${at.documentId}"
    }

    private fun reload() {
        remember()
        job?.cancel()
        val folder = current
        val at = browse.current
        if (folder == null || at == null) {
            load = FolderLoad.Idle
            return
        }
        val key = "${folder.treeUri}|${at.documentId}"
        cache[key]?.let {
            load = FolderLoad.Loaded(it)
            return
        }
        load = FolderLoad.Loading
        job = scope.launch {
            try {
                val page = browser.list(folder.treeUri, at.documentId)
                cache[key] = page
                load = FolderLoad.Loaded(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FolderBrowseException) {
                load = FolderLoad.Failed(e.problem)
            }
        }
    }
}
