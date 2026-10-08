package com.qtekfun.ultimatevideoeditor.domain.relink

import kotlin.math.abs

/** The kind of media a library file is, which a replacement must share (a picture never stands in for a video). */
enum class RelinkKind {
    VIDEO,
    AUDIO,
    IMAGE;

    /** Whether a file of kind [file] can stand in for a missing item of this kind. A video file can carry the sound of a missing audio item. */
    fun accepts(file: RelinkKind): Boolean = this == file || (this == AUDIO && file == VIDEO)

    companion object {
        /** From a MIME type; null for anything that is not media. */
        fun fromMime(mime: String?): RelinkKind? = when {
            mime == null -> null
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            mime.startsWith("image/") -> IMAGE
            else -> null
        }

        /** From the MIME type, else (providers often say only "octet-stream") from the file extension. */
        fun fromFile(mime: String?, name: String): RelinkKind? = fromMime(mime) ?: when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "avi", "ts", "mts", "m2ts" -> VIDEO
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr" -> AUDIO
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp" -> IMAGE
            else -> null
        }
    }
}

/**
 * One missing library item the user wants found. [oldPath] is the folder trail its old address carried (last piece is the
 * file name), as far as that address says; it is just [name] when the address says nothing.
 */
data class WantedMedia(
    val assetId: String,
    val name: String,
    val kind: RelinkKind,
    val oldPath: List<String> = listOf(name),
)

/** A file found in the scanned folder. [path] is relative to the folder the user picked; its last piece is the file name. */
data class FolderFile(
    val uri: String,
    val path: List<String>,
    val kind: RelinkKind?,
    val sizeBytes: Long? = null,
) {
    val name: String get() = path.last()
}

/** How a file was chosen for an item. */
enum class MatchBasis {
    /** The only file with that name (or identical copies of it). */
    NAME,

    /** Several files share the name; the one at the place the earlier matches showed the folder moved to. */
    MOVED_PATH,

    /** Several files share the name; only one agrees with the item's duration and picture size. */
    FACTS,
}

data class FolderMatch(val assetId: String, val file: FolderFile, val basis: MatchBasis)

/** Several different files could be the item and nothing tells them apart: the user chooses. */
data class AmbiguousMatch(val assetId: String, val candidates: List<FolderFile>)

data class FolderMatchResult(
    val matched: List<FolderMatch>,
    val ambiguous: List<AmbiguousMatch>,
    /** Asset ids with no file of that name and kind in the folder. */
    val notFound: List<String>,
)

/**
 * Pure matching of missing media against the files of a folder; no Android, no I/O, so it is tested on the JVM.
 *
 * A file is a candidate when its name equals the item's (ignoring case) and its kind fits. One candidate is
 * accepted. Several candidates of equal size are copies of each other and the first (shortest path) is taken.
 * Several candidates of different (or unknown) size are never settled by name alone: the place the folder moved to
 * (learned from the items found so far) is tried, and what is still open is left to [narrowByFacts] and then to the user.
 */
object FolderRelinkMatcher {

    fun match(wanted: List<WantedMedia>, files: List<FolderFile>): FolderMatchResult {
        val byName = HashMap<String, MutableList<FolderFile>>()
        for (file in files) {
            if (file.kind == null) continue
            byName.getOrPut(key(file.name)) { mutableListOf() } += file
        }
        val candidates = wanted.associate { w ->
            w.assetId to byName[key(w.name)].orEmpty().filter { w.kind.accepts(it.kind!!) }.sortedWith(PATH_ORDER)
        }
        val matched = LinkedHashMap<String, FolderMatch>()
        val moves = mutableListOf<Move>()
        val open = LinkedHashMap<String, WantedMedia>()
        val notFound = mutableListOf<String>()

        for (w in wanted) {
            val list = candidates.getValue(w.assetId)
            val sizes = list.map { it.sizeBytes }
            when {
                list.isEmpty() -> notFound += w.assetId
                list.size == 1 || (sizes.all { it != null } && sizes.toSet().size == 1) -> {
                    matched[w.assetId] = FolderMatch(w.assetId, list.first(), MatchBasis.NAME)
                    Move.of(w.oldPath, list.first().path)?.let { moves += it }
                }
                else -> open[w.assetId] = w
            }
        }

        // The fast path: a folder that moved moved as a whole, so the other items sit at the same relative place.
        var progress = true
        while (progress && open.isNotEmpty()) {
            progress = false
            for (w in open.values.toList()) {
                val expected = moves.mapNotNull { it.expected(w.oldPath) }
                val hits = candidates.getValue(w.assetId).filter { c -> expected.any { samePath(it, c.path) } }
                if (hits.size == 1) {
                    matched[w.assetId] = FolderMatch(w.assetId, hits.single(), MatchBasis.MOVED_PATH)
                    Move.of(w.oldPath, hits.single().path)?.let { moves += it }
                    open.remove(w.assetId)
                    progress = true
                }
            }
        }
        val ambiguous = open.values.map { AmbiguousMatch(it.assetId, candidates.getValue(it.assetId)) }
        return FolderMatchResult(wanted.mapNotNull { matched[it.assetId] }, ambiguous, notFound)
    }

    /** What is known about a file's content; every field is optional because not every file states it. */
    data class Facts(val durationMicros: Long?, val width: Int?, val height: Int?)

    /**
     * Among [candidates] (with their [Facts]) the ones that fit [expected]: a duration within [DURATION_TOLERANCE_FRACTION]
     * (at least [DURATION_TOLERANCE_MICROS]) and the same picture size, wherever both sides state them.
     * Returns the single fit, or null when none or several fit (the item then stays ambiguous).
     */
    fun narrowByFacts(expected: Facts, candidates: List<Pair<FolderFile, Facts>>): FolderFile? =
        candidates.filter { (_, facts) -> fits(expected, facts) }.singleOrNull()?.first

    internal fun fits(expected: Facts, actual: Facts): Boolean {
        val ed = expected.durationMicros
        val ad = actual.durationMicros
        if (ed != null && ad != null && ed > 0 && ad > 0) {
            val tolerance = maxOf(DURATION_TOLERANCE_MICROS, (ed * DURATION_TOLERANCE_FRACTION).toLong())
            if (abs(ed - ad) > tolerance) return false
        }
        if (expected.width != null && actual.width != null && expected.width != actual.width) return false
        if (expected.height != null && actual.height != null && expected.height != actual.height) return false
        return true
    }

    /**
     * A move of a folder, learned from one match: [oldPrefix] became [newPrefix] and the rest of the path is unchanged.
     * The unchanged tail is as long as the two paths agree at their ends.
     */
    private class Move(val oldPrefix: List<String>, val newPrefix: List<String>) {
        fun expected(oldPath: List<String>): List<String>? {
            if (oldPath.size < oldPrefix.size) return null
            for (i in oldPrefix.indices) if (!oldPath[i].equals(oldPrefix[i], ignoreCase = true)) return null
            return newPrefix + oldPath.drop(oldPrefix.size)
        }

        companion object {
            fun of(oldPath: List<String>, newPath: List<String>): Move? {
                var tail = 0
                while (tail < oldPath.size && tail < newPath.size &&
                    oldPath[oldPath.size - 1 - tail].equals(newPath[newPath.size - 1 - tail], ignoreCase = true)
                ) tail++
                if (tail == 0) return null
                return Move(oldPath.dropLast(tail), newPath.dropLast(tail))
            }
        }
    }

    private fun samePath(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.indices.all { a[it].equals(b[it], ignoreCase = true) }

    private fun key(name: String): String = name.trim().lowercase()

    private val PATH_ORDER = compareBy<FolderFile>({ it.path.size }, { it.path.joinToString("/").lowercase() }, { it.uri })

    const val DURATION_TOLERANCE_MICROS = 250_000L
    const val DURATION_TOLERANCE_FRACTION = 0.01
}
