package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.CURRENT_SCHEMA_VERSION
import com.ultimatevideo.uveditor.data.model.ProjectDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Codec for `project.json`. Fields this build does not know about are preserved: saving overlays
 * the typed DTO on top of the JSON that was previously on disk, so a newer or third-party field
 * survives a load/save cycle.
 */
object ProjectJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(text: String): ProjectDto {
        val dto = try {
            json.decodeFromString<ProjectDto>(text)
        } catch (e: IllegalArgumentException) {
            throw ProjectError.Corrupt(e.message ?: "invalid JSON", e)
        }
        if (dto.version > CURRENT_SCHEMA_VERSION) {
            throw ProjectError.UnsupportedVersion(dto.version, CURRENT_SCHEMA_VERSION)
        }
        return dto
    }

    fun parseObject(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: throw ProjectError.Corrupt("root is not a JSON object")
    } catch (e: IllegalArgumentException) {
        throw ProjectError.Corrupt(e.message ?: "invalid JSON", e)
    }

    /** Encodes [dto]; keys present only in [base] are carried over. */
    fun encode(dto: ProjectDto, base: JsonObject? = null): String {
        val typed = json.encodeToJsonElement(ProjectDto.serializer(), dto)
        val merged = if (base == null) typed else overlay(base, typed)
        return json.encodeToString(JsonElement.serializer(), merged)
    }

    /** Re-serialises [raw] with a new identity, keeping every other field untouched. */
    fun withIdentity(raw: JsonObject, id: String, name: String? = null): String {
        val updated = buildMap {
            putAll(raw)
            put("id", JsonPrimitive(id))
            if (name != null) put("name", JsonPrimitive(name))
        }
        return json.encodeToString(JsonElement.serializer(), JsonObject(updated))
    }

    fun nameOf(raw: JsonObject): String? = raw["name"]?.jsonPrimitive?.contentOrNull

    private fun overlay(base: JsonElement?, update: JsonElement): JsonElement = when {
        base is JsonObject && update is JsonObject -> JsonObject(
            buildMap {
                putAll(base)
                for ((key, value) in update) put(key, overlay(base[key], value))
            },
        )
        base is JsonArray && update is JsonArray -> JsonArray(
            update.map { item -> overlay(base.matching(item), item) },
        )
        else -> update
    }

    /** Array elements are matched by their `id` so edits and reorders keep unknown fields. */
    private fun JsonArray.matching(item: JsonElement): JsonElement? {
        val id = (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
        return firstOrNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == id }
    }
}
