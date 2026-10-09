package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleVerification
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.namedList
import java.util.Locale

/**
 * The words of a backup's progress and result; pure, so the dialog, the bar and the notification say the same and it is tested.
 * Sizes are numbers with a unit symbol (B, KB, MB, GB, TB), formatted with the language in use; everything else is a [UiText].
 */
object BundleJobText {
    private const val KIB = 1024.0
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /** "7.4 GB", "512 MB", "300 B". */
    fun bytes(count: Long): String {
        val unit = unitFor(count)
        return amount(count, unit) + " " + UNITS[unit]
    }

    /** "1.8 of 7.4 GB": both in the unit of the total, so the two numbers can be compared at a glance. */
    fun bytesOf(done: Long, total: Long): UiText {
        val unit = unitFor(total)
        return UiText.res(R.string.bundle_bytes_of, amount(done.coerceAtMost(total), unit), amount(total, unit), UNITS[unit])
    }

    private fun unitFor(count: Long): Int {
        var unit = 0
        var value = count.coerceAtLeast(0).toDouble()
        while (value >= KIB && unit < UNITS.lastIndex) {
            value /= KIB
            unit++
        }
        return unit
    }

    private fun amount(count: Long, unit: Int): String {
        val value = count.coerceAtLeast(0).toDouble() / Math.pow(KIB, unit.toDouble())
        return if (unit == 0) "%d".format(Locale.getDefault(), count.coerceAtLeast(0)) else "%.1f".format(Locale.getDefault(), value)
    }

    /** "about 2 min left", "about 40 s left", "about 1 h 5 min left"; rounded so it does not flicker. */
    fun left(ms: Long): UiText {
        val seconds = ms / 1000
        if (seconds < 10) return UiText.res(R.string.time_left_few)
        return when {
            seconds < 60 -> UiText.res(R.string.time_left_seconds, (seconds + 2) / 5 * 5)
            seconds < 3600 -> UiText.res(R.string.time_left_minutes, (seconds + 30) / 60)
            else -> {
                val minutes = (seconds + 30) / 60
                if (minutes % 60 == 0L) UiText.res(R.string.time_left_hours, minutes / 60)
                else UiText.res(R.string.time_left_hours_minutes, minutes / 60, minutes % 60)
            }
        }
    }

    /** "42 MB/s". */
    fun rate(bytesPerSecond: Double): String = bytes(bytesPerSecond.toLong()) + "/s"

    /** What is being written right now: "Packing media 3 of 12: IMG_0014.mov". */
    fun step(progress: BundleProgress): UiText = when (progress.kind) {
        BundleItemKind.MEDIA -> UiText.res(R.string.bundle_step_media, progress.mediaIndex, progress.mediaCount, progress.itemName)
        BundleItemKind.RESOURCE -> UiText.res(R.string.bundle_step_resource, progress.itemName)
        BundleItemKind.THUMBNAIL -> UiText.res(R.string.bundle_step_thumbnail)
        BundleItemKind.PROJECT -> UiText.res(R.string.bundle_step_project)
    }

    /** The same without the file name, for the notification: "Media 3 of 12". */
    fun shortStep(progress: BundleProgress): UiText = when (progress.kind) {
        BundleItemKind.MEDIA -> UiText.res(R.string.bundle_short_media, progress.mediaIndex, progress.mediaCount)
        BundleItemKind.RESOURCE -> UiText.res(R.string.bundle_short_resource)
        BundleItemKind.THUMBNAIL, BundleItemKind.PROJECT -> UiText.res(R.string.bundle_short_project)
    }

    /** "1.8 of 7.4 GB, about 2 min left" (the time left comes when it is known). */
    fun progressLine(progress: BundleProgress): UiText =
        UiText.join(", ", bytesOf(progress.doneBytes, progress.totalBytes), progress.remainingMs?.let { left(it) } ?: UiText.Empty)

    /** "Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, took 4:12)". */
    fun savedLine(fileName: String, result: BundleWriteResult, fileBytes: Long, tookMs: Long): UiText {
        val parts = ArrayList<UiText>()
        parts += UiText.Raw(bytes(fileBytes))
        parts += UiText.plural(R.plurals.count_media_files, result.mediaCopied)
        if (result.lutsIncluded > 0) parts += UiText.plural(R.plurals.count_luts, result.lutsIncluded)
        if (result.fontsIncluded > 0) parts += UiText.plural(R.plurals.count_fonts, result.fontsIncluded)
        parts += UiText.res(R.string.took_in, formatTook(tookMs))
        return UiText.res(R.string.bundle_saved_line, fileName, UiText.join(", ", parts))
    }

    /** What could not go in, or empty. */
    fun skippedLine(result: BundleWriteResult): UiText = UiText.join(
        ". ",
        if (result.mediaSkipped.isNotEmpty()) UiText.res(R.string.bundle_skipped_unreadable, namedList(result.mediaSkipped)) else UiText.Empty,
        if (result.resourcesSkipped.isNotEmpty()) UiText.res(R.string.bundle_skipped_resources, namedList(result.resourcesSkipped)) else UiText.Empty,
    )

    /** The one-line verdict on the saved file, shared by the dialog, the bar and the notification. */
    fun verificationHeadline(verification: BundleVerification?): UiText = when (verification) {
        null -> UiText.Empty
        is BundleVerification.Verified -> UiText.plural(R.plurals.bundle_checked_complete, verification.entryCount)
        is BundleVerification.Warning -> UiText.res(R.string.bundle_warning_headline)
        is BundleVerification.CouldNotVerify -> UiText.res(R.string.bundle_could_not_verify)
        BundleVerification.Skipped -> UiText.res(R.string.bundle_not_checked)
    }

    /** The problems behind a warning, or why the check could not run; empty when there is nothing to add. */
    fun verificationDetail(verification: BundleVerification?): UiText = when (verification) {
        is BundleVerification.Warning -> UiText.join(
            ". ",
            verification.problems.take(4).map { UiText.Raw(it) } +
                if (verification.problems.size > 4) listOf(UiText.res(R.string.bundle_problems_more, verification.problems.size - 4)) else emptyList(),
        )
        is BundleVerification.CouldNotVerify -> UiText.Capitalised(verification.reason)
        else -> UiText.Empty
    }

    /** The reason a backup stopped, from the error that did it: a full disk, a lost permission and so on, never a bare class name. */
    fun failure(error: Throwable): UiText {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        return when {
            chain.any { it is SecurityException } -> UiText.res(R.string.bundle_fail_permission)
            text.contains("ENOSPC", ignoreCase = true) || text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true) -> // i18n-ok: matches the system's own English error text
                UiText.res(R.string.bundle_fail_storage_full)
            chain.any { it is java.io.FileNotFoundException } && !text.contains("could not read") ->
                UiText.res(R.string.bundle_fail_file_open)
            else -> {
                // ProjectError.Io only wraps the cause with the content address of the file; the cause is what the user can act on.
                val top = if (error is ProjectError.Io) error.cause ?: error else error
                val own = top.message?.takeIf { it.isNotBlank() } ?: top.javaClass.simpleName
                val deepest = generateSequence(top) { it.cause }.take(6).mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.lastOrNull()
                val full = if (deepest != null && deepest != own) "$own ($deepest)" else own
                UiText.Capitalised(UiText.Raw(full))
            }
        }
    }
}
