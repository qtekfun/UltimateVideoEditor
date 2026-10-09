package com.qtekfun.ultimatevideoeditor.ui.library

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.BundlePreview
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import com.qtekfun.ultimatevideoeditor.data.interchange.ResourceImportReport
import com.qtekfun.ultimatevideoeditor.ui.about.AboutController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.namedList

/**
 * The state of the dialog that asks what a project bundle should contain: [preview] is what the project
 * could put in (null while it is being measured, or when it could not be: see [failed]) and [choice] is what
 * is ticked. Shared by the hub and the editor, which both export bundles.
 */
data class BundleExportDraft(
    val preview: BundlePreview? = null,
    val choice: BundleChoice = BundleChoice(),
    /** Why the project could not be measured; the dialog then offers only to cancel. */
    val failed: UiText? = null,
) {
    /** What the choice would write, or null until the project has been measured. */
    val estimatedBytes: Long? get() = preview?.estimatedBytes(choice)

    /** The export can go ahead once the project has been measured. */
    val canExport: Boolean get() = preview != null && failed == null
}

/** The words of the export dialog and of the messages after an export or an import; pure, so they can be tested. */
object BundleExportText {
    fun size(bytes: Long): String = AboutController.formatBytes(bytes)

    /** "Media files: 3 (1.4 GB)", with what cannot be read. */
    fun mediaLine(preview: BundlePreview): UiText =
        if (preview.mediaUnreadable.isEmpty()) {
            UiText.res(R.string.bundle_media_line, preview.mediaCount, size(preview.mediaBytes))
        } else {
            UiText.res(R.string.bundle_media_line_unreadable, preview.mediaCount, size(preview.mediaBytes), preview.mediaUnreadable.size)
        }

    fun lutLine(preview: BundlePreview): UiText? = resourceLine(R.string.bundle_luts_line, R.string.bundle_luts_line_absent, preview.luts)

    fun fontLine(preview: BundlePreview): UiText? = resourceLine(R.string.bundle_fonts_line, R.string.bundle_fonts_line_absent, preview.fonts)

    private fun resourceLine(line: Int, lineWithAbsent: Int, items: List<com.qtekfun.ultimatevideoeditor.data.interchange.ResourceInfo>): UiText? {
        if (items.isEmpty()) return null
        val present = items.filter { it.available }
        val absent = items.size - present.size
        val bytes = size(present.sumOf { it.sizeBytes })
        return if (absent > 0) UiText.res(lineWithAbsent, present.size, bytes, absent) else UiText.res(line, present.size, bytes)
    }

    /** "About 1.4 GB", or "Measuring…" until the project has been measured. */
    fun estimateLine(draft: BundleExportDraft): UiText {
        val bytes = draft.estimatedBytes ?: return UiText.res(R.string.common_measuring)
        return UiText.res(R.string.bundle_estimate_line, size(bytes))
    }

    /** What an export wrote and what it had to leave out; [verb] is the opening ("Bundle exported"). */
    fun exportMessage(verb: UiText, choice: BundleChoice, result: BundleWriteResult): UiText {
        val parts = ArrayList<UiText>()
        if (choice.includeMedia) parts += UiText.plural(R.plurals.count_media_files, result.mediaCopied)
        if (result.lutsIncluded > 0) parts += UiText.plural(R.plurals.count_luts, result.lutsIncluded)
        if (result.fontsIncluded > 0) parts += UiText.plural(R.plurals.count_fonts, result.fontsIncluded)
        // "a and b and c": the word "and" is a resource, so a language can place it as it needs.
        val head = if (parts.isEmpty()) verb else UiText.res(R.string.bundle_exported_with, verb, parts.reduce { left, right -> UiText.res(R.string.list_and, left, right) })
        return UiText.join(
            ". ",
            head,
            if (result.mediaSkipped.isNotEmpty()) UiText.res(R.string.bundle_skipped_unreadable, namedList(result.mediaSkipped)) else UiText.Empty,
            if (result.resourcesSkipped.isNotEmpty()) UiText.res(R.string.bundle_skipped_resources, namedList(result.resourcesSkipped)) else UiText.Empty,
        )
    }

    /** One sentence for the snackbar after importing, or null when there is nothing to say about resources. */
    fun importSentence(report: ResourceImportReport): UiText? {
        if (report.isEmpty) return null
        val parts = ArrayList<UiText>()
        if (report.installed.isNotEmpty()) parts += UiText.plural(R.plurals.import_res_installed, report.installed.size)
        if (report.failed.isNotEmpty()) parts += UiText.res(R.string.import_res_failed, report.failed.size)
        if (report.missing.isNotEmpty()) parts += UiText.res(R.string.import_res_missing, report.missing.size)
        return if (parts.isEmpty()) null else UiText.join(", ", parts)
    }

    /** The detailed list for the import report dialog; empty when there is no problem worth a dialog. */
    fun importProblems(report: ResourceImportReport): List<UiText> = buildList {
        for (p in report.failed) add(UiText.res(R.string.import_res_problem, p.name, p.reason))
        for (name in report.missing) add(UiText.res(R.string.import_res_not_found, name))
    }

    /** Lines of good news for the same dialog (shown after the problems). */
    fun importNotes(report: ResourceImportReport): List<UiText> = buildList {
        if (report.installed.isNotEmpty()) add(UiText.res(R.string.import_res_installed_list, report.installed.joinToString()))
        if (report.alreadyHere.isNotEmpty()) add(UiText.res(R.string.import_res_already_here, report.alreadyHere.joinToString()))
        if (report.rekeyed.isNotEmpty()) add(UiText.res(R.string.import_res_rekeyed, report.rekeyed.joinToString()))
    }
}

/** The report of an import with problems: what could not be installed, shown until dismissed. */
data class ImportReportNotes(
    val projectName: String,
    val problems: List<UiText>,
    val notes: List<UiText>,
    /** The sentence above the problems; null for the LUT and font wording. */
    val problemsHeading: UiText? = null,
)
