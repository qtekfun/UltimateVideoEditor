package com.qtekfun.ultimatevideoeditor.ui.editor.interchange

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.Edl
import com.qtekfun.ultimatevideoeditor.data.interchange.Fcpxml
import com.qtekfun.ultimatevideoeditor.data.interchange.InterchangeExporter
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.project.ProjectPersistence
import com.qtekfun.ultimatevideoeditor.ui.export.BundleStart
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportDraft
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportText
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Exports of the project to other tools: the project bundle (with or without media), EDL and FCPXML. Each starts from a save of what is
 * on screen, asks the screen for a document picker through an effect, and writes when the picker answers.
 */
internal class InterchangeHandler(
    host: EditorHost,
    private val projectId: String,
    private val interchange: InterchangeExporter,
    private val persistence: ProjectPersistence,
) : EditorHost by host {

    /** What the bundle dialog was confirmed with, kept while the document picker is open; consumed by the export that follows. */
    private var pendingBundleChoice: BundleChoice? = null

    /**
     * Opens the bundle dialog at once, then measures the saved project (what its media, LUTs and fonts weigh) and
     * shows it. The project is saved first because the bundle is made from the file on disk.
     */
    private fun openBundleDialog(kind: InterchangeKind) {
        val choice = BundleChoice(includeMedia = kind == InterchangeKind.BUNDLE_WITH_MEDIA)
        reduce { copy(library = library.copy(bundleDraft = BundleExportDraft(choice = choice))) }
        scope.launch {
            val measured = try {
                persistence.saveNow()
                BundleExportDraft(preview = interchange.bundlePreview(projectId), choice = choice)
            } catch (e: ProjectError) {
                BundleExportDraft(choice = choice, failed = reasonOf(e, UiText.res(R.string.project_unreadable)))
            } catch (e: IOException) {
                BundleExportDraft(choice = choice, failed = reasonOf(e, UiText.res(R.string.project_unreadable)))
            }
            reduce {
                val current = library.bundleDraft ?: return@reduce this // closed while measuring
                copy(library = library.copy(bundleDraft = measured.copy(choice = current.choice)))
            }
        }
    }

    fun confirmBundleExport() {
        val draft = state.value.library.bundleDraft ?: return
        if (!draft.canExport) return
        pendingBundleChoice = draft.choice
        reduce { copy(library = library.copy(bundleDraft = null)) }
        val kind = if (draft.choice.includeMedia) InterchangeKind.BUNDLE_WITH_MEDIA else InterchangeKind.BUNDLE
        val project = persistence.currentProjectDto() ?: return
        val base = project.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "project" }
        emit(EditorEffect.LaunchInterchangePicker(kind, "$base.${kind.extension}", kind.mime))
    }

    fun requestExport(kind: InterchangeKind) {
        if (kind == InterchangeKind.BUNDLE || kind == InterchangeKind.BUNDLE_WITH_MEDIA) {
            if (state.value.library.busy == null) openBundleDialog(kind)
            return
        }
        val project = persistence.currentProjectDto() ?: return
        val base = project.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "project" }
        val (extension, mime) = when (kind) {
            InterchangeKind.EDL -> {
                val files = Edl.export(project).files
                if (files.isEmpty()) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_there_are_no_video_or)))
                    return
                }
                if (files.size == 1) "edl" to INTERCHANGE_MIME else "zip" to ZIP_MIME
            }
            else -> kind.extension to kind.mime
        }
        emit(EditorEffect.LaunchInterchangePicker(kind, "$base.$extension", mime))
    }

    fun exportTo(kind: InterchangeKind, uri: String) {
        val project = persistence.currentProjectDto() ?: return
        if (state.value.library.busy != null) return
        reduce { copy(library = library.copy(busy = UiText.res(kind.busyRes))) }
        scope.launch {
            try {
                emit(EditorEffect.ShowMessage(writeExport(kind, uri, project)))
            } catch (e: ProjectError) {
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_export_failed, reasonOf(e, UiText.Empty))))
            } catch (e: IOException) {
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_export_failed, e.message ?: "could not write the file"))) // i18n-ok: the system message when there is none
            } finally {
                reduce { copy(library = library.copy(busy = null)) }
            }
        }
    }

    /** Writes the export and returns the message to show: what was written and what the format left out. */
    private suspend fun writeExport(kind: InterchangeKind, uri: String, project: ProjectDto): UiText {
        when (kind) {
            InterchangeKind.BUNDLE, InterchangeKind.BUNDLE_WITH_MEDIA -> {
                // The bundle is made from the project file, so what is on screen has to be saved first.
                persistence.saveNow()
                val choice = pendingBundleChoice ?: BundleChoice(includeMedia = kind == InterchangeKind.BUNDLE_WITH_MEDIA)
                pendingBundleChoice = null
                // A long job that outlives this editor: its dialog, the project list's bar and the notification show how far it is
                // and how it ended, so nothing here waits for the copy.
                when (val started = interchange.startBundleExport(projectId, project.name, uri, choice)) {
                    BundleStart.Started -> return UiText.res(R.string.backup_started)
                    is BundleStart.Refused -> return started.reason
                    null -> Unit
                }
                val result = interchange.exportBundle(projectId, uri, choice)
                return BundleExportText.exportMessage(UiText.res(R.string.bundle_written), choice, result)
            }
            InterchangeKind.EDL -> {
                val export = Edl.export(project)
                if (export.files.isEmpty()) throw IOException("there are no video or audio clips to put in an EDL")
                val bytes = if (export.files.size == 1) export.files.single().text.toByteArray(Charsets.UTF_8) else Edl.zip(export.files)
                interchange.writeDocument(uri, bytes)
                return UiText.join("", UiText.plural(R.plurals.s5_edl_written, export.files.size), leftOut(export.notes))
            }
            InterchangeKind.FCPXML -> {
                val export = Fcpxml.export(project)
                interchange.writeDocument(uri, export.xml.toByteArray(Charsets.UTF_8))
                return UiText.join("", UiText.res(R.string.fcpxml_written), leftOut(export.notes))
            }
        }
    }

    private fun leftOut(notes: List<UiText>): UiText = when {
        notes.isEmpty() -> UiText.Empty
        notes.size == 1 -> UiText.res(R.string.s5_left_out_one, notes.first())
        else -> UiText.res(R.string.s5_left_out_more, notes.first(), notes.size - 1)
    }
}
