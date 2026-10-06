package com.ultimatevideo.uveditor.domain.stillframe

import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.renderClips

/*
 * "Save frame as image" (SPECS 5.35): the pure part. Sizes, how the project's picture is fitted or cropped into the
 * chosen shape, the JPEG quality that keeps a file under a limit, file names, and what the frame under the playhead
 * contains. Positions are integer frames, as everywhere else; no Android types.
 */

enum class FrameFormat(val extension: String, val mime: String, val label: String) {
    PNG("png", "image/png", "PNG"),
    JPEG("jpg", "image/jpeg", "JPEG"),
}

/** What the picture contains: everything the exporter would draw at the frame, or only the selected clip. */
enum class FrameSource { WHOLE_PICTURE, SELECTED_CLIP }

/**
 * What happens when the chosen shape differs from the project's: [LETTERBOX] keeps the whole project picture and fills
 * the rest with black bars (what an export to that shape does); [FILL] scales the picture until it covers the shape and
 * crops the overflow, centred.
 */
enum class FrameFit { LETTERBOX, FILL }

enum class FrameSizePreset(val label: String, val width: Int, val height: Int) {
    PROJECT("Project size", 0, 0),
    YOUTUBE_THUMBNAIL("YouTube thumbnail 1280 x 720", 1280, 720),
    FULL_HD("Full HD 1920 x 1080", 1920, 1080),
    VERTICAL_COVER("Vertical cover 1080 x 1920 (TikTok, Reels, Shorts)", 1080, 1920),
    SQUARE("Square 1080 x 1080", 1080, 1080),
    CUSTOM("Custom width", 0, 0),
    ;

    /** The preset has a fixed shape, which may differ from the project's. */
    val isFixed: Boolean get() = width > 0
}

/** Longest side of a saved picture: 4096 px keeps the readback and the bitmap near 64 MB. */
const val MAX_FRAME_SIDE = 4096

/** Longest side of the surface the picture is drawn on (larger than the picture when it is cropped). */
const val MAX_RENDER_SIDE = 8192
const val MIN_CUSTOM_WIDTH = 16

/** YouTube's thumbnail limit is 2 MB; a file a little under 2,000,000 bytes is under it however a megabyte is counted. */
const val YOUTUBE_THUMBNAIL_MAX_BYTES = 2_000_000L

const val DEFAULT_JPEG_QUALITY = 92
const val MIN_JPEG_QUALITY = 1
const val MAX_JPEG_QUALITY = 100

data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
    }
}

/** The size of the saved picture; [reduced] when the wish exceeded [MAX_FRAME_SIDE] and was scaled down. */
data class FrameTarget(val size: FrameSize, val reduced: Boolean)

private fun divRound(a: Long, b: Long): Long = (a * 2 + b) / (b * 2)

private fun divCeil(a: Long, b: Long): Long = (a + b - 1) / b

/** Fits [width] x [height] inside [limit] on both sides, keeping its shape; the second value tells whether it had to shrink. */
private fun capped(width: Long, height: Long, limit: Int): Pair<FrameSize, Boolean> {
    if (width <= limit && height <= limit) return FrameSize(width.toInt(), height.toInt()) to false
    val long = maxOf(width, height)
    val w = (width * limit / long).coerceAtLeast(1)
    val h = (height * limit / long).coerceAtLeast(1)
    return FrameSize(w.toInt(), h.toInt()) to true
}

/**
 * The output size for [preset]. [PROJECT][FrameSizePreset.PROJECT] is the project's own size (odd sizes are kept: an
 * image has no even-size rule), [CUSTOM][FrameSizePreset.CUSTOM] takes [customWidth] (clamped to
 * [MIN_CUSTOM_WIDTH]..[MAX_FRAME_SIDE]) and the project's shape, and the fixed presets are what they say. Nothing
 * exceeds [MAX_FRAME_SIDE].
 */
fun frameTarget(preset: FrameSizePreset, customWidth: Int, projectWidth: Int, projectHeight: Int): FrameTarget {
    require(projectWidth > 0 && projectHeight > 0) { "invalid project size ${projectWidth}x$projectHeight" }
    return when (preset) {
        FrameSizePreset.PROJECT -> capped(projectWidth.toLong(), projectHeight.toLong(), MAX_FRAME_SIDE).let { FrameTarget(it.first, it.second) }
        FrameSizePreset.CUSTOM -> {
            val width = customWidth.coerceIn(MIN_CUSTOM_WIDTH, MAX_FRAME_SIDE).toLong()
            val height = divRound(width * projectHeight, projectWidth.toLong()).coerceAtLeast(1)
            // A very tall project: the height limit decides, the width follows it.
            val (size, shrunk) = capped(width, height, MAX_FRAME_SIDE)
            FrameTarget(size, shrunk)
        }
        else -> FrameTarget(FrameSize(preset.width, preset.height), false)
    }
}

/**
 * How the picture is drawn: on a surface of [renderWidth] x [renderHeight] (the project's picture letterboxed into it by
 * the compositor, like an export), of which the [outWidth] x [outHeight] rectangle at ([cropX], [cropY]) (y down) is saved.
 * When the picture is cropped ([FrameFit.FILL] with a different shape) the surface is larger than the output.
 */
data class FrameRenderPlan(
    val renderWidth: Int,
    val renderHeight: Int,
    val cropX: Int,
    val cropY: Int,
    val outWidth: Int,
    val outHeight: Int,
    /** The output had to be made smaller than asked so that the surface it needs stays within [MAX_RENDER_SIDE]. */
    val shrunk: Boolean = false,
) {
    val isCropped: Boolean get() = renderWidth != outWidth || renderHeight != outHeight
}

/** Where the compositor puts the [canvasWidth] x [canvasHeight] picture on a surface (integer maths of `letterbox` in layout_math.h). */
internal data class Placed(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun letterboxPlacement(canvasWidth: Int, canvasHeight: Int, surfaceWidth: Int, surfaceHeight: Int): Placed {
    var w = surfaceWidth
    var h = surfaceHeight
    if (canvasWidth.toLong() * surfaceHeight > surfaceWidth.toLong() * canvasHeight) {
        h = (surfaceWidth.toLong() * canvasHeight / canvasWidth).toInt()
    } else {
        w = (surfaceHeight.toLong() * canvasWidth / canvasHeight).toInt()
    }
    return Placed((surfaceWidth - w) / 2, (surfaceHeight - h) / 2, w, h)
}

/**
 * Plans the drawing of a [project] picture into [target]. With [FrameFit.LETTERBOX], or when the shapes agree, the
 * surface is the output. With [FrameFit.FILL] the surface is the project's shape scaled up until it covers the output,
 * and the output is its centre; the surface is grown by a pixel or two where the compositor's rounding would leave a
 * sliver of black at an edge, so the crop is always fully covered by the picture.
 */
fun planFrameRender(target: FrameSize, projectWidth: Int, projectHeight: Int, fit: FrameFit): FrameRenderPlan {
    require(projectWidth > 0 && projectHeight > 0) { "invalid project size ${projectWidth}x$projectHeight" }
    val sameShape = target.width.toLong() * projectHeight == target.height.toLong() * projectWidth
    if (fit == FrameFit.LETTERBOX || sameShape) {
        return FrameRenderPlan(target.width, target.height, 0, 0, target.width, target.height)
    }
    var outW = target.width
    var outH = target.height
    var shrunk = false
    while (true) {
        val widthDecides = outW.toLong() * projectHeight >= outH.toLong() * projectWidth
        var renderW: Long
        var renderH: Long
        if (widthDecides) {
            renderW = outW.toLong()
            renderH = divCeil(outW.toLong() * projectHeight, projectWidth.toLong())
        } else {
            renderH = outH.toLong()
            renderW = divCeil(outH.toLong() * projectWidth, projectHeight.toLong())
        }
        // The compositor rounds the picture's placement down: grow until it covers the whole output rectangle.
        for (i in 0 until MAX_COVER_GROWTH) {
            val placed = letterboxPlacement(projectWidth, projectHeight, renderW.toInt(), renderH.toInt())
            val cx = (renderW - outW) / 2
            val cy = (renderH - outH) / 2
            if (placed.x <= cx && placed.y <= cy && placed.x + placed.width >= cx + outW && placed.y + placed.height >= cy + outH) break
            if (widthDecides) renderH += 1 else renderW += 1
        }
        if (renderW <= MAX_RENDER_SIDE && renderH <= MAX_RENDER_SIDE) {
            return FrameRenderPlan(
                renderWidth = renderW.toInt(),
                renderHeight = renderH.toInt(),
                cropX = ((renderW - outW) / 2).toInt(),
                cropY = ((renderH - outH) / 2).toInt(),
                outWidth = outW,
                outHeight = outH,
                shrunk = shrunk,
            )
        }
        // The surface would be too large: make the output smaller (same shape) and plan again.
        val over = maxOf(renderW, renderH).toDouble() / MAX_RENDER_SIDE
        outW = (outW / over).toInt().coerceAtLeast(1)
        outH = (outH / over).toInt().coerceAtLeast(1)
        shrunk = true
    }
}

private const val MAX_COVER_GROWTH = 4

/**
 * The JPEG quality to use: [requested] when the file it makes fits [maxBytes] (or there is no limit), otherwise the
 * highest quality below it that does, found by bisection, because JPEG size grows with quality. [encodedSize] encodes at a
 * quality and returns the size in bytes; it is called at most once per quality and about eight times in all.
 * [fits] is false when even the lowest quality is over the limit (the picture is then saved at that quality).
 */
data class JpegChoice(val quality: Int, val bytes: Long, val fits: Boolean)

fun chooseJpegQuality(requested: Int, maxBytes: Long?, encodedSize: (Int) -> Long): JpegChoice {
    val start = requested.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
    val sizes = HashMap<Int, Long>()
    fun size(quality: Int): Long = sizes.getOrPut(quality) { encodedSize(quality) }
    val first = size(start)
    if (maxBytes == null || first <= maxBytes) return JpegChoice(start, first, true)
    var low = MIN_JPEG_QUALITY
    var high = start - 1
    var best = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (size(mid) <= maxBytes) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (best < 0) return JpegChoice(MIN_JPEG_QUALITY, size(MIN_JPEG_QUALITY), false)
    return JpegChoice(best, size(best), true)
}

/** `<project> frame <timecode>.png`: the project name without characters file systems reject, the timecode with dashes. */
fun frameFileName(projectName: String, timecode: String, format: FrameFormat): String {
    val cleaned = projectName.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
    val stamp = timecode.replace(Regex("[^0-9A-Za-z]+"), "-")
    return "${cleaned.ifEmpty { "ultimateVE" }.take(80)} frame $stamp.${format.extension}"
}

/** What the picture of project frame [frame] will show. */
enum class FrameContent {
    /** At least one video, picture or title clip covers the frame. */
    PICTURE,

    /** Nothing visual covers the frame, which is inside the project: the saved image is black (the exporter's gap). */
    GAP,

    /** The frame is at or after the end of the project: black as well. */
    PAST_END,

    /** The project has no visual clip at all. */
    EMPTY,
}

fun frameContent(timeline: Timeline, frame: Long): FrameContent {
    val visual = timeline.renderClips().filter { it.kind != RenderKind.AUDIO }
    if (visual.isEmpty()) return FrameContent.EMPTY
    if (visual.any { it.covers(frame) }) return FrameContent.PICTURE
    val end = timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEnd.value } ?: 0L
    return if (frame >= end) FrameContent.PAST_END else FrameContent.GAP
}

/** Whether "Selected clip only" can be used at the playhead, and if not why. */
enum class SelectedClipStatus(val reason: String?) {
    USABLE(null),
    NO_SELECTION("Select a clip first."),
    OUTSIDE_CLIP("The playhead is not on the selected clip."),
    NOT_VISUAL("The selected clip has no picture."),
}

fun selectedClipStatus(timeline: Timeline, selectedClipId: String?, frame: Long): SelectedClipStatus {
    val id = selectedClipId ?: return SelectedClipStatus.NO_SELECTION
    val track = timeline.tracks.firstOrNull { t -> t.clips.any { it.id == id } } ?: return SelectedClipStatus.NO_SELECTION
    val clip = track.clip(id) ?: return SelectedClipStatus.NO_SELECTION
    if (track.type == TrackType.AUDIO) return SelectedClipStatus.NOT_VISUAL
    return if (frame >= clip.timelineStart.value && frame < clip.timelineEnd.value) SelectedClipStatus.USABLE else SelectedClipStatus.OUTSIDE_CLIP
}

/**
 * A timeline that holds only clip [clipId] (alone on its track, no transitions, markers or other layers): what the
 * "Selected clip only" picture is drawn from. Null when there is no such clip or it is on an audio track.
 */
fun timelineOfClip(timeline: Timeline, clipId: String): Timeline? {
    val track = timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return null
    if (track.type == TrackType.AUDIO) return null
    val clip = track.clip(clipId) ?: return null
    return Timeline(tracks = listOf(track.copy(clips = listOf(clip))))
}
