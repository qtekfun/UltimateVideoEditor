package com.ultimatevideo.uveditor.engine.preview

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Which assets the preview can keep open at once, and which to open or close next. Every open
 * video asset owns a hardware decoder and devices only have a few, so a stack of layers may need
 * more decoders than exist. Pure logic: see [DecoderLimits] for the device query.
 */
data class DecoderPlan(
    /** Assets to show now, topmost first. */
    val render: List<Int>,
    /** Needed assets that do not fit the decoder limit; their layers are left out of the preview. */
    val skipped: List<Int>,
    /** Open assets to close first to make room, least recently used first. */
    val toClose: List<Int>,
)

object DecoderPlanner {
    /**
     * @param maxOpen how many decoders may be open at once (at least 1).
     * @param neededTopFirst assets of the layers under the playhead, topmost layer first. The top
     *   layers win when there are too many.
     * @param openOldestFirst assets currently open, least recently used first.
     */
    fun plan(maxOpen: Int, neededTopFirst: List<Int>, openOldestFirst: List<Int>): DecoderPlan {
        require(maxOpen >= 1) { "at least one decoder is needed" }
        val needed = neededTopFirst.distinct()
        val render = needed.take(maxOpen)
        val skipped = needed.drop(maxOpen)
        val open = openOldestFirst.toSet()
        val opening = render.count { it !in open }
        val overflow = open.size + opening - maxOpen
        val toClose = if (overflow > 0) openOldestFirst.filter { it !in render }.take(overflow) else emptyList()
        return DecoderPlan(render, skipped, toClose)
    }
}

/** Asks the device how many hardware video decoders can run at the same time. */
object DecoderLimits {
    /** Preview never holds more than this, whatever the device reports: each decoder pins frame memory. */
    const val PREVIEW_CAP = 4

    private val PREVIEW_MIME_TYPES = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

    /**
     * The number of decoders the preview may open: the smallest of the per-codec instance limits
     * reported by the hardware H.264 and HEVC decoders, capped at [PREVIEW_CAP]. Falls back to 1 if
     * the device reports nothing usable, which keeps single-layer playback working.
     */
    fun maxPreviewDecoders(): Int {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.isHardwareAccelerated }
        val perMime = PREVIEW_MIME_TYPES.mapNotNull { mime -> bestInstanceLimit(infos, mime) }
        val limit = perMime.minOrNull() ?: return 1
        return limit.coerceIn(1, PREVIEW_CAP)
    }

    /** The most instances any one hardware decoder of [mime] supports, or null if there is none. */
    private fun bestInstanceLimit(infos: List<MediaCodecInfo>, mime: String): Int? =
        infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .maxOfOrNull { info -> info.getCapabilitiesForType(mime).maxSupportedInstances }
}
