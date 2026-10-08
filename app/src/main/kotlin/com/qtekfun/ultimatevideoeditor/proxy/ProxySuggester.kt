package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto

enum class SuggestionReason { HEAVY_MEDIA, DROPPED_FRAMES }

/** A dismissible offer to make proxies. Never acted on without the user's consent. */
data class ProxySuggestion(val reason: SuggestionReason, val assetIds: List<String>)

object ProxySuggester {
    /** A video with this short side or more (1440p and up) is heavy to scrub on most phones. */
    const val HEAVY_SHORT_SIDE = 1440

    /** ... as is a stream at or above this bitrate, whatever its size. */
    const val HEAVY_BITRATE_BPS = 50_000_000L

    /** Stalls reported by the preview, in one session, after which the offer is made even for lighter media. */
    const val STALL_THRESHOLD = 3

    fun isHeavy(info: SourceInfo): Boolean = info.shortSide >= HEAVY_SHORT_SIDE || info.bitrateBps >= HEAVY_BITRATE_BPS

    /**
     * The offer to show, or null. It is made only while proxies are off and the user has not dismissed it,
     * for heavy assets that have no proxy yet, or, after [STALL_THRESHOLD] preview stalls, for every video
     * without one. [infoOf] returns null for assets that were not (or cannot be) probed.
     */
    fun evaluate(
        assets: List<MediaAssetDto>,
        infoOf: (MediaAssetDto) -> SourceInfo?,
        hasProxy: (MediaAssetDto) -> Boolean,
        stalls: Int,
        enabled: Boolean,
        dismissed: Boolean,
    ): ProxySuggestion? {
        if (enabled || dismissed) return null
        val candidates = assets.filter { it.canHaveProxy() && !hasProxy(it) }
        val heavy = candidates.filter { asset -> infoOf(asset)?.let(::isHeavy) == true }
        return when {
            heavy.isNotEmpty() -> ProxySuggestion(SuggestionReason.HEAVY_MEDIA, heavy.map { it.id })
            stalls >= STALL_THRESHOLD && candidates.isNotEmpty() -> ProxySuggestion(SuggestionReason.DROPPED_FRAMES, candidates.map { it.id })
            else -> null
        }
    }
}
