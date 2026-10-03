package com.ultimatevideo.uveditor.ui.editor

/**
 * Stable `Long` keys for string ids. The native canvas identifies clips and assets by `Long`;
 * the editing model uses string ids. Keys are never reused within a registry's lifetime.
 */
class KeyRegistry {
    private val keysById = HashMap<String, Long>()
    private val idsByKey = HashMap<Long, String>()
    private var next = 0L

    fun keyFor(id: String): Long = keysById.getOrPut(id) { newKey(id) }

    fun idFor(key: Long): String? = idsByKey[key]

    /**
     * Gives [id] a fresh key, so native caches kept under the old one (a waveform, a thumbnail strip)
     * are not mistaken for the new content of a relinked file. The old key stops resolving to [id].
     */
    fun rekey(id: String): Long {
        keysById[id]?.let { idsByKey.remove(it) }
        return newKey(id).also { keysById[id] = it }
    }

    private fun newKey(id: String): Long {
        val key = next++
        idsByKey[key] = id
        return key
    }
}
