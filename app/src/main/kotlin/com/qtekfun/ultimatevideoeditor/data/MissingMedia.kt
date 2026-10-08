package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import kotlin.math.abs

/** A clip whose media file cannot be read, described so a person can find it on the timeline. */
data class MissingClip(val clipId: String, val assetId: String, val where: String)

/** What the editor knows about one unreadable library file. */
data class MissingAsset(val assetId: String, val name: String, val problem: MediaProblem, val clipCount: Int)

/** Pure helpers for reporting and handling media that cannot be read; no Android, no I/O. */
object MissingMedia {

    /** Clips that need one of [missingAssetIds], in timeline order within each track. */
    fun clipsUsing(timeline: Timeline, missingAssetIds: Set<String>, fps: FrameRate): List<MissingClip> {
        if (missingAssetIds.isEmpty()) return emptyList()
        val out = mutableListOf<MissingClip>()
        timeline.tracks.forEachIndexed { index, track ->
            for (clip in track.clips) {
                val assetId = clip.assetId ?: continue
                if (assetId in missingAssetIds && clip.hasMedia) out += MissingClip(clip.id, assetId, whereIs(timeline, index, clip, fps))
            }
        }
        return out
    }

    /** "V2 at 0:10": the track as the editor labels it, and when the clip starts. */
    fun whereIs(timeline: Timeline, trackIndex: Int, clip: Clip, fps: FrameRate): String =
        "${trackLabel(timeline, trackIndex)} at ${clock(fps.framesToMicros(clip.timelineStart.value))}"

    /** Video tracks count up from the base (V1 is the base), audio and title tracks from the top. */
    fun trackLabel(timeline: Timeline, trackIndex: Int): String {
        val track = timeline.tracks[trackIndex]
        return when (track.type) {
            TrackType.VIDEO -> {
                val base = ClipDeletion.baseTrack(timeline)
                val videos = timeline.tracks.filter { it.type == TrackType.VIDEO }
                val fromBase = videos.size - videos.indexOfFirst { it.id == track.id }
                if (base == null) "V?" else "V$fromBase"
            }
            TrackType.AUDIO -> "A${timeline.tracks.take(trackIndex + 1).count { it.type == TrackType.AUDIO }}"
            TrackType.TITLE -> "T${timeline.tracks.take(trackIndex + 1).count { it.type == TrackType.TITLE }}"
        }
    }

    /** The unreadable library files, each with how many clips depend on it, most used first. */
    fun summarize(timeline: Timeline, assets: List<MediaAssetDto>, problems: Map<String, MediaProblem>): List<MissingAsset> {
        if (problems.isEmpty()) return emptyList()
        val uses = HashMap<String, Int>()
        for (track in timeline.tracks) for (clip in track.clips) if (clip.hasMedia) clip.assetId?.let { uses.merge(it, 1, Int::plus) }
        return assets.mapNotNull { asset ->
            problems[asset.id]?.let { MissingAsset(asset.id, nameOf(asset), it, uses[asset.id] ?: 0) }
        }.sortedByDescending { it.clipCount }
    }

    /** Best label for a file: the stored name, else the last piece of its URI, else its id. */
    fun nameOf(asset: MediaAssetDto): String =
        asset.displayName?.takeIf { it.isNotBlank() }
            ?: leafOfUri(asset.uri).takeIf { it.isNotBlank() }
            ?: asset.id

    /**
     * The file name at the end of a content URI. Document URIs percent-encode their id
     * (`.../document/raw%3A%2Fstorage%2F...%2Fclip.mp4`), so the last segment is decoded before the
     * name is taken; a plain URI behaves as before.
     */
    internal fun leafOfUri(uri: String): String {
        val decoded = percentDecode(uri.substringAfterLast('/'))
        return decoded.substringAfterLast('/').substringAfterLast(':')
    }

    internal fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The folder trail a document URI carries, last piece the file name (`primary:Movies/trip/a.mp4` gives
     * Movies, trip, a.mp4). Providers that hide the path in an opaque id (`msf:1234`) give just that id.
     */
    fun pathOfUri(uri: String): List<String> {
        val id = percentDecode(uri.substringAfterLast('/'))
        val afterVolume = if (':' in id) id.substringAfter(':') else id
        return afterVolume.split('/').filter { it.isNotBlank() }
    }

    /** The furthest source position, in microseconds, that any clip of [assetId] reads up to. */
    fun requiredSourceMicros(timeline: Timeline, assetId: String, fps: FrameRate): Long {
        var furthest = 0L
        for (track in timeline.tracks) for (clip in track.clips) {
            if (clip.assetId == assetId && clip.hasMedia) furthest = maxOf(furthest, clip.sourceOut.value)
        }
        return fps.framesToMicros(furthest)
    }

    internal fun clock(micros: Long): String {
        val totalSeconds = micros / 1_000_000
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }
}

/** Outcome of checking a replacement file against the media it is meant to stand in for. */
sealed interface RelinkVerdict {
    /** The replacement cannot work; [reason] says why in words for the user. */
    data class Rejected(val reason: String) : RelinkVerdict

    /** It can be used; [warnings] list differences worth knowing about (possibly none). */
    data class Accepted(val warnings: List<String>) : RelinkVerdict
}

/** Turns a probed replacement file into the library entry that replaces the old one; shared by every relink path. */
object RelinkApply {
    /**
     * The entry for [old] after it is pointed at [uri] (probed as [probed]), or null when the file is too short to use.
     * The replacement is what gets exported, so its facts replace the old file's.
     */
    fun relinked(old: MediaAssetDto, probed: ProbedMedia, uri: String, projectFps: FrameRate): MediaAssetDto? {
        if (probed.isImage) return old.copy(uri = uri, displayName = probed.displayName ?: old.displayName)
        // Audio-only files have no native frame rate; use the project's.
        val (num, den) = if (probed.hasVideo) probed.fpsNum to probed.fpsDen else projectFps.num to projectFps.den
        val durationFrames = FrameRate(num, den).microsToFrames(probed.durationMicros)
        if (durationFrames <= 0) return null
        return old.copy(
            uri = uri,
            durationFrames = durationFrames,
            nativeFpsNum = num,
            nativeFpsDen = den,
            colorSpace = probed.colorSpace,
            hasVideo = probed.hasVideo,
            hasAudio = probed.hasAudio,
            displayName = probed.displayName ?: old.displayName,
            videoWidth = probed.videoWidth,
            videoHeight = probed.videoHeight,
            videoBitrate = probed.videoBitrate,
            videoCodec = probed.videoCodec,
            tenBit = probed.tenBit,
        )
    }
}

object RelinkCheck {
    private const val FPS_TOLERANCE = 0.005

    fun evaluate(
        old: MediaAssetDto,
        replacement: ProbedMedia,
        replacementUri: String,
        otherAssetUris: Collection<String>,
        neededSourceMicros: Long,
    ): RelinkVerdict {
        if (replacementUri in otherAssetUris) {
            return RelinkVerdict.Rejected("That file is already in the project as another media item")
        }
        if (old.isImage != replacement.isImage) {
            return RelinkVerdict.Rejected(if (old.isImage) "Pick a picture to replace a picture" else "Pick a video or audio file, not a picture")
        }
        if (old.hasVideo && !replacement.hasVideo) return RelinkVerdict.Rejected("The new file has no video")
        if (old.hasAudio && !old.hasVideo && !replacement.hasAudio) return RelinkVerdict.Rejected("The new file has no audio")
        val warnings = mutableListOf<String>()
        if (old.hasAudio && old.hasVideo && !replacement.hasAudio) warnings += "The new file has no audio: its clips will be silent"
        if (!old.isImage) {
            if (replacement.durationMicros < neededSourceMicros) {
                warnings += "The new file is shorter (${MissingMedia.clock(replacement.durationMicros)}) than the part used " +
                    "(${MissingMedia.clock(neededSourceMicros)}): the end of some clips will have no picture"
            }
            if (replacement.hasVideo && old.hasVideo && differs(old.nativeFpsNum, old.nativeFpsDen, replacement.fpsNum, replacement.fpsDen)) {
                warnings += "The new file has a different frame rate"
            }
            if (old.colorSpace != replacement.colorSpace) warnings += "The new file uses another colour space (${replacement.colorSpace})"
        }
        return RelinkVerdict.Accepted(warnings)
    }

    private fun differs(aNum: Int, aDen: Int, bNum: Int, bDen: Int): Boolean {
        val a = aNum.toDouble() / aDen
        val b = bNum.toDouble() / bDen
        return abs(a - b) > a * FPS_TOLERANCE
    }
}
