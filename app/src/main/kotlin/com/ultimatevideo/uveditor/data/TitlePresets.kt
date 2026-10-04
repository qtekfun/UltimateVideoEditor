package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.TitleLayerDto
import com.ultimatevideo.uveditor.domain.ImageLayer
import com.ultimatevideo.uveditor.domain.MotionPreset
import com.ultimatevideo.uveditor.domain.PresetFont
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TextTemplate
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayerEdit
import com.ultimatevideo.uveditor.domain.TitleLayers
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A `.uvtitle` file that cannot be read. The message is fit to show to the user. */
class PresetFormatException(message: String) : Exception(message)

/** A font a preset uses: the registry id (a hash of the font file) and its family name. */
@Serializable
data class PresetFontDto(val id: String, val family: String)

/**
 * The `.uvtitle` file: a shareable animated title. It holds the layers (text, shapes and built-in
 * stickers, never photos), the in and out animation and the default text and length. Fonts are listed
 * by id and family but not embedded (font licences are the user's business): a device that lacks them
 * shows the title in the default font and says which fonts are missing.
 */
@Serializable
data class TitlePresetDto(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String,
    val defaultText: String = "",
    val seconds: Double = 4.0,
    val layers: List<TitleLayerDto>,
    val intro: String = "none",
    val outro: String = "none",
    val edgeSeconds: Double = 0.4,
    val fonts: List<PresetFontDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "uvtitle"
        const val VERSION = 1
    }
}

/** Reads and writes `.uvtitle` text. Pure, unit-tested. */
object TitlePresetCodec {
    const val MAX_BYTES = 256 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(template: TextTemplate): String = json.encodeToString(TitlePresetDto.serializer(), toDto(template))

    /** @throws PresetFormatException when [text] is not a valid, supported `.uvtitle` file. */
    fun decode(text: String, id: String): TextTemplate {
        if (text.length > MAX_BYTES) throw PresetFormatException("This preset file is too large")
        val dto = try {
            json.decodeFromString(TitlePresetDto.serializer(), text)
        } catch (e: SerializationException) {
            throw PresetFormatException("This is not a title preset file")
        } catch (e: IllegalArgumentException) {
            throw PresetFormatException("This is not a title preset file")
        }
        if (dto.format != TitlePresetDto.FORMAT) throw PresetFormatException("This is not a title preset file")
        if (dto.version > TitlePresetDto.VERSION) throw PresetFormatException("This preset was made by a newer version of the app")
        val layers = try {
            dto.layers.map { TitleLayerMapper.toLayer("the preset", it) }
        } catch (e: ProjectError.Corrupt) {
            throw PresetFormatException("This preset is damaged: ${e.message}")
        }
        // A photo is a file of some other project: it cannot travel in a preset.
        val portable = layers.filterNot { it is ImageLayer && it.kind == StillKind.PHOTO }
        val template = TextTemplate(
            id = id,
            name = dto.name.trim().ifBlank { "Preset" },
            defaultText = dto.defaultText.ifBlank { "Your text" },
            defaultSeconds = dto.seconds,
            layers = portable,
            intro = motion(dto.intro),
            outro = motion(dto.outro),
            edgeSeconds = dto.edgeSeconds,
            builtIn = false,
            fonts = dto.fonts.map { PresetFont(it.id, it.family) },
        )
        template.problem()?.let { throw PresetFormatException("This preset cannot be used: $it") }
        return template
    }

    private fun toDto(template: TextTemplate) = TitlePresetDto(
        name = template.name,
        defaultText = template.defaultText,
        seconds = template.defaultSeconds,
        layers = template.layers.map(TitleLayerMapper::toDto),
        intro = template.intro.name.lowercase(),
        outro = template.outro.name.lowercase(),
        edgeSeconds = template.edgeSeconds,
        fonts = template.fonts.map { PresetFontDto(it.id, it.family) },
    )

    private fun motion(name: String): MotionPreset =
        MotionPreset.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MotionPreset.NONE
}

/**
 * The app-wide library of the user's saved title presets: one `<id>.uvtitle` file each under [dir]. A
 * file that does not parse is ignored when listing. Everything stays on the device; sharing is the
 * user's choice of where to export the file.
 */
class TitlePresetStore(private val dir: File) {

    /**
     * Saves the title [content] as a preset called [name], with the animation [intro]/[outro]. Photo layers
     * are left out (they point at this project's files). [familyOf] gives the family name of an imported font id.
     * @throws IOException when it cannot be written, @throws PresetFormatException when the title is not a valid preset.
     */
    fun saveFrom(
        name: String,
        content: TitleContent,
        seconds: Double,
        intro: MotionPreset,
        outro: MotionPreset,
        edgeSeconds: Double,
        familyOf: (String) -> String?,
    ): TextTemplate {
        val layers = TitleLayers.of(content).filterNot { it is ImageLayer && it.kind == StillKind.PHOTO }
        val layered = TitleLayerEdit.of(layers)
        val firstText = layered.text.ifBlank { "Your text" }
        val fonts = TitleLayers.fontIds(layered).map { PresetFont(it, familyOf(it) ?: it) }
        val trimmed = name.trim().ifBlank { "My title" }
        val template = TextTemplate(
            id = newId(trimmed, layers),
            name = trimmed,
            defaultText = firstText,
            defaultSeconds = seconds,
            layers = layers,
            intro = intro,
            outro = outro,
            edgeSeconds = edgeSeconds,
            builtIn = false,
            fonts = fonts,
        )
        template.problem()?.let { throw PresetFormatException("This title cannot be saved as a preset: $it") }
        write(template)
        return template
    }

    /** Stores an imported `.uvtitle` [text] as a new preset. @throws PresetFormatException, @throws IOException */
    fun import(text: String): TextTemplate {
        val provisional = TitlePresetCodec.decode(text, "tmp")
        val template = provisional.copy(id = newId(provisional.name, provisional.layers))
        write(template)
        return template
    }

    /** The `.uvtitle` text of preset [id], or null when it does not exist. */
    fun exportText(id: String): String? = list().firstOrNull { it.id == id }?.let(TitlePresetCodec::encode)

    /** All readable presets, sorted by name. */
    fun list(): List<TextTemplate> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty()
        .mapNotNull { file ->
            try {
                TitlePresetCodec.decode(file.readText(), file.name.removeSuffix(EXTENSION))
            } catch (e: PresetFormatException) {
                null
            } catch (e: IOException) {
                null
            }
        }
        .sortedBy { it.name.lowercase() }

    fun delete(id: String): Boolean = File(dir, "${safe(id)}$EXTENSION").delete()

    private fun write(template: TextTemplate) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val target = File(dir, "${safe(template.id)}$EXTENSION")
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(TitlePresetCodec.encode(template))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot save the preset")
        }
    }

    /** `user-` plus a short hash of the name and content, so saving the same title twice replaces it instead of duplicating it. */
    private fun newId(name: String, layers: List<com.ultimatevideo.uveditor.domain.TitleLayer>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$name|$layers".toByteArray())
        return "user-" + digest.take(5).joinToString("") { "%02x".format(it) }
    }

    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private companion object {
        const val EXTENSION = ".uvtitle"
    }
}
