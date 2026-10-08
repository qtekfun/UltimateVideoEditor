package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto

/** The picture a project card shows: one frame of [uri] at [timeMicros] (a photo when [isImage]). */
data class ThumbnailSource(val uri: String, val timeMicros: Long, val isImage: Boolean)

/** What a project card shows beyond its settings. Pure functions over the stored project, no I/O. */
object ProjectOverview {

    /** The end of the last clip, in project frames; 0 for an empty project. */
    fun durationFrames(project: ProjectDto): Long = project.tracks
        .flatMap { it.clips }
        .maxOfOrNull { it.timelineStartFrame + (it.timelineFrames ?: (it.sourceOutFrame - it.sourceInFrame)) }
        ?.coerceAtLeast(0L)
        ?: 0L

    /**
     * The first picture of the project: the earliest clip on a video track that plays a video or photo
     * from the media library (titles and built-in stickers have nothing to read). Null if there is none.
     */
    fun thumbnailSource(project: ProjectDto): ThumbnailSource? {
        val assets = project.mediaLibrary.associateBy { it.id }
        val clip = project.tracks
            .filter { it.type == "video" }
            .flatMap { it.clips }
            .filter { clip -> clip.title == null && assets[clip.assetId]?.let { it.hasVideo || it.isImage } == true }
            .minByOrNull { it.timelineStartFrame }
            ?: return null
        val asset = checkNotNull(assets[clip.assetId])
        val micros = if (asset.isImage || asset.nativeFpsNum <= 0) {
            0L
        } else {
            clip.sourceInFrame.coerceAtLeast(0L) * 1_000_000L * asset.nativeFpsDen / asset.nativeFpsNum
        }
        return ThumbnailSource(asset.uri, micros, asset.isImage)
    }

    /** "0:42", "12:05" or "1:02:03" for a duration of [frames] at fpsNum/fpsDen. */
    fun formatDuration(frames: Long, fpsNum: Int, fpsDen: Int): String {
        if (fpsNum <= 0) return "0:00"
        val totalSeconds = frames.coerceAtLeast(0L) * fpsDen / fpsNum
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
