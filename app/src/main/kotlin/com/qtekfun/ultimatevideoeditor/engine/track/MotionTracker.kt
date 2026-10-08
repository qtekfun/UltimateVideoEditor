package com.qtekfun.ultimatevideoeditor.engine.track

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.MotionTrack
import com.qtekfun.ultimatevideoeditor.domain.TrackPath

/** Where a motion track's analysis stands, as the inspector shows it. */
sealed interface TrackStatus {
    /** Nothing has been analysed for this target yet (or the cache is gone). */
    data object NotAnalysed : TrackStatus

    /** The analysis covers the clip. [lost] is how many frames the target was lost in (they hold the last position). */
    data class Ready(val lost: Int, val frames: Int) : TrackStatus

    /** There is an analysis but the clip now reaches outside the part that was analysed (it was extended). */
    data object Stale : TrackStatus
}

/** How an analysis ended. [Failed.message] is fit for the user. */
sealed interface TrackOutcome {
    data object Done : TrackOutcome

    data object Cancelled : TrackOutcome

    data class Failed(val message: String) : TrackOutcome
}

/**
 * Motion tracking as the editor sees it: a background analysis per target (a point or box the user picked at one
 * frame) that follows it forward and backward through the clip, cached on the device. Classical computer vision over
 * the video's own frames (SPECS.md 9.15); nothing leaves the device.
 */
interface MotionTracker {
    /** Width / height of the upright picture of [asset] (rotation applied), or null when it cannot be read. */
    suspend fun frameAspect(asset: MediaAssetDto): Double?

    /** Whether [track]'s analysis covers [clip]. Reads only the small cache header. */
    fun statusOf(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate): TrackStatus

    /** Tracks [track]'s seed through [clip]'s part of [asset] and reports progress in 0..1; returns when it is over. */
    suspend fun analyse(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate, onProgress: (Float) -> Unit): TrackOutcome

    fun cancel()

    /** The analysed path, in project frames, or null when there is none or the file is unreadable. */
    fun load(asset: MediaAssetDto, track: MotionTrack, fps: FrameRate): TrackPath?

    /** Deletes the analysis of [track] (it was removed). */
    fun forget(asset: MediaAssetDto, track: MotionTrack)
}

/** A tracker that does nothing; used by tests and where there is no engine. */
object NoMotionTracker : MotionTracker {
    override suspend fun frameAspect(asset: MediaAssetDto): Double? = null

    override fun statusOf(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate): TrackStatus = TrackStatus.NotAnalysed

    override suspend fun analyse(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate, onProgress: (Float) -> Unit): TrackOutcome =
        TrackOutcome.Failed("Motion tracking is not available here")

    override fun cancel() = Unit

    override fun load(asset: MediaAssetDto, track: MotionTrack, fps: FrameRate): TrackPath? = null

    override fun forget(asset: MediaAssetDto, track: MotionTrack) = Unit
}
