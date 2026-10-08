package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.ImportSteps
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportText
import com.qtekfun.ultimatevideoeditor.ui.library.ImportReportNotes

/** An import as the screens show it: dialog, bar and notification are all built from this one value (itself built from [ImportJobState] only). */
data class ImportView(
    val phase: Phase,
    val title: String,
    /** What is happening: "Importing project: file 3 of 12: IMG_0014.mov". Empty when finished. */
    val step: String = "",
    val stepShort: String = "",
    /** "1.8 of 7.4 GB, about 2 min left". Empty when finished. */
    val progressLine: String = "",
    val rateLine: String = "",
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    /** The end state's headline: "Imported: Holiday (7.4 GB, 14 media files, took 4:12)", or why it failed. */
    val message: String = "",
    /** What the import did not do cleanly (missing media, a new name, LUTs and fonts) and what it did besides; empty when nothing. */
    val detail: String = "",
    /** The imported project, for Open; null unless it was imported. */
    val projectId: String? = null,
) {
    enum class Phase { IMPORTING, IMPORTED, NOTES, FAILED }

    val running: Boolean get() = phase == Phase.IMPORTING

    val canOpen: Boolean get() = projectId != null

    /** The one-line state for the bar. */
    val barLine: String
        get() = if (running) listOf(progressLine, if (percent > 0) "$percent%" else "").filter { it.isNotEmpty() }.joinToString(" · ") else message
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
                ImportView.Phase.IMPORTING, "Importing ${state.sourceName}",
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
                if (notes) "Imported, with notes" else "Project imported",
                message = ImportJobText.doneLine(state),
                detail = ImportReportText.detailLines(state.report).joinToString("\n"),
                projectId = state.report.project.id,
            )
        }
    is ImportJobState.Failed -> ImportView(ImportView.Phase.FAILED, "Import failed", message = state.message)
}

/** The notification for [state]: the same words, no Android type. Null when there is nothing to show. */
fun importNotificationFor(state: ImportJobState): ExportNotificationModel? {
    val view = importViewFor(state) ?: return null
    return when (view.phase) {
        ImportView.Phase.IMPORTING -> ExportNotificationModel(
            title = view.title,
            text = listOf(view.stepShort, view.progressLine).filter { it.isNotEmpty() }.joinToString(" · "),
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
    /** What is being read right now: "Importing project: file 3 of 12: IMG_0014.mov". */
    fun step(progress: BundleProgress): String = "Importing project: " + when (progress.kind) {
        BundleItemKind.MEDIA -> "file ${progress.mediaIndex}${if (progress.mediaCount > 0) " of ${progress.mediaCount}" else ""}: ${progress.itemName}"
        BundleItemKind.RESOURCE -> "adding ${progress.itemName}"
        BundleItemKind.THUMBNAIL -> "adding the project picture"
        BundleItemKind.PROJECT -> when (progress.itemName) {
            "" -> "opening the file"
            ImportSteps.FINISHING -> "finishing"
            else -> "reading the project data"
        }
    }

    /** The same without the file name, for the notification: "File 3 of 12". */
    fun shortStep(progress: BundleProgress): String = when (progress.kind) {
        BundleItemKind.MEDIA -> "File ${progress.mediaIndex}${if (progress.mediaCount > 0) " of ${progress.mediaCount}" else ""}"
        BundleItemKind.RESOURCE -> "LUTs and fonts"
        BundleItemKind.THUMBNAIL -> "Project data"
        BundleItemKind.PROJECT -> if (progress.itemName == ImportSteps.FINISHING) "Finishing" else "Project data"
    }

    /** "1.8 of 7.4 GB, about 2 min left"; "1.8 GB so far" while the size of the whole is not known (a stream has no directory). */
    fun progressLine(progress: BundleProgress): String =
        if (progress.totalBytes > 0) {
            BundleJobText.progressLine(progress)
        } else if (progress.doneBytes > 0) {
            BundleJobText.bytes(progress.doneBytes) + " so far"
        } else {
            ""
        }

    /** "Imported: Holiday (7.4 GB, 14 media files, took 4:12)". */
    fun doneLine(done: ImportJobState.Done): String {
        val parts = ArrayList<String>()
        if (done.bytes > 0) parts += BundleJobText.bytes(done.bytes)
        val copied = done.report.bundle?.mediaCopied ?: done.report.lumaFusion?.mediaCopied
        if (copied != null) parts += "$copied media file${if (copied == 1) "" else "s"}"
        parts += "took ${formatTook(done.tookMs)}"
        return "Imported: ${done.report.project.name} (${parts.joinToString(", ")})"
    }

    /** The message of the project list for an import that ended before anything was shown. */
    fun quickLine(report: ImportReport): String = ImportReportText.message(report)

    /**
     * Why an import stopped, from the error that did it: a full disk, a lost permission, a file that is not a project, a provider that
     * went away; never a bare class name. Every line says that nothing was added to the project list.
     */
    fun failure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        val reason = when {
            chain.any { it is SecurityException } -> "The app no longer has permission to read that file. Pick it again."
            text.contains("ENOSPC", ignoreCase = true) || text.contains("No space left", ignoreCase = true) || text.contains("not enough space", ignoreCase = true) ->
                "The storage is full. Free some space and import again."
            error is ProjectError.Bundle -> error.message.orEmpty()
            error is ProjectError.Corrupt -> "This is not a readable project (${error.message.orEmpty().removePrefix("Project file is corrupt: ")})."
            error is ProjectError.UnsupportedVersion -> error.message.orEmpty() + ". Update the app to open it."
            chain.any { it is java.io.FileNotFoundException } || text.contains("Cannot read ") ->
                "The file could not be opened. It may have been moved or deleted, or the app that provides it is gone."
            error is ProjectError.Io -> {
                val cause = error.cause
                "Reading or writing failed (${cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName ?: error.message})."
            }
            else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        }
        val sentence = reason.trim().let { if (it.endsWith(".") || it.endsWith(")") || it.endsWith("!")) it else "$it." }
        return sentence.replaceFirstChar { it.uppercase() } + " Nothing was added to the project list."
    }
}

/** What an import did, in words: used by the project list's message, the report dialog and the result in the bar. Pure. */
object ImportReportText {
    /** What an import did: the project's name and, for a bundle, what became of its media. */
    fun message(report: ImportReport): String {
        val name = report.project.name
        report.lumaFusion?.let { lf ->
            val omitted = lf.report.notImported.size
            return "Imported \"$name\" from LumaFusion" +
                (if (lf.mediaCopied > 0) ". ${lf.mediaCopied} media file${if (lf.mediaCopied == 1) "" else "s"} came with it" else "") +
                (if (lf.missing.isNotEmpty()) ". Missing (relink in the editor): ${lf.missing.take(3).joinToString()}" else "") +
                (if (omitted > 0) ". $omitted kind${if (omitted == 1) "" else "s"} of settings not imported" else "")
        }
        val bundle = report.bundle ?: return "Imported \"$name\"" + (report.renamedFrom?.let { ". Named \"$name\" because \"$it\" was taken" } ?: "")
        return buildString {
            append("Imported \"$name\"")
            if (bundle.mediaCopied > 0) append(". ${bundle.mediaCopied} media file${if (bundle.mediaCopied == 1) "" else "s"} came with it")
            BundleExportText.importSentence(bundle.resources)?.let { append(". $it") }
            if (bundle.relinked > 0) append(". ${bundle.relinked} found on this device by name and size")
            if (bundle.missing.isNotEmpty()) {
                append(". Missing (relink in the editor): ${bundle.missing.take(3).joinToString()}")
                if (bundle.missing.size > 3) append(" and ${bundle.missing.size - 3} more")
            }
            report.renamedFrom?.let { append(". Named \"$name\" because \"$it\" was taken") }
        }
    }

    /** The list of LUTs and fonts an import could not install (and the like), or null when it went fully through. */
    fun notes(report: ImportReport): ImportReportNotes? {
        report.lumaFusion?.let { lf ->
            val notImported = lf.report.notImported.map { "$it" }
            return ImportReportNotes(
                report.project.name,
                notImported,
                lf.report.imported,
                problemsHeading = if (notImported.isEmpty()) null else "Not imported from LumaFusion (what each line says is used instead):",
            ).let { if (notImported.isEmpty()) it.copy(notes = it.notes + "Nothing was left out.") else it }
        }
        val resources = report.bundle?.resources ?: return null
        val problems = BundleExportText.importProblems(resources)
        if (problems.isEmpty()) return null
        return ImportReportNotes(report.project.name, problems, BundleExportText.importNotes(resources))
    }

    /** What was not clean: media still missing, a new name, LUTs and fonts that could not be installed, LumaFusion settings left out. */
    fun problems(report: ImportReport): List<String> = buildList {
        report.renamedFrom?.let { add("The name \"$it\" was taken, so the project is called \"${report.project.name}\".") }
        val missing = report.bundle?.missing ?: report.lumaFusion?.missing.orEmpty()
        if (missing.isNotEmpty()) {
            add("Missing media (relink in the editor): ${missing.take(3).joinToString()}" + if (missing.size > 3) " and ${missing.size - 3} more" else "")
        }
        report.bundle?.let { addAll(BundleExportText.importProblems(it.resources)) }
        report.lumaFusion?.let { lf ->
            if (lf.report.notImported.isNotEmpty()) {
                add("${lf.report.notImported.size} kind${if (lf.report.notImported.size == 1) "" else "s"} of LumaFusion settings not imported: ${lf.report.notImported.take(3).joinToString()}")
            }
        }
    }

    /** [problems] first, then the good news (relinked by name and size, LUTs and fonts installed). */
    fun detailLines(report: ImportReport): List<String> = problems(report) + buildList {
        report.bundle?.let { bundle ->
            if (bundle.relinked > 0) add("${bundle.relinked} media file${if (bundle.relinked == 1) "" else "s"} found on this device by name and size.")
            addAll(BundleExportText.importNotes(bundle.resources))
        }
    }
}
