package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.engine.audio.AudioSnapshot
import com.ultimatevideo.uveditor.engine.export.VideoClipSpec
import com.ultimatevideo.uveditor.ui.editor.KeyRegistry
import com.ultimatevideo.uveditor.ui.editor.audioSnapshotOf
import java.math.BigInteger

/** What to render, derived from the committed timeline. Pure data: no Android types. */
internal data class ExportPlan(
    /** Length of the movie in project frames. */
    val projectFrames: Long,
    val videoClips: List<VideoClipSpec>,
    /** Null when nothing audible is on the timeline: the file then has no audio track. */
    val audio: AudioSnapshot?,
    /** Assets the render needs, by native key. */
    val assetKeys: Map<String, Long>,
)

private const val HLG_TO_SDR = 1
private const val SDR = 0

/**
 * Plans an export of [timeline]. Returns null when it is empty. The first video track is the topmost
 * layer, matching the preview; clip transforms and opacity are carried over. Source ranges are in project frames, as everywhere in the editor.
 */
internal fun buildExportPlan(timeline: Timeline, assets: List<MediaAssetDto>, fps: FrameRate): ExportPlan? {
    val assetsById = assets.associateBy { it.id }
    val assetKeys = KeyRegistry()
    val clipKeys = KeyRegistry()
    val used = LinkedHashMap<String, Long>()

    val videoClips = ArrayList<VideoClipSpec>()
    timeline.tracks.filter { it.type == TrackType.VIDEO }.forEachIndexed { layer, track ->
        for (clip in track.clips) {
            val asset = clip.assetId?.let(assetsById::get) ?: continue
            if (!asset.hasVideo) continue
            val key = used.getOrPut(asset.id) { assetKeys.keyFor(asset.id) }
            videoClips += VideoClipSpec(
                startFrame = clip.timelineStart.value,
                durationFrames = clip.durationFrames,
                sourceInFrame = clip.sourceIn.value,
                assetKey = key,
                layer = layer,
                // The export is SDR Rec.709: HLG sources are tone-mapped down to it.
                colorMode = if (asset.colorSpace.contains("HLG", ignoreCase = true)) HLG_TO_SDR else SDR,
                positionX = clip.transform.positionX,
                positionY = clip.transform.positionY,
                scaleX = clip.transform.scaleX,
                scaleY = clip.transform.scaleY,
                rotationDegrees = clip.transform.rotationDegrees,
                opacity = clip.transform.opacity,
            )
        }
    }

    val snapshot = audioSnapshotOf(timeline, assets, fps, clipKeys::keyFor) { id -> used.getOrPut(id) { assetKeys.keyFor(id) } }
    val audio = snapshot.takeIf { it.clips.isNotEmpty() }

    val end = timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEnd.value } ?: 0L
    if (end <= 0L || (videoClips.isEmpty() && audio == null)) return null
    return ExportPlan(end, videoClips, audio, used)
}

/** Output frames needed to cover [projectFrames] project frames, rounded up so no audio is cut off. */
internal fun outputFrameCount(projectFrames: Long, project: FrameRate, output: FrameRate): Long {
    val numerator = BigInteger.valueOf(projectFrames) * BigInteger.valueOf(output.num.toLong()) * BigInteger.valueOf(project.den.toLong())
    val denominator = BigInteger.valueOf(output.den.toLong()) * BigInteger.valueOf(project.num.toLong())
    return (numerator + denominator - BigInteger.ONE).divide(denominator).toLong()
}
