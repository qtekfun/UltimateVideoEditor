package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.domain.isRetimed
import com.qtekfun.ultimatevideoeditor.domain.sourceSpan
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotClip
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotClipKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotKeyframe
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotLabel
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotMarker
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotRetime
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotTrackType
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotTransition
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineSnapshot
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/** Encodes what the native timeline canvas draws: lanes, clips, transitions, keys, markers and labels, keyed by [clipKeys] and [assetKeys]. */
internal fun buildTimelineSnapshot(
state: EditorState,
clipKeys: KeyRegistry,
assetKeys: KeyRegistry,
words: (UiText) -> String,
): TimelineSnapshot {
    val timeline = state.visibleTimeline
    val selection = state.selection
    val tracks = timeline.tracks.map {
        when (it.type) {
            TrackType.VIDEO -> SnapshotTrackType.VIDEO
            TrackType.AUDIO -> SnapshotTrackType.AUDIO
            TrackType.TITLE -> SnapshotTrackType.TITLE
        }
    }
    // Mute and solo marks for the lane headers of audio lanes.
    val trackFlags = timeline.tracks.map {
        if (it.type == TrackType.AUDIO) {
            (if (it.audio.mute) TimelineSnapshot.TRACK_MUTED else 0) or (if (it.audio.solo) TimelineSnapshot.TRACK_SOLO else 0)
        } else {
            0
        }
    }
    val assetNames = state.assets.associate { it.id to it.displayName }
    val clips = timeline.tracks.flatMapIndexed { trackIndex, track ->
        track.clips.map { clip ->
            SnapshotClip(
                clipKey = clipKeys.keyFor(clip.id),
                trackIndex = trackIndex,
                // Titles and stickers have no media to read waveforms or thumbnails from; a photo has a thumbnail.
                assetKey = clip.assetId?.takeIf { clip.hasMedia || clip.still == StillKind.PHOTO }?.let(assetKeys::keyFor) ?: NO_ASSET_KEY,
                startFrame = clip.timelineStart.value,
                durationFrames = clip.durationFrames,
                sourceInFrame = clip.sourceIn.value,
                sourceFpsNum = state.fps.num,
                sourceFpsDen = state.fps.den,
                selected = clip.id in selection,
                primary = clip.id == state.selectedClipId,
                hasFx = !clip.fx.isNeutral,
                missing = clip.hasMedia && clip.assetId in state.missingMedia,
                audioDetached = clip.audioDetached,
                linked = clip.linkId != null,
                kind = when {
                    clip.still == StillKind.STICKER -> SnapshotClipKind.STICKER
                    clip.still == StillKind.PHOTO -> SnapshotClipKind.IMAGE
                    timeline.multicams.isNotEmpty() && clip.id.startsWith("mc-") -> SnapshotClipKind.MULTICAM
                    else -> SnapshotClipKind.DEFAULT
                },
            )
        }
    }
    val transitions = timeline.transitions.mapNotNull { transition ->
        val trackIndex = timeline.tracks.indexOfFirst { it.clip(transition.toClipId) != null }
        val cut = timeline.cutOf(transition)
        if (trackIndex < 0 || cut == null) {
            null
        } else {
            SnapshotTransition(trackIndex, cut.value, transition.preFrames, transition.postFrames)
        }
    }
    val keyframes = timeline.tracks.flatMap { track ->
        // One diamond per frame that carries a key of any kind: the pose and every animated parameter.
        track.clips.flatMap { clip ->
            val frames = (clip.keyframes.map { it.frame } + clip.params.flatMap { t -> t.keys.map { it.frame } }).distinct().sorted()
            frames.map { SnapshotKeyframe(clipKeys.keyFor(clip.id), it) }
        }
    }
    val retimes = timeline.tracks.flatMap { track ->
        track.clips.filter { it.isRetimed || it.isFreeze }.map {
            SnapshotRetime(clipKeys.keyFor(it.id), it.sourceSpan, reverse = it.reverse, freeze = it.isFreeze)
        }
    }
    val markers = timeline.markers.map {
        SnapshotMarker(
            it.frame.value,
            beat = it.kind == MarkerKind.BEAT,
            colorCode = it.color?.let { color -> color.ordinal + 1 } ?: 0,
            hasNote = !it.note.isNullOrBlank(),
        )
    }
    val labels = timeline.tracks.flatMap { track ->
        track.clips.mapNotNull { clip -> ClipLabels.of(clip, clip.assetId?.let(assetNames::get), words)?.let { SnapshotLabel(clipKeys.keyFor(clip.id), it) } }
    }
    val markerLabels = timeline.markers.mapIndexedNotNull { index, marker ->
        ClipLabels.clean(marker.name.orEmpty()).takeIf { it.isNotEmpty() }?.let { SnapshotLabel(SnapshotLabel.markerKey(index), it) }
    }
    val shaping = timeline.tracks.flatMap { track ->
        if (track.type == TrackType.TITLE) return@flatMap emptyList()
        track.clips.mapNotNull { clip ->
            val editable = AudioShapeGesture.isEditable(clip, track.type, isPrimary = clip.id == state.selectedClipId)
            AudioShapeGesture.snapshotOf(clip, clipKeys.keyFor(clip.id), editable)
        }
    }
    return TimelineSnapshot(state.fps.num, state.fps.den, tracks, clips, transitions, keyframes, retimes, markers, labels + markerLabels, shaping, trackFlags)
}
