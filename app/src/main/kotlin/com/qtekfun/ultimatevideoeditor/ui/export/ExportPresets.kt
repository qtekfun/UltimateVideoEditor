package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec

/**
 * Starting settings for an upload destination. [shortSide] is the size of the shorter edge
 * (1080 for a 1080p or 1080×1920 movie), [maxFps] the fastest rate worth sending, and [aspect] the
 * shape the platform is made for ("9:16"), shown as a hint when the project has another shape.
 * Bitrates are for SDR H.264/HEVC and follow what each service recommends for uploads.
 */
data class ExportPreset(
    val id: String,
    val label: String,
    val shortSide: Int,
    val maxFps: Int,
    val codec: ExportCodec,
    val bitrateMbps: Int,
    val aspect: String,
)

object ExportPresets {
    val all: List<ExportPreset> = listOf(
        ExportPreset("youtube-1080", "YouTube 1080p", 1080, 60, ExportCodec.H264, 12, "16:9"),
        ExportPreset("youtube-4k", "YouTube 4K", 2160, 60, ExportCodec.HEVC, 35, "16:9"),
        ExportPreset("youtube-shorts", "YouTube Shorts", 1080, 60, ExportCodec.H264, 12, "9:16"),
        ExportPreset("tiktok", "TikTok", 1080, 30, ExportCodec.H264, 8, "9:16"),
        ExportPreset("instagram-reels", "Instagram Reels", 1080, 30, ExportCodec.H264, 8, "9:16"),
        ExportPreset("instagram-feed", "Instagram feed", 1080, 30, ExportCodec.H264, 8, "4:5"),
    )
}

/** The concrete settings a preset resolves to for one project. */
data class PresetChoice(
    val resolution: ResolutionOption,
    val frameRate: FrameRate,
    val codec: ExportCodec,
    val bitrateMbps: Int,
)

/**
 * Fits [preset] to what the project offers: the largest size not above the preset's (never
 * upscaling past the project), the project's own rate unless it is faster than the preset wants,
 * and the preset's bitrate snapped to the nearest choice.
 */
fun resolvePreset(preset: ExportPreset, resolutions: List<ResolutionOption>, rates: List<FrameRate>): PresetChoice {
    require(resolutions.isNotEmpty() && rates.isNotEmpty()) { "no export options to choose from" }
    val resolution = resolutions.filter { it.shortSide <= preset.shortSide }.maxByOrNull { it.shortSide }
        ?: resolutions.minBy { it.shortSide }
    val frameRate = rates.firstOrNull { it.num.toDouble() / it.den <= preset.maxFps + FPS_TOLERANCE }
        ?: rates.minBy { it.num.toDouble() / it.den }
    val bitrate = bitrateChoicesMbps().minBy { kotlin.math.abs(it - preset.bitrateMbps) }
    return PresetChoice(resolution, frameRate, preset.codec, bitrate)
}

private const val FPS_TOLERANCE = 0.01
