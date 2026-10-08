package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.GradeCurvesDto
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
import com.qtekfun.ultimatevideoeditor.domain.GradeLook
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A saved colour grade on disk; see [GradeLook]. [curves] is absent for identity curves. */
@Serializable
internal data class LookDto(
    val id: String,
    val name: String,
    val values: List<Double>,
    val curves: GradeCurvesDto? = null,
)

/**
 * The app-wide library of saved looks, one JSON file per look under [dir] (`<id>.json`). Everything stays on
 * the device. A file that does not parse or fails validation is ignored when listing, never an error: a look
 * is a convenience, not project data.
 */
class LookStore(private val dir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Stores [look] (replacing a look with the same id). @throws IOException when it cannot be written. */
    fun save(look: GradeLook) {
        look.problem()?.let { throw IOException("This look cannot be saved: $it") }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val target = File(dir, "${safeId(look.id)}.json")
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(json.encodeToString(LookDto.serializer(), toDto(look)))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot save the look")
        }
    }

    /** All readable looks, sorted by name. */
    fun list(): List<GradeLook> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull(::read).sortedBy { it.name.lowercase() }

    fun delete(id: String): Boolean = File(dir, "${safeId(id)}.json").delete()

    private fun read(file: File): GradeLook? = try {
        val look = toLook(json.decodeFromString(LookDto.serializer(), file.readText()))
        look.takeIf { it.problem() == null }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun toDto(look: GradeLook) = LookDto(
        id = look.id,
        name = look.name,
        values = look.values,
        curves = look.curves.takeUnless { it.isIdentity }?.let(TimelineMapper::toCurvesDto),
    )

    private fun toLook(dto: LookDto) = GradeLook(
        id = dto.id,
        name = dto.name,
        values = dto.values,
        curves = dto.curves?.let(TimelineMapper::toCurves) ?: GradeCurves.IDENTITY,
    )

    /** File-name safe form of an id (ids are generated, but never trust a name that reaches the file system). */
    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
}
