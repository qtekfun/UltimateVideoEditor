package com.qtekfun.ultimatevideoeditor.data.interchange

import java.io.IOException

/**
 * Where the app writes inside the folder the user chose: its own `ultimateVE` subfolder, never loose files in the folder
 * itself (DECISIONS.md, "Media folder layout"). Every folder is created on first use, so an empty one never appears.
 */
object MediaLayout {
    /** The root, exactly this spelling; reused (not nested) when the chosen folder is already called so or already holds one. */
    const val ROOT = "ultimateVE"

    /** Footage unpacked from LumaFusion packages, one subfolder per imported project. */
    const val MEDIA = "Media"

    /** Where the document picker opens for `.uvbundle` exports. */
    const val PROJECT_BACKUPS = "Project-Backups"

    /** The LumaFusion folders this app does not write (LibraryMedia, ReversedMedia, UserMedia): see DECISIONS.md. */
    val NOT_USED = listOf("LibraryMedia", "ReversedMedia", "UserMedia")

    /** The folder [below] (names from the root down) of [chosen], which may not exist yet. */
    fun path(chosen: MediaFolder, vararg below: String): FolderPath {
        val names = ArrayList<String>()
        if (!chosen.name.equals(ROOT, ignoreCase = true)) names += ROOT
        names += below
        return FolderPath(chosen, names)
    }

    /** The name of the subfolder for the footage of the project [projectName], free among [taken]. */
    fun projectFolderName(projectName: String, taken: Collection<String>): String {
        val cleaned = MediaFileNames.clean(projectName).let { if (it == "media") "Project" else it }
        return MediaFileNames.unique(cleaned, taken)
    }

    /**
     * Removes the folders in [created] (outermost first) that are still empty, innermost first; never one that holds
     * anything. What stopped it is passed to [report], so a folder that stays is never a silent failure.
     */
    fun discardEmpty(created: List<MediaFolder>, report: (String) -> Unit = {}) {
        for (folder in created.asReversed()) {
            val label = folder.name
            val left = try {
                folder.children().size
            } catch (e: IOException) {
                report("cannot list $label: ${e.message}")
                return
            }
            if (left > 0) {
                report("$label is kept: it holds $left entries")
                return
            }
            if (folder.delete()) report("$label removed") else {
                report("$label could not be removed")
                return
            }
        }
    }

    /** What the About screen shows for [chosen]: the effective path and what the categories hold. Reads the folder only. */
    fun describe(chosen: MediaFolder): LayoutSummary {
        val chosenName = chosen.name
        val isRoot = chosenName.equals(ROOT, ignoreCase = true)
        val root = if (isRoot) chosen else chosen.findFolder(ROOT)
        val shown = when {
            isRoot -> chosenName ?: ROOT
            chosenName != null -> "$chosenName/$ROOT"
            else -> ROOT
        }
        val categories = ArrayList<CategoryCount>()
        if (root != null) {
            for (name in listOf(MEDIA, PROJECT_BACKUPS)) {
                val folder = root.findFolder(name) ?: continue
                categories += CategoryCount(name, folder.children().size)
            }
        }
        // Files from imports made before the subfolder existed stay where they were: tell how many, never touch them.
        val loose = if (isRoot) 0 else chosen.children().count { !it.isFolder }
        return LayoutSummary(shown, root != null, categories, loose)
    }
}

/** A folder below a chosen one, resolved on demand: [find] never creates, [ensure] creates what is missing. */
class FolderPath internal constructor(private val base: MediaFolder, private val names: List<String>) {
    /** The folder when every part exists, or null. */
    @Throws(IOException::class)
    fun find(): MediaFolder? {
        var current = base
        for (name in names) current = current.findFolder(name) ?: return null
        return current
    }

    /** The folder, creating the missing parts; each one created is added to [created] (outermost first). */
    @Throws(IOException::class)
    fun ensure(created: MutableList<MediaFolder>): MediaFolder {
        var current = base
        for (name in names) {
            current = current.findFolder(name) ?: current.createFolder(name).also { created += it }
        }
        return current
    }
}

/** What the media folder holds, for display. [path] is `<folder>/ultimateVE` (or just the folder when it is the root itself). */
data class LayoutSummary(val path: String, val rootExists: Boolean, val categories: List<CategoryCount>, val looseFiles: Int)

/** A category folder that exists and how many entries it holds. */
data class CategoryCount(val name: String, val items: Int)
