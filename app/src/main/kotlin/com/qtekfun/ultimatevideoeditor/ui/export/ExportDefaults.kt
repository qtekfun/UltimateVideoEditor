package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import java.math.BigInteger
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * What the video clips actually on the timeline say about quality (SPECS.md 5.10). Library items that no clip uses, photos, titles and
 * audio-only files do not count. Every field is "unknown" (null or false) when the asset predates the probe or the file does not say.
 */
data class UsedSources(
    /** Video clips on the timeline whose asset is a video file. */
    val videoClips: Int = 0,
    /** The highest bit rate among them, in Mbit/s; null when none of them has a known rate. */
    val maxBitrateMbps: Double? = null,
    /** Pixels of the source that has [maxBitrateMbps] (to scale the rate when the output is smaller); null when unknown. */
    val maxBitratePixels: Long? = null,
    val anyHevc: Boolean = false,
    /** A used source is 10-bit or HDR (HLG or PQ). */
    val anyTenBitOrHdr: Boolean = false,
    /** The largest short side among the used sources, 0 when unknown. */
    val maxShortSide: Int = 0,
    /** Any asset of the timeline has audio, so the movie gets an audio track (for the size estimate). */
    val hasAudio: Boolean = false,
) {
    val isEmpty: Boolean get() = videoClips == 0
}

/** Reads [UsedSources] from the clips of [timeline]; [assets] is the project's library. */
fun usedSources(timeline: Timeline, assets: List<MediaAssetDto>): UsedSources {
    val byId = assets.associateBy { it.id }
    var count = 0
    var bestBps = 0L
    var bestPixels: Long? = null
    var hevc = false
    var tenBitOrHdr = false
    var short = 0
    var audio = false
    val seen = HashSet<String>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            val asset = clip.assetId?.let(byId::get) ?: continue
            if (clip.title != null || clip.still != null) continue
            if (asset.hasAudio && !asset.isImage) audio = true
            if (track.type != TrackType.VIDEO || !asset.hasVideo || asset.isImage) continue
            count++
            if (!seen.add(asset.id)) continue
            val w = asset.videoWidth
            val h = asset.videoHeight
            val pixels = if (w != null && h != null) w.toLong() * h else null
            if (w != null && h != null) short = max(short, min(w, h))
            val bps = asset.videoBitrate
            if (bps != null && bps > bestBps) {
                bestBps = bps
                bestPixels = pixels
            }
            if (asset.videoCodec == "hevc") hevc = true
            if (asset.tenBit == true || asset.colorSpace != "Rec709-SDR") tenBitOrHdr = true
        }
    }
    return UsedSources(
        videoClips = count,
        maxBitrateMbps = bestBps.takeIf { it > 0 }?.let { it / 1_000_000.0 },
        maxBitratePixels = bestPixels,
        anyHevc = hevc,
        anyTenBitOrHdr = tenBitOrHdr,
        maxShortSide = short,
        hasAudio = audio,
    )
}

/** The settings the dialog starts on, and why. */
data class ExportRecommendation(
    val bitrateMbps: Int,
    val codec: ExportCodec,
    /** The clips' rate (Mbit/s) the bitrate was derived from at the chosen size, or null when it is today's fixed default. */
    val sourceMbps: Double? = null,
    /** The clips need more than the highest bitrate choice. */
    val capped: Boolean = false,
    /** The sources decided the codec or the bitrate (not the fixed defaults), so the chips are marked as recommended. */
    val fromSources: Boolean = false,
)

private const val H264_HEADROOM = 2.0
private const val RATE_EPSILON = 0.05

/**
 * The bit rate and codec that keep the used sources' quality at the output [width] x [height] and [fps]; [defaultCodec] is what
 * the dialog used before (kept unless the sources call for HEVC). Pure.
 *
 * Bit rate: the highest source rate, scaled down by the pixel ratio when the output has fewer pixels than that source (a 1080p
 * export of an 80 Mbit/s 4K clip needs about a quarter), then the smallest choice at or above it, never below today's fixed default
 * for that size, capped at the highest choice. Codec: HEVC when any used source is HEVC or 10-bit/HDR, or when the needed rate is
 * more than twice what H.264 gets by default at this size (it would need a rate H.264 handles poorly); otherwise [defaultCodec].
 * Nothing known about the sources: today's defaults.
 */
fun recommendExport(width: Int, height: Int, fps: FrameRate, sources: UsedSources, defaultCodec: ExportCodec): ExportRecommendation {
    val needed = neededMbps(width, height, sources)
    val codecByRate = needed != null && needed > suggestedBitrateMbps(width, height, fps, ExportCodec.H264) * H264_HEADROOM
    val codec = if (sources.anyHevc || sources.anyTenBitOrHdr || codecByRate) ExportCodec.HEVC else defaultCodec
    return ExportRecommendation(
        bitrateMbps = recommendedBitrateMbps(width, height, fps, codec, sources),
        codec = codec,
        sourceMbps = needed,
        capped = needed != null && needed > bitrateChoicesMbps().last() + RATE_EPSILON,
        fromSources = needed != null || codec != defaultCodec,
    )
}

/** The bit rate that keeps the used sources' quality for exactly these settings (the user's codec is respected), see [recommendExport]. */
fun recommendedBitrateMbps(width: Int, height: Int, fps: FrameRate, codec: ExportCodec, sources: UsedSources): Int {
    val fixed = suggestedBitrateMbps(width, height, fps, codec)
    val needed = neededMbps(width, height, sources) ?: return fixed
    val choices = bitrateChoicesMbps()
    val covering = choices.firstOrNull { it >= needed - RATE_EPSILON } ?: choices.last()
    return max(covering, fixed)
}

private fun neededMbps(width: Int, height: Int, sources: UsedSources): Double? {
    val rate = sources.maxBitrateMbps ?: return null
    val sourcePixels = sources.maxBitratePixels ?: return rate
    val outPixels = width.toLong() * height
    return if (outPixels >= sourcePixels) rate else rate * outPixels / sourcePixels
}

/** The line under the Bitrate chips, or null when no source rate is known. */
fun bitrateAdvice(recommendation: ExportRecommendation, sources: UsedSources, selectedMbps: Int): String? {
    val clipsMbps = sources.maxBitrateMbps ?: return null
    val shown = formatMbps(clipsMbps)
    val highest = bitrateChoicesMbps().last()
    return when {
        recommendation.capped -> "Your clips go up to $shown Mbps; the highest choice is $highest Mbps"
        selectedMbps >= (recommendation.sourceMbps ?: clipsMbps) - RATE_EPSILON ->
            "Your clips go up to $shown Mbps: $selectedMbps Mbps keeps their quality"
        else -> "Your clips go up to $shown Mbps: at $selectedMbps Mbps some of their quality is lost"
    }
}

private fun formatMbps(mbps: Double): String = if (mbps >= 10) "%.0f".format(mbps) else "%.1f".format(mbps)

/** The estimated size of the finished file, with the spread the encoder's rate control can add or take away. */
data class SizeEstimate(val bytes: Long, val lowBytes: Long, val highBytes: Long)

const val ESTIMATE_AUDIO_BITRATE = 192_000L
private const val CONTAINER_PERMILLE = 10L
private const val LOW_PERMILLE = 800L
private const val HIGH_PERMILLE = 1_100L

/**
 * Bytes of a movie of [movieFrames] project frames at [project] rate, at [videoMbps] plus [audioBitrate] (0 without an audio track),
 * plus 1% for the container. Exports use VBR aimed at the target; a 714.85 s export at 35 Mbps came out at 35.0 Mbit/s on average,
 * so the range is deliberately wide: 80% to 110%. Integer maths with a big intermediate, so no float time. Null for an empty movie.
 */
fun estimateExportSize(movieFrames: Long, project: FrameRate, videoMbps: Int, audioBitrate: Long = ESTIMATE_AUDIO_BITRATE): SizeEstimate? {
    if (movieFrames <= 0 || videoMbps <= 0) return null
    val bitsPerSecond = BigInteger.valueOf(videoMbps.toLong() * 1_000_000L + audioBitrate)
    val bits = bitsPerSecond * BigInteger.valueOf(movieFrames) * BigInteger.valueOf(project.den.toLong())
    val bytes = bits / (BigInteger.valueOf(project.num.toLong()) * BigInteger.valueOf(8))
    val withContainer = bytes * BigInteger.valueOf(1000 + CONTAINER_PERMILLE) / BigInteger.valueOf(1000)
    fun scaled(permille: Long) = (withContainer * BigInteger.valueOf(permille) / BigInteger.valueOf(1000)).toLong()
    return SizeEstimate(scaled(1000), scaled(LOW_PERMILLE), scaled(HIGH_PERMILLE))
}

/** "3.1 GB", "640 MB", "4.5 MB": decimal units, as the file manager shows them. */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(locale, bytes / 1e9)
    bytes >= 10_000_000L -> "%.0f MB".format(locale, bytes / 1e6)
    else -> "%.1f MB".format(locale, bytes / 1e6)
}

/** True when the movie would use more than 90% of the free space (so it may not fit, or leaves the phone nearly full). */
fun exceedsFreeSpace(estimate: SizeEstimate, freeBytes: Long?): Boolean = freeBytes != null && estimate.bytes > freeBytes / 10 * 9
