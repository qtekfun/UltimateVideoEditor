package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.EditError
import com.qtekfun.ultimatevideoeditor.domain.GroupEditUnavailable
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/** The message a user reads when an edit is refused. */
internal fun describe(error: EditError): UiText = when (error) {
    is EditError.Overlap -> UiText.res(R.string.ee_overlap)
    EditError.SplitOutsideClip -> UiText.res(R.string.ee_split_outside)
    EditError.NegativeStart -> UiText.res(R.string.ee_negative_start)
    EditError.SourceOutOfRange -> UiText.res(R.string.ee_source_range)
    is EditError.InvalidTrim -> UiText.res(R.string.ee_invalid_trim, error.reason)
    is EditError.InvalidAppearance -> UiText.res(R.string.ee_invalid_appearance, error.reason)
    is EditError.InvalidAudio -> UiText.res(R.string.ee_invalid_audio, error.reason)
    is EditError.TrackNotFound, is EditError.ClipNotFound -> UiText.res(R.string.ee_not_found)
    is EditError.TrackNotEmpty -> UiText.res(R.string.ee_track_not_empty)
    is EditError.InvalidTransition -> UiText.res(R.string.ee_invalid_transition, error.reason)
    is EditError.TransitionNotFound -> UiText.res(R.string.ee_transition_gone)
    EditError.NoBaseTrack -> UiText.res(R.string.ee_no_base)
    is EditError.BaseClipCannotLeave -> UiText.res(R.string.ee_base_cannot_leave)
    is EditError.BaseTrackCannotMove -> UiText.res(R.string.ee_base_stays)
    is EditError.TrackCannotMove -> UiText.res(R.string.ee_lane_cannot_move, error.reason)
    is EditError.NotATitle -> UiText.res(R.string.ee_not_title)
    is EditError.InvalidKeyframe -> UiText.res(R.string.ee_invalid_keyframe, error.reason)
    is EditError.KeyframeNotFound -> UiText.res(R.string.ee_no_keyframe)
    is EditError.InvalidSpeed -> UiText.res(R.string.ee_invalid_speed, error.reason)
    is EditError.InvalidEffect -> UiText.res(R.string.ee_invalid_effect, error.reason)
    is EditError.EffectNotFound -> UiText.res(R.string.ee_effect_gone)
    is EditError.InvalidMarker -> UiText.res(R.string.ee_invalid_marker, error.reason)
    is EditError.MarkerNotFound -> UiText.res(R.string.ee_marker_gone)
    is EditError.InvalidTemplate -> UiText.res(R.string.ee_invalid_template, error.reason)
    is EditError.CutToBeatUnavailable -> UiText.res(R.string.ee_cut_to_beat, error.reason)
    is GroupEditUnavailable -> error.reason
    is EditError.DuplicateClipId, is EditError.DuplicateTrackId, is EditError.DuplicateTransitionId,
    is EditError.InvalidClip, is EditError.TrackTypeMismatch -> UiText.res(R.string.ee_not_valid)
}

internal fun uniqueTrackId(tracks: List<Track>, prefix: String): String =
    generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { id -> tracks.none { it.id == id } }

/** A project always has a video lane and an audio lane to put media on, even when it was saved without one. */
internal fun withDefaultTracks(timeline: Timeline): Timeline {
    var tracks = timeline.tracks
    if (tracks.none { it.type == TrackType.VIDEO }) tracks = listOf(Track(uniqueTrackId(tracks, "track-v"), TrackType.VIDEO)) + tracks
    if (tracks.none { it.type == TrackType.AUDIO }) tracks = tracks + Track(uniqueTrackId(tracks, "track-a"), TrackType.AUDIO)
    return timeline.copy(tracks = tracks)
}
