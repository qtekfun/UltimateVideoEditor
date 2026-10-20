package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A map of `key -> number` kept in one small JSON file, read once and rewritten on each new entry (through a temporary file, so an
 * interrupted write keeps the old one). A damaged or unreadable file reads as empty: the caches built on it only hold values that
 * can be measured again.
 */
internal class FileDoubleMap(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), Double.serializer())
    private val values: MutableMap<String, Double> = load()

    @Synchronized
    fun get(key: String): Double? = values[key]

    @Synchronized
    fun put(key: String, value: Double) {
        values[key] = value
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(Json.encodeToString(serializer, values))
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (_: IOException) {
            // Losing a cache entry only costs a re-measurement later.
        }
    }

    private fun load(): MutableMap<String, Double> = try {
        if (file.isFile) Json.decodeFromString(serializer, file.readText()).toMutableMap() else mutableMapOf()
    } catch (_: IOException) {
        mutableMapOf()
    } catch (_: SerializationException) {
        mutableMapOf()
    }
}
