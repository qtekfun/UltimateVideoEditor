package com.ultimatevideo.uveditor.ui.library

import com.ultimatevideo.uveditor.data.interchange.BundleChoice
import com.ultimatevideo.uveditor.data.interchange.BundlePreview
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import com.ultimatevideo.uveditor.data.interchange.ResourceImportReport
import com.ultimatevideo.uveditor.ui.about.AboutController

/**
 * The state of the dialog that asks what a project bundle should contain: [preview] is what the project
 * could put in (null while it is being measured, or when it could not be: see [failed]) and [choice] is what
 * is ticked. Shared by the hub and the editor, which both export bundles.
 */
data class BundleExportDraft(
    val preview: BundlePreview? = null,
    val choice: BundleChoice = BundleChoice(),
    /** Why the project could not be measured; the dialog then offers only to cancel. */
    val failed: String? = null,
) {
    /** What the choice would write, or null until the project has been measured. */
    val estimatedBytes: Long? get() = preview?.estimatedBytes(choice)

    /** The export can go ahead once the project has been measured. */
    val canExport: Boolean get() = preview != null && failed == null
}

/** The words of the export dialog and of the messages after an export or an import; pure, so they can be tested. */
object BundleExportText {
    const val FONT_LICENCE_NOTE =
        "Fonts are only included if you tick this. Many font licences do not allow giving the file to others: " +
            "check yours before sharing the bundle."

    fun size(bytes: Long): String = AboutController.formatBytes(bytes)

    /** "Media files: 3 (1.4 GB)", with what cannot be read. */
    fun mediaLine(preview: BundlePreview): String = buildString {
        append("Media files: ${preview.mediaCount} (${size(preview.mediaBytes)})")
        if (preview.mediaUnreadable.isNotEmpty()) append(", ${preview.mediaUnreadable.size} cannot be read and stay out")
    }

    fun lutLine(preview: BundlePreview): String? = resourceLine("Colour LUTs", preview.luts)

    fun fontLine(preview: BundlePreview): String? = resourceLine("Fonts", preview.fonts)

    private fun resourceLine(title: String, items: List<com.ultimatevideo.uveditor.data.interchange.ResourceInfo>): String? {
        if (items.isEmpty()) return null
        val present = items.filter { it.available }
        return buildString {
            append("$title: ${present.size} (${size(present.sumOf { it.sizeBytes })})")
            val absent = items.size - present.size
            if (absent > 0) append(", $absent not in this device's library")
        }
    }

    /** "About 1.4 GB", or "Measuring…" until the project has been measured. */
    fun estimateLine(draft: BundleExportDraft): String {
        val bytes = draft.estimatedBytes ?: return "Measuring…"
        return "About ${size(bytes)} plus the project file"
    }

    /** What an export wrote and what it had to leave out. */
    fun exportMessage(verb: String, choice: BundleChoice, result: BundleWriteResult): String = buildString {
        append(verb)
        val parts = ArrayList<String>()
        if (choice.includeMedia) parts += "${result.mediaCopied} media file${if (result.mediaCopied == 1) "" else "s"}"
        if (result.lutsIncluded > 0) parts += "${result.lutsIncluded} LUT${if (result.lutsIncluded == 1) "" else "s"}"
        if (result.fontsIncluded > 0) parts += "${result.fontsIncluded} font${if (result.fontsIncluded == 1) "" else "s"}"
        if (parts.isNotEmpty()) append(" with ${parts.joinToString(" and ")}")
        if (result.mediaSkipped.isNotEmpty()) {
            append(". Not copied (cannot be read): ${result.mediaSkipped.take(3).joinToString()}")
            if (result.mediaSkipped.size > 3) append(" and ${result.mediaSkipped.size - 3} more")
        }
        if (result.resourcesSkipped.isNotEmpty()) {
            append(". Not included: ${result.resourcesSkipped.take(3).joinToString()}")
            if (result.resourcesSkipped.size > 3) append(" and ${result.resourcesSkipped.size - 3} more")
        }
    }

    /** One sentence for the snackbar after importing, or null when there is nothing to say about resources. */
    fun importSentence(report: ResourceImportReport): String? {
        if (report.isEmpty) return null
        val parts = ArrayList<String>()
        val fresh = report.installed.size
        if (fresh > 0) parts += "$fresh LUT/font${if (fresh == 1) "" else "s"} installed"
        if (report.failed.isNotEmpty()) parts += "${report.failed.size} could not be installed"
        if (report.missing.isNotEmpty()) parts += "${report.missing.size} still missing"
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** The detailed list for the import report dialog; empty when there is no problem worth a dialog. */
    fun importProblems(report: ResourceImportReport): List<String> = buildList {
        for (p in report.failed) add("${p.name}: ${p.reason}")
        for (name in report.missing) add("$name: not in the bundle and not on this device")
    }

    /** Lines of good news for the same dialog (shown after the problems). */
    fun importNotes(report: ResourceImportReport): List<String> = buildList {
        if (report.installed.isNotEmpty()) add("Installed: ${report.installed.joinToString()}")
        if (report.alreadyHere.isNotEmpty()) add("Already on this device: ${report.alreadyHere.joinToString()}")
        if (report.rekeyed.isNotEmpty()) add("Given a new library key (another LUT used the old one): ${report.rekeyed.joinToString()}")
    }
}

/** The report of an import with problems: what could not be installed, shown until dismissed. */
data class ImportReportNotes(
    val projectName: String,
    val problems: List<String>,
    val notes: List<String>,
    /** The sentence above the problems; null for the LUT and font wording. */
    val problemsHeading: String? = null,
)
