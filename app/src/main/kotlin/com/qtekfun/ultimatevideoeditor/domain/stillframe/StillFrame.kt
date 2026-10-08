package com.qtekfun.ultimatevideoeditor.domain.stillframe

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.RenderKind
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.renderClips
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * "Save frame as image" (SPECS 5.35): the pure part. One tap saves a JPEG of the whole picture at the project size, so the
 * only decisions left are the size cap, the file name, what the frame contains and whether the result is usable. Positions
 * are integer frames, as everywhere else; no Android types.
 */

const val JPEG_QUALITY = 95

/** Longest side of a saved picture: 4096 px keeps the readback and the bitmap near 64 MB. */
const val MAX_FRAME_SIDE = 4096

data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
    }
}

/** The size of the saved picture; [reduced] when the project is larger than [MAX_FRAME_SIDE] and was scaled down. */
data class FrameTarget(val size: FrameSize, val reduced: Boolean)

/**
 * The project's own size, odd sizes included (an image has no even-size rule). A project with a side over [MAX_FRAME_SIDE] is
 * scaled down keeping its shape.
 */
fun frameTarget(projectWidth: Int, projectHeight: Int): FrameTarget {
    require(projectWidth > 0 && projectHeight > 0) { "invalid project size ${projectWidth}x$projectHeight" }
    val long = maxOf(projectWidth, projectHeight)
    if (long <= MAX_FRAME_SIDE) return FrameTarget(FrameSize(projectWidth, projectHeight), false)
    val w = (projectWidth.toLong() * MAX_FRAME_SIDE / long).coerceAtLeast(1).toInt()
    val h = (projectHeight.toLong() * MAX_FRAME_SIDE / long).coerceAtLeast(1).toInt()
    return FrameTarget(FrameSize(w, h), true)
}

/** `00h01m23s12f`: the frame as hours, minutes, seconds and frames (non-drop-frame, nominal integer rate), safe in a file name. */
fun frameTimecode(frame: Long, fps: FrameRate): String {
    val nominal = ((fps.num + fps.den / 2) / fps.den).coerceAtLeast(1)
    val frames = frame % nominal
    val totalSeconds = frame / nominal
    return "%02dh%02dm%02ds%02df".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60, frames)
}

private val StampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * `<project>_<timecode>_<yyyyMMdd-HHmmss>.jpg`. The project name loses what MediaStore and file systems reject (path
 * separators, `:*?"<>|`, control characters, leading dots) and its spaces become underscores; it is cut at 60 characters.
 */
fun frameFileName(projectName: String, frame: Long, fps: FrameRate, now: LocalDateTime): String {
    val cleaned = projectName.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), "_")
        .replace(Regex("_+"), "_")
        .trim('.', '_', ' ')
        .take(MAX_NAME)
        .trimEnd('_', '.')
    return "${cleaned.ifEmpty { "ultimateVE" }}_${frameTimecode(frame, fps)}_${now.format(StampFormat)}.jpg"
}

private const val MAX_NAME = 60

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

/**
 * True when every pixel of the RGBA picture ([width] x [height], in [rgba] from its start) equals the first one: a frame
 * that nothing was drawn into, or that was drawn black, is uniform; real footage never is. Used as a guard: a picture of
 * a frame that has visible layers must not be uniform, or nothing is saved.
 */
fun isUniformPicture(rgba: ByteBuffer, width: Int, height: Int): Boolean {
    val pixels = width.toLong() * height
    val bytes = pixels * PIXEL_BYTES
    require(rgba.capacity() >= bytes) { "buffer too small for ${width}x$height" }
    if (pixels <= 1) return true
    val view = rgba.duplicate()
    view.clear()
    val first = view.getInt(0)
    var i = PIXEL_BYTES
    while (i < bytes) {
        if (view.getInt(i.toInt()) != first) return false
        i += PIXEL_BYTES
    }
    return true
}

private const val PIXEL_BYTES = 4L

/** What the user is told about the frame before or after it is saved, or null when there is nothing to add. */
fun frameNotice(content: FrameContent): String? = when (content) {
    FrameContent.PICTURE -> null
    FrameContent.GAP -> "No picture at this frame (a gap): the image is black."
    FrameContent.PAST_END -> "The playhead is after the end of the project: the image is black."
    FrameContent.EMPTY -> "The project has no picture yet: the image is black."
}
