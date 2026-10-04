package com.ultimatevideo.uveditor.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.ClipPeeker
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.TemplateFile
import com.ultimatevideo.uveditor.data.TemplateFormatException
import com.ultimatevideo.uveditor.data.TemplateStore
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.templates.Placeholder
import com.ultimatevideo.uveditor.domain.templates.ProjectTemplate
import com.ultimatevideo.uveditor.domain.templates.SlotFill
import com.ultimatevideo.uveditor.domain.templates.TemplateBuilder
import com.ultimatevideo.uveditor.domain.templates.TemplateInstantiator
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
    val problem: String? = null,
    val message: String? = null,
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
            } catch (e: MediaImportException) {
                _state.update { it.copy(busyPlaceholder = null, message = e.message ?: "That file cannot be used") }
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
                _state.update { it.copy(busy = false, message = "The project could not be created: ${e.message}") }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = "The project could not be saved: ${e.message}") }
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
                        name = "${dto.name} template",
                        description = "Made from the project \"${dto.name}\".",
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
                    _state.update { it.copy(message = "That project has no clips to turn into slots") }
                } else {
                    _state.update { it.copy(message = "Saved \"${template.name}\" with ${template.placeholders.size} slots") }
                    refresh()
                }
            } catch (e: ProjectError) {
                _state.update { it.copy(message = "The project could not be read: ${e.message}") }
            } catch (e: IOException) {
                _state.update { it.copy(message = "The template could not be saved: ${e.message}") }
            }
        }
    }

    fun importTemplate(uri: String) {
        viewModelScope.launch {
            try {
                val stored = withContext(io) { store.import(String(transfer.read(uri), Charsets.UTF_8)) }
                _state.update { it.copy(message = "Added \"${stored.name}\"") }
                refresh()
            } catch (e: TemplateFormatException) {
                _state.update { it.copy(message = e.message ?: "That is not a template file") }
            } catch (e: IOException) {
                _state.update { it.copy(message = "The file could not be read: ${e.message}") }
            }
        }
    }

    fun exportTemplate(templateId: String, uri: String) {
        val template = _state.value.templates.firstOrNull { it.id == templateId } ?: return
        viewModelScope.launch {
            try {
                withContext(io) { transfer.write(uri, TemplateFile.encode(template).toByteArray(Charsets.UTF_8)) }
                _state.update { it.copy(message = "Saved the template file") }
            } catch (e: IOException) {
                _state.update { it.copy(message = "The file could not be written: ${e.message}") }
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

    private fun describe(error: EditError): String = when (error) {
        is EditError.InvalidClip -> error.reason.replaceFirstChar { it.uppercase() }
        else -> "The template could not be filled with these files"
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
        if (frames <= 0) throw MediaImportException("The file is too short to use")
        return MediaAssetDto(
            id = id, uri = uri, durationFrames = frames, nativeFpsNum = num, nativeFpsDen = den, colorSpace = probed.colorSpace,
            hasVideo = probed.hasVideo, hasAudio = probed.hasAudio, displayName = probed.displayName,
        )
    }

    private companion object {
        const val PHOTO_DEFAULT_MICROS = 5_000_000L
    }
}
