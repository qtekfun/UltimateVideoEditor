package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.templates.Placeholder
import com.qtekfun.ultimatevideoeditor.domain.templates.PlaceholderKind
import com.qtekfun.ultimatevideoeditor.domain.templates.ProjectTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A `.uvtemplate` file that cannot be used, with a message fit to show the user. */
class TemplateFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class PlaceholderDto(
    val id: String,
    val name: String,
    /** `video`, `video-or-photo`, `photo` or `audio`. */
    val kind: String,
    val frames: Long,
    val minFrames: Long = 1,
    val optional: Boolean = false,
)

/**
 * The `.uvtemplate` file: a JSON document holding the structure of a project (its settings, tracks, titles,
 * effects, transitions and manual markers, exactly as `project.json` writes them) plus the [placeholders]. It never
 * holds media: the media library is empty and the clips of the placeholders point at `slot:<id>`.
 */
@Serializable
data class TemplateFileDto(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val id: String,
    val name: String,
    val description: String = "",
    val project: ProjectDto,
    val placeholders: List<PlaceholderDto>,
) {
    companion object {
        const val FORMAT = "uvtemplate"
        const val VERSION = 1
    }
}

object TemplateFile {
    /** A template is a few kilobytes; anything bigger is not one. */
    const val MAX_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(template: ProjectTemplate): String {
        val base = ProjectDto(
            id = template.id,
            name = template.name,
            settings = ProjectSettingsDto(template.width, template.height, template.fpsNum, template.fpsDen, template.colorSpace),
        )
        val project = TimelineMapper.toDto(base, template.timeline, emptyList())
        val dto = TemplateFileDto(
            id = template.id,
            name = template.name,
            description = template.description,
            project = project,
            placeholders = template.placeholders.map { PlaceholderDto(it.id, it.name, kindName(it.kind), it.frames, it.minFrames, it.optional) },
        )
        return json.encodeToString(dto)
    }

    /** @throws TemplateFormatException when [text] is not a usable template. */
    fun decode(text: String): ProjectTemplate {
        if (text.length > MAX_BYTES) throw TemplateFormatException("This file is too big to be a template")
        val dto = try {
            json.decodeFromString<TemplateFileDto>(text)
        } catch (e: IllegalArgumentException) {
            throw TemplateFormatException("This is not a ultimateVE template file", e)
        }
        if (dto.format != TemplateFileDto.FORMAT) throw TemplateFormatException("This is not a ultimateVE template file")
        if (dto.version > TemplateFileDto.VERSION) throw TemplateFormatException("This template was made by a newer version of the app")
        if (dto.project.mediaLibrary.isNotEmpty()) throw TemplateFormatException("A template cannot contain media")
        val timeline = try {
            TimelineMapper.toTimeline(dto.project)
        } catch (e: ProjectError) {
            throw TemplateFormatException("The template's timeline is not valid: ${e.message}", e)
        }
        // Every clip that plays media must be a placeholder: a real asset would point at a file that is not there.
        for (clip in timeline.tracks.flatMap { it.clips }) {
            val isSlot = Placeholder.idOfSlotAsset(clip.assetId) != null
            val needsMedia = clip.hasMedia || clip.still == StillKind.PHOTO
            if (needsMedia && !isSlot) throw TemplateFormatException("The template refers to media that is not part of it")
        }
        val placeholders = try {
            dto.placeholders.map { Placeholder(it.id, it.name, parseKind(it.kind), it.frames, it.minFrames, it.optional) }
        } catch (e: IllegalArgumentException) {
            throw TemplateFormatException("A placeholder of the template is not valid: ${e.message}", e)
        }
        val settings = dto.project.settings
        val template = ProjectTemplate(
            id = dto.id,
            name = dto.name,
            description = dto.description,
            width = settings.width,
            height = settings.height,
            fpsNum = settings.fpsNum,
            fpsDen = settings.fpsDen,
            colorSpace = settings.colorSpace,
            timeline = timeline,
            placeholders = placeholders,
        )
        template.problem()?.let { throw TemplateFormatException("The template is not valid: $it") }
        return template
    }

    private fun kindName(kind: PlaceholderKind): String = kind.name.lowercase().replace('_', '-')

    private fun parseKind(name: String): PlaceholderKind =
        PlaceholderKind.entries.firstOrNull { kindName(it) == name } ?: throw IllegalArgumentException("unknown kind '$name'")
}
