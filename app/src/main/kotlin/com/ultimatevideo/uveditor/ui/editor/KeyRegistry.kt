package com.ultimatevideo.uveditor.ui.editor

/**
 * Stable `Long` keys for string ids. The native canvas identifies clips and assets by `Long`;
 * the editing model uses string ids. Keys are never reused within a registry's lifetime.
 */
class KeyRegistry {
    private val keysById = HashMap<String, Long>()
    private val idsByKey = HashMap<Long, String>()

    fun keyFor(id: String): Long = keysById.getOrPut(id) {
        val key = keysById.size.toLong()
        idsByKey[key] = id
        key
    }

    fun idFor(key: Long): String? = idsByKey[key]
}
