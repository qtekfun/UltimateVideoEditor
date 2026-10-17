package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.domain.sourceSpan
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** One EDL document: the cuts of a single track. */
data class EdlFile(val name: String, val text: String)

/** The EDLs of a project and what could not be put in them. */
data class EdlExport(val files: List<EdlFile>, val notes: List<UiText>)

/**
 * CMX3600 export of the cuts of every video and audio track. CMX3600 has one video channel, so each track
 * gets its own file (`<project>-V1.edl` for the base, `-V2` for the overlay above it, `-A1`, ...), which
 * is how other editors expect multi-track EDLs. Titles, stickers, transitions, effects, transforms and
 * keyframes are not part of the format; transitions are written as cuts and everything left out is listed
 * in [EdlExport.notes].
 */
object Edl {

    fun export(project: ProjectDto): EdlExport =
        export(project.name, project.settings.fpsNum, project.settings.fpsDen, TimelineMapper.toTimeline(project), project.mediaLibrary)

    fun export(name: String, fpsNum: Int, fpsDen: Int, timeline: Timeline, assets: List<MediaAssetDto>): EdlExport {
        val dropFrame = SmpteTimecode.isNtsc(fpsNum, fpsDen)
        val tc = SmpteTimecode(fpsNum, fpsDen, dropFrame)
        val byId = assets.associateBy { it.id }
        val files = mutableListOf<EdlFile>()
        val notes = linkedSetOf<UiText>()
        if (!dropFrame && fpsDen != 1) {
            notes += UiText.res(R.string.edl_note_rate, rateLabel(fpsNum, fpsDen), tc.nominal)
        }
        var skipped = 0
        timeline.tracks.forEachIndexed { index, track ->
            if (track.type == TrackType.TITLE) {
                skipped += track.clips.size
                return@forEachIndexed
            }
            val usable = track.clips.filter { it.isFileClip() }
            skipped += track.clips.size - usable.size
            if (usable.isEmpty()) return@forEachIndexed
            val label = MissingMedia.trackLabel(timeline, index)
            val sb = StringBuilder()
            sb.append("TITLE: ").append(oneLine(name)).append(" - ").append(label).append('\n')
            sb.append("FCM: ").append(if (tc.dropFrame) "DROP FRAME" else "NON-DROP FRAME").append("\n\n")
            usable.forEachIndexed { i, clip ->
                appendEvent(sb, i + 1, clip, clip.assetId?.let(byId::get), track.type, tc, fpsNum, fpsDen, notes)
            }
            files += EdlFile("${fileSafe(name)}-$label.edl", sb.toString())
        }
        if (skipped > 0) notes += UiText.plural(R.plurals.edl_note_skipped, skipped)
        if (timeline.transitions.isNotEmpty()) notes += UiText.plural(R.plurals.edl_note_crossfades, timeline.transitions.size)
        val all = timeline.tracks.flatMap { it.clips }
        if (all.any { it.fx.effects.isNotEmpty() || !it.transform.isIdentity || it.keyframes.isNotEmpty() }) {
            notes += UiText.res(R.string.edl_note_effects)
        }
        return EdlExport(files, notes.toList())
    }

    /** The files as one zip, for when there is more than one track. Entries carry no timestamps, so the bytes are reproducible. */
    fun zip(files: List<EdlFile>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name).apply { time = 0L })
                zip.write(file.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun Clip.isFileClip(): Boolean = hasMedia && assetId != null

    private fun appendEvent(
        sb: StringBuilder,
        event: Int,
        clip: Clip,
        asset: MediaAssetDto?,
        trackType: TrackType,
        tc: SmpteTimecode,
        fpsNum: Int,
        fpsDen: Int,
        notes: MutableSet<UiText>,
    ) {
        val channel = when {
            trackType == TrackType.AUDIO -> "AA"
            asset != null && asset.hasAudio && asset.hasVideo && !clip.audioDetached -> "B"
            else -> "V"
        }
        val span = clip.sourceSpan
        val retimed = clip.retimedFrames != null || clip.reverse || clip.speedRamp.isNotEmpty() || clip.isFreeze
        val srcIn = clip.sourceIn.value
        val srcOut = srcIn + span
        sb.append("%03d  AX       %-5s C        %s %s %s %s\n".format(event, channel, tc.format(srcIn), tc.format(srcOut), tc.format(clip.timelineStart.value), tc.format(clip.timelineEnd.value)))
        if (retimed) {
            val speed = span.toDouble() / clip.durationFrames.toDouble()
            val rate = fpsNum.toDouble() / fpsDen * speed * (if (clip.reverse) -1 else 1)
            sb.append("M2   AX       %s                %s\n".format(formatRate(if (clip.isFreeze) 0.0 else rate), tc.format(srcIn)))
            if (clip.speedRamp.isNotEmpty()) notes += UiText.res(R.string.edl_note_ramps)
            if (clip.reverse) notes += UiText.res(R.string.edl_note_reverse)
        }
        sb.append("* FROM CLIP NAME: ").append(asset?.let(MissingMedia::nameOf) ?: clip.assetId.orEmpty()).append('\n')
        sb.append("* CLIP ID: ").append(clip.id).append('\n')
        sb.append('\n')
    }

    private fun formatRate(rate: Double) = String.format(Locale.ROOT, "%s%05.1f", if (rate < 0) "-" else "", abs(rate))

    private fun rateLabel(num: Int, den: Int) = String.format(Locale.ROOT, "%.3f", num.toDouble() / den)

    private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ')

    private fun fileSafe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "project" }
}
