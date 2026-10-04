package com.ultimatevideo.uveditor.ui.library

import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.ui.editor.tray.colourBadge
import com.ultimatevideo.uveditor.ui.editor.tray.kind

/** What the library lists. [UNUSED] is the files no clip on the timeline refers to. */
enum class LibraryFilter(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    AUDIO("Audio"),
    IMAGE("Images"),
    UNUSED("Unused"),
}

/** Search text (matched against name, tags and note), a kind filter and an optional tag. */
data class LibraryQuery(
    val text: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val tag: String? = null,
)

/** One file as the library lists it. */
data class LibraryItem(
    val asset: MediaAssetDto,
    val kind: AssetKind,
    val name: String,
    /** How many clips on the timeline use the file. */
    val usage: Int,
    val tags: List<String>,
    val note: String?,
    val missing: Boolean,
    /** "HLG" or "PQ" for HDR media; null for ordinary SDR files. */
    val badge: String?,
    val durationFrames: Long,
)

/** A place where a library file is used on the timeline: the clip, its lane label and where it starts. */
data class ClipUse(val clipId: String, val trackLabel: String, val startFrame: Long)

/**
 * Pure queries and edits of the media library: filtering and search, tags and notes, where a file is
 * used and the cleanup of files nothing uses. The library itself is `ProjectDto.mediaLibrary`.
 */
object Library {
    const val MAX_TAGS = 16
    const val MAX_TAG_LENGTH = 32
    const val MAX_NOTE_LENGTH = 280

    fun items(assets: List<MediaAssetDto>, usage: Map<String, Int>, missing: Set<String>, query: LibraryQuery): List<LibraryItem> {
        val needle = query.text.trim()
        val tag = query.tag?.trim()?.takeIf { it.isNotEmpty() }
        return assets.asSequence()
            .map { asset ->
                LibraryItem(
                    asset = asset,
                    kind = asset.kind,
                    name = MissingMedia.nameOf(asset),
                    usage = usage[asset.id] ?: 0,
                    tags = asset.tags,
                    note = asset.note,
                    missing = asset.id in missing,
                    badge = colourBadge(asset),
                    durationFrames = asset.durationFrames,
                )
            }
            .filter { item ->
                when (query.filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.VIDEO -> item.kind == AssetKind.VIDEO
                    LibraryFilter.AUDIO -> item.kind == AssetKind.AUDIO
                    LibraryFilter.IMAGE -> item.kind == AssetKind.PHOTO
                    LibraryFilter.UNUSED -> item.usage == 0
                }
            }
            .filter { item -> tag == null || item.tags.any { it.equals(tag, ignoreCase = true) } }
            .filter { item ->
                needle.isEmpty() ||
                    item.name.contains(needle, ignoreCase = true) ||
                    item.tags.any { it.contains(needle, ignoreCase = true) } ||
                    (item.note?.contains(needle, ignoreCase = true) == true)
            }
            .toList()
    }

    /** Every tag in use with the number of files that carry it, most used first, then alphabetically. */
    fun allTags(assets: List<MediaAssetDto>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Pair<String, Int>>() // lower-case key -> (display, count)
        for (asset in assets) for (tag in asset.tags) {
            val key = tag.lowercase()
            val current = counts[key]
            counts[key] = (current?.first ?: tag) to ((current?.second ?: 0) + 1)
        }
        return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }

    /** Tags as typed (comma separated) turned into a clean list; see [normaliseTags]. */
    fun parseTags(text: String): List<String> = normaliseTags(text.split(','))

    /** Trims, drops blanks and commas, caps the length, removes duplicates ignoring case and keeps at most [MAX_TAGS]. */
    fun normaliseTags(input: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in input) {
            val tag = raw.replace(',', ' ').trim().replace(Regex("\\s+"), " ").take(MAX_TAG_LENGTH).trim()
            if (tag.isEmpty() || !seen.add(tag.lowercase())) continue
            out += tag
            if (out.size == MAX_TAGS) break
        }
        return out
    }

    /** A note trimmed and capped; a blank note is null. */
    fun cleanNote(text: String?): String? = text?.trim()?.take(MAX_NOTE_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    fun withTags(assets: List<MediaAssetDto>, assetId: String, tags: List<String>): List<MediaAssetDto> =
        assets.map { if (it.id == assetId) it.copy(tags = normaliseTags(tags)) else it }

    fun withNote(assets: List<MediaAssetDto>, assetId: String, note: String?): List<MediaAssetDto> =
        assets.map { if (it.id == assetId) it.copy(note = cleanNote(note)) else it }

    /** Where [assetId] is used, in timeline order (start frame, then lane). Titles and stickers use no file. */
    fun uses(timeline: Timeline, assetId: String, fps: FrameRate): List<ClipUse> {
        val out = ArrayList<ClipUse>()
        timeline.tracks.forEachIndexed { index, track ->
            for (clip in track.clips) {
                if (clip.assetId == assetId && (clip.hasMedia || clip.still == com.ultimatevideo.uveditor.domain.StillKind.PHOTO)) {
                    out += ClipUse(clip.id, MissingMedia.trackLabel(timeline, index), clip.timelineStart.value)
                }
            }
        }
        return out.sortedWith(compareBy({ it.startFrame }, { it.trackLabel }))
    }

    /** The use after [afterFrame] (strictly), wrapping to the first one; null when the file is not used. Calling it again from the result steps through all uses. */
    fun nextUse(uses: List<ClipUse>, afterFrame: Long): ClipUse? = uses.firstOrNull { it.startFrame > afterFrame } ?: uses.firstOrNull()

    /** The library file the clip [clipId] reads, or null for a title, a sticker or an unknown clip. */
    fun assetOfClip(timeline: Timeline, clipId: String): String? {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return null
        return clip.assetId?.takeIf { clip.hasMedia || clip.still == com.ultimatevideo.uveditor.domain.StillKind.PHOTO }
    }

    /** Files that no clip uses. */
    fun unused(assets: List<MediaAssetDto>, usage: Map<String, Int>): List<MediaAssetDto> = assets.filter { (usage[it.id] ?: 0) == 0 }

    /** [assets] without the unused ones, in the same order. */
    fun withoutUnused(assets: List<MediaAssetDto>, usage: Map<String, Int>): List<MediaAssetDto> = assets.filter { (usage[it.id] ?: 0) > 0 }
}
