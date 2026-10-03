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
}

internal fun failure(error: EditError): EditResult.Failure = EditResult.Failure(error)
