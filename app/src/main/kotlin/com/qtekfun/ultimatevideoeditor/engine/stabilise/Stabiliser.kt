package com.qtekfun.ultimatevideoeditor.engine.stabilise

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameRate

/** Where a clip's stabilisation stands, as the inspector shows it. */
sealed interface StabStatus {
    /** The stabiliser is not on for this clip, or the clip cannot be analysed. */
    data object Off : StabStatus

    /** The file has not been analysed yet. */
    data object NotAnalysed : StabStatus

    /** The analysis covers the clip. */
    data object Ready : StabStatus

    /** There is an analysis but the clip now reaches outside the part that was analysed (it was extended). */
    data object Stale : StabStatus
}

/** How an analysis ended. [Failed.message] is fit for the user. */
sealed interface StabOutcome {
    data object Done : StabOutcome

    data object Cancelled : StabOutcome

    data class Failed(val message: String) : StabOutcome
}

/**
 * The stabiliser as the editor sees it: a cached analysis per media file and the correction tables that the
 * preview and the exporter read through the native registry. Everything runs on the device; the analysis is
 * classical computer vision over the video's own frames (SPECS.md 9.6).
 */
interface Stabiliser {
    /** Whether [clip]'s part of [asset] has been analysed. Reads only the small header of the cache file. */
    fun statusOf(asset: MediaAssetDto, clip: Clip, fps: FrameRate): StabStatus

    /**
     * Analyses the clip's part of [asset] (plus a margin, merged with what was analysed before) and reports progress in
     * 0..1. Runs in the background and returns when it is over; [cancel] stops it early.
     */
    suspend fun analyse(asset: MediaAssetDto, clip: Clip, fps: FrameRate, onProgress: (Float) -> Unit): StabOutcome

    fun cancel()

    /**
     * Makes the correction table for [clip]'s stabiliser settings available to the preview and the exporter. A no-op
     * (returning false) when the clip is not stabilised or its analysis is missing or does not cover it, in which case
     * the clip is drawn unstabilised.
     */
    fun register(asset: MediaAssetDto, clip: Clip, fps: FrameRate): Boolean

    /** Forgets every registered table (the project was closed). */
    fun releaseAll()
}

/** A stabiliser that does nothing: the clip is drawn as it is. Used by tests and where there is no engine. */
object NoStabiliser : Stabiliser {
    override fun statusOf(asset: MediaAssetDto, clip: Clip, fps: FrameRate): StabStatus = StabStatus.Off

    override suspend fun analyse(asset: MediaAssetDto, clip: Clip, fps: FrameRate, onProgress: (Float) -> Unit): StabOutcome =
        StabOutcome.Failed("Stabilisation is not available here")

    override fun cancel() = Unit

    override fun register(asset: MediaAssetDto, clip: Clip, fps: FrameRate): Boolean = false

    override fun releaseAll() = Unit
}
