package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ColorSpaceNames
import com.qtekfun.ultimatevideoeditor.data.ProjectOverview
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.ui.about.AboutController

/** The words the project rows, posters and cards show; pure so they are tested without a screen. */
internal object HubFormat {
    /** "1080p · 30 fps · SDR". */
    fun formatLine(project: ProjectSummary): String {
        val s = project.settings
        return "${resolutionShortName(s.width, s.height)} · ${formatFps(s.fpsNum, s.fpsDen)} fps · ${colorSpaceShortName(s.colorSpace)}"
    }

    /** "1080p · 30 fps", the line of the Continue card (the colour has its own chip). */
    fun sizeLine(project: ProjectSummary): String {
        val s = project.settings
        return "${resolutionShortName(s.width, s.height)} · ${formatFps(s.fpsNum, s.fpsDen)} fps"
    }

    fun isHdr(project: ProjectSummary): Boolean = project.settings.colorSpace.let { it != ColorSpaceNames.SDR && it.isNotBlank() }

    /** The length as "0:42", or "Empty" for a project without clips. */
    fun length(project: ProjectSummary): String = if (project.durationFrames > 0) {
        ProjectOverview.formatDuration(project.durationFrames, project.settings.fpsNum, project.settings.fpsDen)
    } else {
        "Empty"
    }

    /** "1 missing", "3 missing"; null when nothing is known to be missing. */
    fun missing(project: ProjectSummary): String? = project.missingMedia.takeIf { it > 0 }?.let { "$it missing" }

    fun bytes(bytes: Long?): String? = bytes?.let { AboutController.formatBytes(it) }

    /**
     * "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago", and the date from [absolute] after a week.
     * A time in the future (a clock that moved back) reads as "Just now".
     */
    fun relativeDate(thenMillis: Long, nowMillis: Long, absolute: (Long) -> String): String {
        val diff = (nowMillis - thenMillis).coerceAtLeast(0L)
        val minutes = diff / MINUTE
        val hours = diff / HOUR
        val days = diff / DAY
        return when {
            minutes < 1 -> "Just now"
            hours < 1 -> "$minutes min ago"
            days < 1 -> "$hours h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> absolute(thenMillis)
        }
    }

    /** One line for the storage card: "3 projects". */
    fun projectCount(count: Int): String = if (count == 1) "1 project" else "$count projects"

    const val FOOTAGE_HINT = "Your footage is not counted: media stay where they are and projects only point to them."

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
