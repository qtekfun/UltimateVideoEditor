package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.ColorSpaceNames
import com.qtekfun.ultimatevideoeditor.data.ProjectOverview
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.ui.about.AboutController

/** The words the project rows, posters and cards show; pure so they are tested without a screen. */
internal object HubFormat {
    /** "1080p · 30 fps · SDR". */
    fun formatLine(project: ProjectSummary): UiText {
        val s = project.settings
        return UiText.join(" · ", UiText.Raw(resolutionShortName(s.width, s.height)), fps(project), UiText.Raw(colorSpaceShortName(s.colorSpace)))
    }

    /** "1080p · 30 fps", the line of the Continue card (the colour has its own chip). */
    fun sizeLine(project: ProjectSummary): UiText {
        val s = project.settings
        return UiText.join(" · ", UiText.Raw(resolutionShortName(s.width, s.height)), fps(project))
    }

    private fun fps(project: ProjectSummary): UiText =
        UiText.res(R.string.fps_value, formatFps(project.settings.fpsNum, project.settings.fpsDen))

    fun isHdr(project: ProjectSummary): Boolean = project.settings.colorSpace.let { it != ColorSpaceNames.SDR && it.isNotBlank() }

    /** The length as "0:42", or "Empty" for a project without clips. */
    fun length(project: ProjectSummary): UiText = if (project.durationFrames > 0) {
        UiText.Raw(ProjectOverview.formatDuration(project.durationFrames, project.settings.fpsNum, project.settings.fpsDen))
    } else {
        UiText.res(R.string.hub_length_empty)
    }

    /** "1 missing", "3 missing"; null when nothing is known to be missing. */
    fun missing(project: ProjectSummary): UiText? = project.missingMedia.takeIf { it > 0 }?.let { UiText.plural(R.plurals.hub_missing_count, it) }

    fun bytes(bytes: Long?): String? = bytes?.let { AboutController.formatBytes(it) }

    /**
     * "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago", and the date from [absolute] after a week.
     * A time in the future (a clock that moved back) reads as "Just now".
     */
    fun relativeDate(thenMillis: Long, nowMillis: Long, absolute: (Long) -> String): UiText {
        val diff = (nowMillis - thenMillis).coerceAtLeast(0L)
        val minutes = diff / MINUTE
        val hours = diff / HOUR
        val days = diff / DAY
        return when {
            minutes < 1 -> UiText.res(R.string.hub_just_now)
            hours < 1 -> UiText.res(R.string.hub_min_ago, minutes)
            days < 1 -> UiText.res(R.string.hub_hours_ago, hours)
            days == 1L -> UiText.res(R.string.hub_yesterday)
            days < 7 -> UiText.plural(R.plurals.hub_days_ago, days.toInt())
            else -> UiText.Raw(absolute(thenMillis))
        }
    }

    /** One line for the storage card: "3 projects". */
    fun projectCount(count: Int): UiText = UiText.plural(R.plurals.hub_project_count, count)

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
