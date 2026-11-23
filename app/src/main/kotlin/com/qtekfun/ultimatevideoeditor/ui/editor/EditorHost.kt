package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What an editor feature handler may use of the ViewModel that owns it: the state, the undo history, one-shot
 * messages, and the single entry point for timeline edits ([execute]). Handlers delegate to it
 * (`class XHandler(host: EditorHost) : EditorHost by host`) and are given any other collaborator they need
 * explicitly, so each feature can be read, tested and changed without the rest of the ViewModel.
 */
internal interface EditorHost {
    val state: StateFlow<EditorState>
    val scope: CoroutineScope

    /** The committed timeline and its undo stack; replaced by [execute], undo and redo, so read it afresh each time. */
    val history: EditHistory

    /** Stable native keys of clips and of library assets. */
    val clipKeys: KeyRegistry
    val assetKeys: KeyRegistry

    /** Makes the id of a new clip, track or asset. */
    val idGenerator: () -> String

    /** Applies a pure reducer to the state. */
    fun reduce(reducer: EditorState.() -> EditorState)

    /** Sends a one-shot effect (message, navigation) to the screen. */
    fun emit(effect: EditorEffect)

    /** Runs [command] through the undo history; reports the reason and returns false on failure. */
    fun execute(command: EditCommand): Boolean

    /** Marks the project changed so the debounced autosave writes it. */
    fun scheduleSave()
}

/** Default length of a new photo clip, in project frames. */
internal fun EditorHost.stillLengthFrames(): Long = state.value.fps.microsToFrames(PHOTO_DEFAULT_MICROS).coerceAtLeast(1)

/** Length of an asset in project frames, or null if it is not in the library (or has no length of its own). */
internal fun EditorHost.assetLengthFrames(assetId: String?): Long? {
    val asset = state.value.assets.firstOrNull { it.id == assetId } ?: return null
    // A picture has no length to run out of.
    if (asset.isImage) return null
    val micros = FrameRate(asset.nativeFpsNum, asset.nativeFpsDen).framesToMicros(asset.durationFrames)
    return state.value.fps.microsToFrames(micros).takeIf { it > 0 }
}

internal inline fun EditorHost.withSelection(block: (String) -> Unit) {
    val selected = state.value.selectedClipId
    if (selected == null) {
        emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_first)))
        return
    }
    block(selected)
}

/** The clip with this id and the library file it plays; null when either is gone. */
internal fun EditorHost.clipWithAsset(clipId: String?): Pair<Clip, MediaAssetDto>? {
    val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) } ?: return null
    val asset = state.value.assets.firstOrNull { it.id == clip.assetId } ?: return null
    return clip to asset
}
