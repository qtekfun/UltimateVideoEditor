package com.qtekfun.ultimatevideoeditor.data.folders

import android.content.SharedPreferences

/** The folder the person was last looking at: which linked folder, and the path down from its top. [trail] pairs a document id with its name. */
data class BrowsePosition(val linkedId: String, val trail: List<Pair<String, String>>)

/** Where the last position is kept, so the tab opens where it was left. */
interface BrowsePositionStore {
    fun load(): BrowsePosition?

    /** Null forgets it (the person went back to the list of linked folders). */
    fun save(position: BrowsePosition?)
}

object NoBrowsePositionStore : BrowsePositionStore {
    override fun load(): BrowsePosition? = null

    override fun save(position: BrowsePosition?) = Unit
}

/**
 * The position as one line: the linked folder's id, then each step as `id TAB name`, all separated by a line break, with `%`, tab and line
 * breaks written as `%25`, `%09`, `%0A`, `%0D`. Reading is forgiving: anything odd is a position that is not there.
 */
object BrowsePositionCodec {
    fun encode(position: BrowsePosition): String =
        (listOf(escape(position.linkedId)) + position.trail.map { (id, name) -> escape(id) + "\t" + escape(name) }).joinToString("\n")

    fun decode(text: String?): BrowsePosition? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        val linkedId = unescape(lines.first())
        if (linkedId.isEmpty()) return null
        val trail = lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0].isEmpty()) null else unescape(parts[0]) to unescape(parts[1])
        }
        return if (trail.isEmpty()) null else BrowsePosition(linkedId, trail)
    }

    private fun escape(text: String) = text.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

    private fun unescape(text: String) = text.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
}

class PrefsBrowsePositionStore(private val prefs: SharedPreferences) : BrowsePositionStore {
    override fun load(): BrowsePosition? = BrowsePositionCodec.decode(prefs.getString(KEY, null))

    override fun save(position: BrowsePosition?) {
        prefs.edit().apply { if (position == null) remove(KEY) else putString(KEY, BrowsePositionCodec.encode(position)) }.apply()
    }

    companion object {
        const val FILE = "linked_folders"
        private const val KEY = "position"
    }
}
