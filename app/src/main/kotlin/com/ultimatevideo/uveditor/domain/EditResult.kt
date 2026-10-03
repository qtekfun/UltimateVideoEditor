package com.ultimatevideo.uveditor.domain

/** Outcome of an edit: expected failures are values, never exceptions. */
sealed interface EditResult<out T> {
    data class Success<out T>(val value: T) : EditResult<T>
    data class Failure(val error: EditError) : EditResult<Nothing>
}

sealed interface EditError {
    data class TrackNotFound(val trackId: String) : EditError
    data class ClipNotFound(val clipId: String) : EditError
    data class DuplicateClipId(val clipId: String) : EditError
    data class DuplicateTrackId(val trackId: String) : EditError

    /** Only an empty track can be removed; removing one with clips would silently delete them. */
    data class TrackNotEmpty(val trackId: String) : EditError
    data class InvalidClip(val reason: String) : EditError
    data class TrackTypeMismatch(val clipId: String, val trackId: String) : EditError
    data class Overlap(val blockingClipId: String) : EditError
    data object NegativeStart : EditError

    /** Split point is not strictly inside any clip (on an edge or in a gap). */
    data object SplitOutsideClip : EditError
    data class InvalidTrim(val reason: String) : EditError
    data object SourceOutOfRange : EditError

    /** A transform or gain value that cannot be rendered or mixed. */
    data class InvalidAppearance(val reason: String) : EditError

    /** A keyframe that cannot exist on its clip (outside it, an invalid pose, or on an audio clip). */
    data class InvalidKeyframe(val reason: String) : EditError
    data class KeyframeNotFound(val frame: Long) : EditError

    /** An effect, blend mode or mask that cannot be applied (invalid value, audio clip, unknown id). */
    data class InvalidEffect(val reason: String) : EditError

    data class EffectNotFound(val effectId: String) : EditError

    data class NotATitle(val clipId: String) : EditError
    data class TransitionNotFound(val transitionId: String) : EditError
    data class DuplicateTransitionId(val transitionId: String) : EditError

    /** A speed, reverse or ramp the clip cannot take (out of range, a title, a one-frame clip). */
    data class InvalidSpeed(val reason: String) : EditError

    /** A transition that cannot hold between its clips (not adjacent, too long, no handle). */
    data class InvalidTransition(val reason: String) : EditError

    /** The timeline has no video track to act as the magnetic base. */
    data object NoBaseTrack : EditError

    /** The base track is the guide: it stays the lowest video lane and cannot be reordered. */
    data class BaseTrackCannotMove(val trackId: String) : EditError

    /** A lane cannot move that way (already first/last of its kind, or nothing to swap with). */
    data class TrackCannotMove(val trackId: String, val reason: String) : EditError

    /** A base-track clip cannot be dragged onto another track: that would leave a gap in the base. */
    data class BaseClipCannotLeave(val clipId: String) : EditError
}

internal fun failure(error: EditError): EditResult.Failure = EditResult.Failure(error)
