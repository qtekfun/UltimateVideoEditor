package com.ultimatevideo.uveditor.ui.editor.captions

import com.ultimatevideo.uveditor.ui.editor.EditorState

/**
 * The selected clip as a captions target, or null when it cannot be transcribed: nothing selected,
 * a title, or media without an audio track. Uses the committed timeline, not a drag in progress.
 */
fun EditorState.captionTarget(): CaptionTarget? {
    val id = selectedClipId ?: return null
    val clip = timeline.trackOfClip(id)?.clip(id) ?: return null
    val assetId = clip.assetId ?: return null
    val asset = assets.firstOrNull { it.id == assetId }?.takeIf { it.hasAudio } ?: return null
    return CaptionTarget(clip = clip, assetUri = asset.uri, fps = fps, canvasHeight = canvasHeight)
}
