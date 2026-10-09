package com.qtekfun.ultimatevideoeditor.ui.templates

import com.qtekfun.ultimatevideoeditor.ui.text.rawOr
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.ClipPeeker
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.TemplateFile
import com.qtekfun.ultimatevideoeditor.data.TemplateFormatException
import com.qtekfun.ultimatevideoeditor.data.TemplateStore
import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.domain.EditError
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.templates.Placeholder
import com.qtekfun.ultimatevideoeditor.domain.templates.ProjectTemplate
import com.qtekfun.ultimatevideoeditor.domain.templates.SlotFill
import com.qtekfun.ultimatevideoeditor.domain.templates.TemplateBuilder
import com.qtekfun.ultimatevideoeditor.domain.templates.TemplateInstantiator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** A file the user chose for a placeholder, probed and ready to be placed. */
data class PickedMedia(val fill: SlotFill, val label: String)

data class TemplateWizardState(
    val templates: List<ProjectTemplate> = emptyList(),
    val selected: ProjectTemplate? = null,
    val name: String = "",
    /** The media chosen so far, by placeholder id. */
    val picked: Map<String, PickedMedia> = emptyMap(),
    /** The placeholder whose file is being read, or null. */
    val busyPlaceholder: String? = null,
    val busy: Boolean = false,
    /** What fitting the media into the template would do right now: warnings, or why it cannot be done yet. */
    val warnings: List<String> = emptyList(),
    val problem: UiText? = null,
    val message: UiText? = null,
    /** Set when a project was created; the host opens it. */
    val createdProjectId: String? = null,
) {
    val missingRequired: List<Placeholder>
        get() = selected?.placeholders.orEmpty().filter { !it.optional && it.id !in picked }

    val canCreate: Boolean get() = selected != null && !busy && busyPlaceholder == null && name.isNotBlank() && missingRequired.isEmpty() && problem == null
}

/**
 * Starting a project from a template (SPECS.md 9.19): choose a template, choose a file for each placeholder, and
 * create the project; plus keeping the user's own templates (make one from a project, import or export a
 * `.uvtemplate` file, delete). Everything stays on the device; files come from the system picker.
 */
class TemplateWizardViewModel(
    private val projects: ProjectRepository,
    private val store: TemplateStore,
    private val importer: MediaImporter,
    private val peeker: ClipPeeker,
    private val transfer: ProjectTransferIO,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().take(8) },
) : ViewModel() {

    private val _state = MutableStateFlow(TemplateWizardState())
    val state: StateFlow<TemplateWizardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val all = withContext(io) { store.all() }
            _state.update { it.copy(templates = all) }
        }
    }

    fun select(templateId: String) {
        val template = _state.value.templates.firstOrNull { it.id == templateId } ?: return
        _state.update { it.copy(selected = template, name = template.name, picked = emptyMap(), warnings = emptyList(), problem = null, message = null) }
        recompute()
    }

    /** Back to the list of templates. */
    fun back() {
        _state.update { it.copy(selected = null, picked = emptyMap(), warnings = emptyList(), problem = null, message = null) }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun clear(placeholderId: String) {
        _state.update { it.copy(picked = it.picked - placeholderId) }
        recompute()
    }

    /** Reads the file [uri] (keeping access to it, like any import) and puts it in [placeholderId]. */
    fun pick(placeholderId: String, uri: String) {
        val template = _state.value.selected ?: return
        _state.update { it.copy(busyPlaceholder = placeholderId, message = null) }
        viewModelScope.launch {
            try {
                val (asset, size) = withContext(io) {
                    val probed = importer.import(uri)
                    val size = try {
                        peeker.peek(uri)
                    } catch (e: MediaImportException) {
                        null
                    }
                    assetOf(uri, probed, FrameRate(template.fpsNum, template.fpsDen)) to size
                }
                val label = asset.displayName ?: size?.displayName ?: uri.substringAfterLast('/')
                val fill = SlotFill(asset, 0L, size?.width ?: 0, size?.height ?: 0)
                _state.update { it.copy(picked = it.picked + (placeholderId to PickedMedia(fill, label)), busyPlaceholder = null) }
                recompute()
            } catch (e: FileTooShortException) {
                _state.update { it.copy(busyPlaceholder = null, message = UiText.res(R.string.ed_s3_tw_too_short)) }
            } catch (e: MediaImportException) {
                _state.update { it.copy(busyPlaceholder = null, message = rawOr(e.message, UiText.res(R.string.ed_s3_tw_cannot_use))) }
            }
        }
    }

    /** Fits the chosen media into the template and creates the project; [TemplateWizardState.createdProjectId] says which. */
    fun create() {
        val current = _state.value
        val template = current.selected ?: return
        if (!current.canCreate) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val result = when (val r = TemplateInstantiator.instantiate(template, current.picked.mapValues { it.value.fill })) {
                    is EditResult.Success -> r.value
                    is EditResult.Failure -> {
                        _state.update { it.copy(busy = false, message = describe(r.error)) }
                        return@launch
                    }
                }
                val id = withContext(io) {
                    val settings = ProjectSettingsDto(template.width, template.height, template.fpsNum, template.fpsDen, template.colorSpace)
                    val created = projects.create(current.name.trim(), settings)
                    projects.save(TimelineMapper.toDto(created, result.timeline, result.assets))
                    created.id
                }
                _state.update { it.copy(busy = false, createdProjectId = id) }
            } catch (e: ProjectError) {
                _state.update { it.copy(busy = false, message = UiText.res(R.string.ed_s3_tw_create_failed, e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = UiText.res(R.string.ed_s3_tw_save_failed, e.message.orEmpty())) }
            }
        }
    }

    fun consumeCreated() = _state.update { it.copy(createdProjectId = null, selected = null, picked = emptyMap()) }

    /** Makes a template out of the project [projectId] (its media becomes placeholders) and keeps it. */
    fun saveProjectAsTemplate(projectId: String) {
        viewModelScope.launch {
            try {
                val template = withContext(io) {
                    val dto = projects.load(projectId)
                    val timeline = TimelineMapper.toTimeline(dto)
                    val made = TemplateBuilder.fromProject(
                        id = "user-${idGenerator()}",
                        name = "${dto.name} template", // i18n-ok: a stored name, kept as saved
                        description = "Made from the project \"${dto.name}\".", // i18n-ok: a stored description, kept as saved
                        width = dto.settings.width,
                        height = dto.settings.height,
                        fpsNum = dto.settings.fpsNum,
                        fpsDen = dto.settings.fpsDen,
                        colorSpace = dto.settings.colorSpace,
                        timeline = timeline,
                        assets = dto.mediaLibrary,
                    )
                    if (made.placeholders.isEmpty()) null else store.save(made)
                }
                if (template == null) {
                    _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_no_clips)) }
                } else {
                    _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_saved_template, template.name, slotCountLabel(template.placeholders.size))) }
                    refresh()
                }
            } catch (e: ProjectError) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_read_failed, e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_template_save_failed, e.message.orEmpty())) }
            }
        }
    }

    fun importTemplate(uri: String) {
        viewModelScope.launch {
            try {
                val stored = withContext(io) { store.import(String(transfer.read(uri), Charsets.UTF_8)) }
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_added, stored.name)) }
                refresh()
            } catch (e: TemplateFormatException) {
                _state.update { it.copy(message = rawOr(e.message, UiText.res(R.string.ed_s3_tw_not_template))) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_file_unreadable, e.message.orEmpty())) }
            }
        }
    }

    fun exportTemplate(templateId: String, uri: String) {
        val template = _state.value.templates.firstOrNull { it.id == templateId } ?: return
        viewModelScope.launch {
            try {
                withContext(io) { transfer.write(uri, TemplateFile.encode(template).toByteArray(Charsets.UTF_8)) }
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_file_saved)) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_tw_file_write_failed, e.message.orEmpty())) }
            }
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            withContext(io) { store.delete(templateId) }
            refresh()
        }
    }

    /** Tries the current media in the template, so the sheet can say what will happen before anything is created. */
    private fun recompute() {
        val current = _state.value
        val template = current.selected ?: return
        if (current.missingRequired.isNotEmpty()) {
            _state.update { it.copy(warnings = emptyList(), problem = null) }
            return
        }
        when (val result = TemplateInstantiator.instantiate(template, current.picked.mapValues { it.value.fill })) {
            is EditResult.Success -> _state.update { it.copy(warnings = result.value.warnings, problem = null) }
            is EditResult.Failure -> _state.update { it.copy(warnings = emptyList(), problem = describe(result.error)) }
        }
    }

    private fun describe(error: EditError): UiText = when (error) {
        is EditError.InvalidClip -> UiText.Capitalised(UiText.Raw(error.reason))
        else -> UiText.res(R.string.ed_s3_tw_cannot_fill)
    }

    private fun assetOf(uri: String, probed: ProbedMedia, project: FrameRate): MediaAssetDto {
        val id = "asset-${idGenerator()}"
        if (probed.isImage) {
            return MediaAssetDto(
                id = id, uri = uri, durationFrames = project.microsToFrames(PHOTO_DEFAULT_MICROS).coerceAtLeast(1),
                nativeFpsNum = project.num, nativeFpsDen = project.den, colorSpace = probed.colorSpace,
                hasVideo = false, hasAudio = false, isImage = true, displayName = probed.displayName,
                animationDelaysMs = probed.animationDelaysMs,
                animationPlays = probed.animationPlays.takeIf { probed.animationDelaysMs != null },
            )
        }
        // Audio-only files have no native frame rate; use the project's.
        val (num, den) = if (probed.hasVideo) probed.fpsNum to probed.fpsDen else project.num to project.den
        val frames = FrameRate(num, den).microsToFrames(probed.durationMicros)
        if (frames <= 0) throw FileTooShortException()
        return MediaAssetDto(
            id = id, uri = uri, durationFrames = frames, nativeFpsNum = num, nativeFpsDen = den, colorSpace = probed.colorSpace,
            hasVideo = probed.hasVideo, hasAudio = probed.hasAudio, displayName = probed.displayName,
            videoWidth = probed.videoWidth, videoHeight = probed.videoHeight, videoBitrate = probed.videoBitrate,
            videoCodec = probed.videoCodec, tenBit = probed.tenBit,
        )
    }

    private companion object {
        const val PHOTO_DEFAULT_MICROS = 5_000_000L
    }
}

/** A picked file whose length rounds to no frame at the template's rate. */
private class FileTooShortException : Exception("The file is too short to use") // i18n-ok: log text
