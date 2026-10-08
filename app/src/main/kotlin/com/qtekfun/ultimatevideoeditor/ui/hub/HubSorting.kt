package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectSummary

/** The key the project list is ordered by. [defaultAscending] is the direction that reads naturally for it. */
enum class ProjectSort(val label: String, val defaultAscending: Boolean) {
    LAST_EDITED("Last edited", false),
    NAME("Name", true),
    SIZE("Size", false),
    LENGTH("Length", false),
}

/** How the projects are laid out. */
enum class HubViewMode(val label: String) {
    LIST("List"),
    GRID("Grid"),
}

/** Pure ordering of the project list; no Android, so every key and direction is tested on the JVM. */
object ProjectSorting {
    /**
     * [projects] ordered by [key]. Ties keep their incoming order in both directions (the sort is stable and the
     * descending order reverses the comparison, not the result). [sizes] maps a project id to its bytes on disk; unknown is 0.
     */
    fun sort(projects: List<ProjectSummary>, key: ProjectSort, ascending: Boolean, sizes: Map<String, Long>): List<ProjectSummary> {
        val ascendingOrder: Comparator<ProjectSummary> = when (key) {
            ProjectSort.LAST_EDITED -> compareBy { it.lastModifiedMillis }
            ProjectSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ProjectSort.SIZE -> compareBy { sizes[it.id] ?: 0L }
            ProjectSort.LENGTH -> compareBy { lengthMicros(it) }
        }
        return projects.sortedWith(if (ascending) ascendingOrder else ascendingOrder.reversed())
    }

    /** Length in microseconds, so projects of different frame rates compare by what they last, not by frame count. */
    internal fun lengthMicros(project: ProjectSummary): Long {
        val s = project.settings
        if (s.fpsNum <= 0) return 0L
        return project.durationFrames.coerceAtLeast(0L) * 1_000_000L * s.fpsDen / s.fpsNum
    }
}
