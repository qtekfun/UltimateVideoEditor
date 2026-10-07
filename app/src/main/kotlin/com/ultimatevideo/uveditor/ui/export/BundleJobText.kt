package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind
import com.ultimatevideo.uveditor.data.interchange.BundleVerification
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import java.util.Locale

/** The words of a backup's progress and result; pure, so the dialog, the bar and the notification say the same and it is tested. */
object BundleJobText {
    private const val KIB = 1024.0
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /** "7.4 GB", "512 MB", "300 B". */
    fun bytes(count: Long): String {
        val unit = unitFor(count)
        return amount(count, unit) + " " + UNITS[unit]
    }

    /** "1.8 of 7.4 GB": both in the unit of the total, so the two numbers can be compared at a glance. */
    fun bytesOf(done: Long, total: Long): String {
        val unit = unitFor(total)
        return "${amount(done.coerceAtMost(total), unit)} of ${amount(total, unit)} ${UNITS[unit]}"
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
        return if (unit == 0) "%d".format(Locale.ROOT, count.coerceAtLeast(0)) else "%.1f".format(Locale.ROOT, value)
    }

    /** "about 2 min left", "about 40 s left", "about 1 h 5 min left"; rounded so it does not flicker. */
    fun left(ms: Long): String {
        val seconds = ms / 1000
        if (seconds < 10) return "a few seconds left"
        return "about " + when {
            seconds < 60 -> "${(seconds + 2) / 5 * 5} s"
            seconds < 3600 -> "${(seconds + 30) / 60} min"
            else -> {
                val minutes = (seconds + 30) / 60
                if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
            }
        } + " left"
    }

    /** "42 MB/s". */
    fun rate(bytesPerSecond: Double): String = bytes(bytesPerSecond.toLong()) + "/s"

    /** What is being written right now: "Packing media 3 of 12: IMG_0014.mov". */
    fun step(progress: BundleProgress): String = when (progress.kind) {
        BundleItemKind.MEDIA -> "Packing media ${progress.mediaIndex} of ${progress.mediaCount}: ${progress.itemName}"
        BundleItemKind.RESOURCE -> "Adding ${progress.itemName}"
        BundleItemKind.THUMBNAIL -> "Adding the project picture"
        BundleItemKind.PROJECT -> "Writing the project data"
    }

    /** The same without the file name, for the notification: "Media 3 of 12". */
    fun shortStep(progress: BundleProgress): String = when (progress.kind) {
        BundleItemKind.MEDIA -> "Media ${progress.mediaIndex} of ${progress.mediaCount}"
        BundleItemKind.RESOURCE -> "LUTs and fonts"
        BundleItemKind.THUMBNAIL, BundleItemKind.PROJECT -> "Project data"
    }

    /** "1.8 of 7.4 GB, about 2 min left" (the time left comes when it is known). */
    fun progressLine(progress: BundleProgress): String = buildString {
        append(bytesOf(progress.doneBytes, progress.totalBytes))
        progress.remainingMs?.let { append(", ").append(left(it)) }
    }

    /** "Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, took 4:12)". */
    fun savedLine(fileName: String, result: BundleWriteResult, fileBytes: Long, tookMs: Long): String {
        val parts = ArrayList<String>()
        parts += bytes(fileBytes)
        parts += "${result.mediaCopied} media file${if (result.mediaCopied == 1) "" else "s"}"
        if (result.lutsIncluded > 0) parts += "${result.lutsIncluded} LUT${if (result.lutsIncluded == 1) "" else "s"}"
        if (result.fontsIncluded > 0) parts += "${result.fontsIncluded} font${if (result.fontsIncluded == 1) "" else "s"}"
        parts += "took ${formatTook(tookMs)}"
        return "Backup saved: $fileName (${parts.joinToString(", ")})"
    }

    /** What could not go in, or empty. */
    fun skippedLine(result: BundleWriteResult): String = buildList {
        if (result.mediaSkipped.isNotEmpty()) add("Not copied (cannot be read): ${named(result.mediaSkipped)}")
        if (result.resourcesSkipped.isNotEmpty()) add("Not included: ${named(result.resourcesSkipped)}")
    }.joinToString(". ")

    private fun named(names: List<String>): String =
        names.take(3).joinToString() + if (names.size > 3) " and ${names.size - 3} more" else ""

    /** The one-line verdict on the saved file, shared by the dialog, the bar and the notification. */
    fun verificationHeadline(verification: BundleVerification?): String = when (verification) {
        null -> ""
        is BundleVerification.Verified -> "Checked: the file is complete (${verification.entryCount} files inside)"
        is BundleVerification.Warning -> "The saved file may be damaged"
        is BundleVerification.CouldNotVerify -> "Could not check the saved file"
        BundleVerification.Skipped -> "The saved file was not checked"
    }

    /** The problems behind a warning, or why the check could not run; empty when there is nothing to add. */
    fun verificationDetail(verification: BundleVerification?): String = when (verification) {
        is BundleVerification.Warning -> verification.problems.take(4).joinToString(". ") + if (verification.problems.size > 4) ". And ${verification.problems.size - 4} more" else ""
        is BundleVerification.CouldNotVerify -> verification.reason.replaceFirstChar { it.uppercase() }
        else -> ""
    }

    /** The reason a backup stopped, from the error that did it: a full disk, a lost permission and so on, never a bare class name. */
    fun failure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        return when {
            chain.any { it is SecurityException } -> "The app no longer has permission to write there. Choose the location again."
            text.contains("ENOSPC", ignoreCase = true) || text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true) ->
                "The storage is full. Free some space or choose another location."
            chain.any { it is java.io.FileNotFoundException } && !text.contains("could not read") ->
                "The chosen file could not be opened (it may have been removed, or the permission was lost)."
            else -> {
                // ProjectError.Io only wraps the cause with the content address of the file; the cause is what the user can act on.
                val top = if (error is com.ultimatevideo.uveditor.data.ProjectError.Io) error.cause ?: error else error
                val own = top.message?.takeIf { it.isNotBlank() } ?: top.javaClass.simpleName
                val deepest = generateSequence(top) { it.cause }.take(6).mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.lastOrNull()
                val full = if (deepest != null && deepest != own) "$own ($deepest)" else own
                full.replaceFirstChar { it.uppercase() }
            }
        }
    }
}
