package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Timeline

/** Tabs of the media tray. Only [MEDIA] and [AUDIO] list project assets. */
enum class TrayTab(val label: String) {
    MEDIA("Media"),
    STICKERS("Stickers"),
    TEMPLATES("Titles"),
    AUDIO("Audio"),
}

/** What the media tab shows. [UNUSED] lists assets that no clip on the timeline refers to. */
enum class AssetFilter(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    PHOTO("Photos"),
    UNUSED("Unused"),
}

enum class TrayLayout { GRID, LIST }

/** The snap heights of the tray when it is a bottom panel. */
enum class TrayHeight {
    COLLAPSED,
    HALF,
    FULL,
    ;

    fun taller(): TrayHeight = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    fun shorter(): TrayHeight = entries[(ordinal - 1).coerceAtLeast(0)]
}

/** Everything the tray remembers about how it is shown. Pure data: the editor state holds the assets. */
data class TrayState(
    val tab: TrayTab = TrayTab.MEDIA,
    val filter: AssetFilter = AssetFilter.ALL,
    val query: String = "",
    val layout: TrayLayout = TrayLayout.GRID,
    val height: TrayHeight = TrayHeight.COLLAPSED,
) {
    /** Selects [tab] and makes sure a bottom tray is tall enough to show it. */
    fun open(tab: TrayTab): TrayState = copy(tab = tab, height = if (height == TrayHeight.COLLAPSED) TrayHeight.HALF else height)
}

enum class AssetKind { VIDEO, PHOTO, AUDIO }

val MediaAssetDto.kind: AssetKind
    get() = when {
        isImage -> AssetKind.PHOTO
        hasVideo -> AssetKind.VIDEO
        else -> AssetKind.AUDIO
    }

/** One asset as the tray lists it. */
data class TrayItem(
    val asset: MediaAssetDto,
    val kind: AssetKind,
    val name: String,
    /** How many clips on the timeline use the asset. */
    val usage: Int,
    val missing: Boolean,
)

/** Number of timeline clips per asset id. */
fun usageCounts(timeline: Timeline): Map<String, Int> {
    val counts = HashMap<String, Int>()
    for (track in timeline.tracks) for (clip in track.clips) {
        val id = clip.assetId ?: continue
        counts[id] = (counts[id] ?: 0) + 1
    }
    return counts
}

/**
 * The assets the tray lists for [tab], in library order (which is the order the user arranged them in),
 * narrowed by [filter] and a case-insensitive [query] on the file name. The audio tab ignores the filter
 * but not the query; tabs without assets list nothing.
 */
fun trayItems(
    assets: List<MediaAssetDto>,
    usage: Map<String, Int>,
    missing: Set<String>,
    tab: TrayTab,
    filter: AssetFilter,
    query: String,
): List<TrayItem> {
    if (tab != TrayTab.MEDIA && tab != TrayTab.AUDIO) return emptyList()
    val needle = query.trim()
    return assets.asSequence()
        .filter { asset ->
            val kind = asset.kind
            when (tab) {
                TrayTab.AUDIO -> kind == AssetKind.AUDIO
                else -> kind != AssetKind.AUDIO && when (filter) {
                    AssetFilter.ALL -> true
                    AssetFilter.VIDEO -> kind == AssetKind.VIDEO
                    AssetFilter.PHOTO -> kind == AssetKind.PHOTO
                    AssetFilter.UNUSED -> (usage[asset.id] ?: 0) == 0
                }
            }
        }
        .map { TrayItem(it, it.kind, MissingMedia.nameOf(it), usage[it.id] ?: 0, it.id in missing) }
        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        .toList()
}

/**
 * Moves asset [assetId] so it ends up at index [toIndex] of the library (clamped), keeping the others in
 * order. An unknown id, or a move that changes nothing, returns the same list.
 */
fun moveAsset(assets: List<MediaAssetDto>, assetId: String, toIndex: Int): List<MediaAssetDto> {
    val from = assets.indexOfFirst { it.id == assetId }
    if (from < 0) return assets
    val target = toIndex.coerceIn(0, assets.lastIndex)
    if (target == from) return assets
    val result = assets.toMutableList()
    result.add(target, result.removeAt(from))
    return result
}

/** "HLG" or "PQ" for HDR media, null for ordinary SDR files (no badge is shown for those). */
fun colourBadge(asset: MediaAssetDto): String? = when {
    asset.colorSpace.contains("HLG", ignoreCase = true) -> "HLG"
    asset.colorSpace.contains("PQ", ignoreCase = true) || asset.colorSpace.contains("2084") -> "PQ"
    else -> null
}
