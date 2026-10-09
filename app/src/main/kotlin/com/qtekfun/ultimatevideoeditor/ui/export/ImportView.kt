package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.ImportSteps
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportText
import com.qtekfun.ultimatevideoeditor.ui.library.ImportReportNotes
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.namedList
import com.qtekfun.ultimatevideoeditor.ui.text.sentenceOf

/** An import as the screens show it: dialog, bar and notification are all built from this one value (itself built from [ImportJobState] only). */
data class ImportView(
    val phase: Phase,
    val title: UiText,
    /** What is happening: "Importing project: file 3 of 12: IMG_0014.mov". Empty when finished. */
    val step: UiText = UiText.Empty,
    val stepShort: UiText = UiText.Empty,
    /** "1.8 of 7.4 GB, about 2 min left". Empty when finished. */
    val progressLine: UiText = UiText.Empty,
    val rateLine: String = "",
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    /** The end state's headline: "Imported: Holiday (7.4 GB, 14 media files, took 4:12)", or why it failed. */
    val message: UiText = UiText.Empty,
    /** What the import did not do cleanly (missing media, a new name, LUTs and fonts) and what it did besides; empty when nothing. */
    val detail: UiText = UiText.Empty,
    /** The imported project, for Open; null unless it was imported. */
    val projectId: String? = null,
) {
    enum class Phase { IMPORTING, IMPORTED, NOTES, FAILED }

    val running: Boolean get() = phase == Phase.IMPORTING

    val canOpen: Boolean get() = projectId != null

    /** The one-line state for the bar. */
    val barLine: UiText
        get() = if (running) UiText.join(" · ", progressLine, if (percent > 0) UiText.res(R.string.percent_value, percent) else UiText.Empty) else message
}

/** The view for [state], or null when there is nothing to show (idle, not shown yet, a quick import, cancelled, or waiting for a folder). */
fun importViewFor(state: ImportJobState): ImportView? = when (state) {
    ImportJobState.Idle, is ImportJobState.Cancelled, is ImportJobState.NeedsMediaFolder -> null
    is ImportJobState.Running ->
        if (!state.revealed) {
            null
        } else {
            val p = state.progress
            ImportView(
                ImportView.Phase.IMPORTING, UiText.res(R.string.import_title_running, ImportJobText.sourceLabel(state.sourceName)),
                step = ImportJobText.step(p), stepShort = ImportJobText.shortStep(p), progressLine = ImportJobText.progressLine(p),
                rateLine = p.bytesPerSecond?.let { BundleJobText.rate(it) }.orEmpty(),
                percent = p.percent, indeterminate = p.totalBytes <= 0 || p.doneBytes <= 0,
            )
        }
    is ImportJobState.Done ->
        if (state.quick) {
            null
        } else {
            val notes = ImportReportText.problems(state.report).isNotEmpty()
            ImportView(
                if (notes) ImportView.Phase.NOTES else ImportView.Phase.IMPORTED,
                UiText.res(if (notes) R.string.import_title_notes else R.string.import_title_done),
                message = ImportJobText.doneLine(state),
                detail = UiText.join("\n", ImportReportText.detailLines(state.report)),
                projectId = state.report.project.id,
            )
        }
    is ImportJobState.Failed -> ImportView(ImportView.Phase.FAILED, UiText.res(R.string.import_title_failed), message = state.message)
}

/** The notification for [state]: the same words, no Android type. Null when there is nothing to show. */
fun importNotificationFor(state: ImportJobState): ExportNotificationModel? {
    val view = importViewFor(state) ?: return null
    return when (view.phase) {
        ImportView.Phase.IMPORTING -> ExportNotificationModel(
            title = view.title,
            text = UiText.join(" · ", view.stepShort, view.progressLine),
            progressPercent = view.percent,
            indeterminate = view.indeterminate,
            ongoing = true,
            showCancel = true,
            import = true,
        )
        else -> ExportNotificationModel(
            title = view.title,
            text = view.message,
            progressPercent = null,
            indeterminate = false,
            ongoing = false,
            showCancel = false,
            import = true,
        )
    }
}

/** The words of an import's progress and result; pure, so the dialog, the bar and the notification say the same and it is tested. */
object ImportJobText {
    /** The picked file's name for a sentence; "the file" (in the language in use) when the provider did not give one. */
    fun sourceLabel(sourceName: String): UiText =
        if (sourceName == BundleImportExecutor.UNKNOWN_NAME) UiText.res(R.string.import_unknown_source) else UiText.Raw(sourceName)

    /** What is being read right now: "Importing project: file 3 of 12: IMG_0014.mov". */
    fun step(progress: BundleProgress): UiText = when (progress.kind) {
        BundleItemKind.MEDIA ->
            if (progress.mediaCount > 0) UiText.res(R.string.import_step_file_of, progress.mediaIndex, progress.mediaCount, progress.itemName)
            else UiText.res(R.string.import_step_file, progress.mediaIndex, progress.itemName)
        BundleItemKind.RESOURCE -> UiText.res(R.string.import_step_adding, progress.itemName)
        BundleItemKind.THUMBNAIL -> UiText.res(R.string.import_step_picture)
        BundleItemKind.PROJECT -> when (progress.itemName) {
            "" -> UiText.res(R.string.import_step_opening)
            ImportSteps.FINISHING -> UiText.res(R.string.import_step_finishing)
            else -> UiText.res(R.string.import_step_reading)
        }
    }

    /** The same without the file name, for the notification: "File 3 of 12". */
    fun shortStep(progress: BundleProgress): UiText = when (progress.kind) {
        BundleItemKind.MEDIA ->
            if (progress.mediaCount > 0) UiText.res(R.string.import_short_file_of, progress.mediaIndex, progress.mediaCount)
            else UiText.res(R.string.import_short_file, progress.mediaIndex)
        BundleItemKind.RESOURCE -> UiText.res(R.string.bundle_short_resource)
        BundleItemKind.THUMBNAIL -> UiText.res(R.string.bundle_short_project)
        BundleItemKind.PROJECT -> if (progress.itemName == ImportSteps.FINISHING) UiText.res(R.string.import_short_finishing) else UiText.res(R.string.bundle_short_project)
    }

    /** "1.8 of 7.4 GB, about 2 min left"; "1.8 GB so far" while the size of the whole is not known (a stream has no directory). */
    fun progressLine(progress: BundleProgress): UiText =
        if (progress.totalBytes > 0) {
            BundleJobText.progressLine(progress)
        } else if (progress.doneBytes > 0) {
            UiText.res(R.string.import_so_far, BundleJobText.bytes(progress.doneBytes))
        } else {
            UiText.Empty
        }

    /** "Imported: Holiday (7.4 GB, 14 media files, took 4:12)". */
    fun doneLine(done: ImportJobState.Done): UiText {
        val parts = ArrayList<UiText>()
        if (done.bytes > 0) parts += UiText.Raw(BundleJobText.bytes(done.bytes))
        val copied = done.report.bundle?.mediaCopied ?: done.report.lumaFusion?.mediaCopied
        if (copied != null) parts += UiText.plural(R.plurals.count_media_files, copied)
        parts += UiText.res(R.string.took_in, formatTook(done.tookMs))
        return UiText.res(R.string.import_done_line, done.report.project.name, UiText.join(", ", parts))
    }

    /** The message of the project list for an import that ended before anything was shown. */
    fun quickLine(report: ImportReport): UiText = ImportReportText.message(report)

    /**
     * Why an import stopped, from the error that did it: a full disk, a lost permission, a file that is not a project, a provider that
     * went away; never a bare class name. Every line says that nothing was added to the project list.
     */
    fun failure(error: Throwable): UiText {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        val reason = when {
            chain.any { it is SecurityException } -> UiText.res(R.string.import_fail_permission)
            text.contains("ENOSPC", ignoreCase = true) || text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true) -> // i18n-ok: matches the system's own English error text
                UiText.res(R.string.import_fail_storage_full)
            error is ProjectError.Bundle -> sentenceOf(error.message.orEmpty())
            error is ProjectError.Corrupt -> UiText.res(R.string.import_fail_not_readable, error.message.orEmpty().removePrefix("Project file is corrupt: ")) // i18n-ok: the prefix ProjectError.Corrupt puts on its message
            error is ProjectError.UnsupportedVersion -> UiText.res(R.string.import_fail_unsupported_version, error.message.orEmpty())
            chain.any { it is java.io.FileNotFoundException } || text.contains("Cannot read ") -> UiText.res(R.string.import_fail_cannot_open)
            error is ProjectError.Io -> {
                val cause = error.cause
                UiText.res(R.string.import_fail_io, cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName ?: error.message.orEmpty())
            }
            else -> sentenceOf(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
        }
        return UiText.res(R.string.import_fail_nothing_added, reason)
    }
}

/** What an import did, in words: used by the project list's message, the report dialog and the result in the bar. Pure. */
object ImportReportText {
    /** What an import did: the project's name and, for a bundle, what became of its media. */
    fun message(report: ImportReport): UiText {
        val name = report.project.name
        val renamed = report.renamedFrom?.let { UiText.res(R.string.import_msg_renamed, name, it) } ?: UiText.Empty
        report.lumaFusion?.let { lf ->
            val omitted = lf.report.notImported.size
            return UiText.join(
                ". ",
                UiText.res(R.string.import_msg_lumafusion, name),
                if (lf.mediaCopied > 0) UiText.plural(R.plurals.import_media_came, lf.mediaCopied) else UiText.Empty,
                if (lf.missing.isNotEmpty()) UiText.res(R.string.import_msg_missing, namedList(lf.missing, andMore = false)) else UiText.Empty,
                if (omitted > 0) UiText.plural(R.plurals.import_settings_not_imported, omitted) else UiText.Empty,
            )
        }
        val bundle = report.bundle ?: return UiText.join(". ", UiText.res(R.string.import_msg_plain, name), renamed)
        return UiText.join(
            ". ",
            UiText.res(R.string.import_msg_plain, name),
            if (bundle.mediaCopied > 0) UiText.plural(R.plurals.import_media_came, bundle.mediaCopied) else UiText.Empty,
            BundleExportText.importSentence(bundle.resources) ?: UiText.Empty,
            if (bundle.relinked > 0) UiText.plural(R.plurals.import_relinked_by_name, bundle.relinked) else UiText.Empty,
            if (bundle.missing.isNotEmpty()) UiText.res(R.string.import_msg_missing, namedList(bundle.missing)) else UiText.Empty,
            renamed,
        )
    }

    /** The list of LUTs and fonts an import could not install (and the like), or null when it went fully through. */
    fun notes(report: ImportReport): ImportReportNotes? {
        report.lumaFusion?.let { lf ->
            val notImported = lf.report.notImported.map { UiText.Raw("$it") }
            return ImportReportNotes(
                report.project.name,
                notImported,
                lf.report.imported.map { UiText.Raw(it) },
                problemsHeading = if (notImported.isEmpty()) null else UiText.res(R.string.import_lf_heading),
            ).let { if (notImported.isEmpty()) it.copy(notes = it.notes + UiText.res(R.string.import_lf_nothing_left_out)) else it }
        }
        val resources = report.bundle?.resources ?: return null
        val problems = BundleExportText.importProblems(resources)
        if (problems.isEmpty()) return null
        return ImportReportNotes(report.project.name, problems, BundleExportText.importNotes(resources))
    }

    /** What was not clean: media still missing, a new name, LUTs and fonts that could not be installed, LumaFusion settings left out. */
    fun problems(report: ImportReport): List<UiText> = buildList {
        report.renamedFrom?.let { add(UiText.res(R.string.import_prob_renamed, it, report.project.name)) }
        val missing = report.bundle?.missing ?: report.lumaFusion?.missing.orEmpty()
        if (missing.isNotEmpty()) add(UiText.res(R.string.import_prob_missing, namedList(missing)))
        report.bundle?.let { addAll(BundleExportText.importProblems(it.resources)) }
        report.lumaFusion?.let { lf ->
            if (lf.report.notImported.isNotEmpty()) {
                add(UiText.Plural(R.plurals.import_prob_lf_kinds, lf.report.notImported.size, listOf(lf.report.notImported.size, namedList(lf.report.notImported.map { "$it" }, andMore = false))))
            }
        }
    }

    /** [problems] first, then the good news (relinked by name and size, LUTs and fonts installed). */
    fun detailLines(report: ImportReport): List<UiText> = problems(report) + buildList {
        report.bundle?.let { bundle ->
            if (bundle.relinked > 0) add(UiText.plural(R.plurals.import_detail_relinked, bundle.relinked))
            addAll(BundleExportText.importNotes(bundle.resources))
        }
    }
}
