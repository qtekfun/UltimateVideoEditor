package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipAttributes
import com.qtekfun.ultimatevideoeditor.domain.ClipSelection
import com.qtekfun.ultimatevideoeditor.domain.Clipboard
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.GroupAlign
import com.qtekfun.ultimatevideoeditor.domain.GroupDelete
import com.qtekfun.ultimatevideoeditor.domain.GroupDuplicate
import com.qtekfun.ultimatevideoeditor.domain.GroupPaste
import com.qtekfun.ultimatevideoeditor.domain.GroupPasteAttributes
import com.qtekfun.ultimatevideoeditor.domain.GroupSetGain
import com.qtekfun.ultimatevideoeditor.domain.GroupSetOpacity
import com.qtekfun.ultimatevideoeditor.domain.GroupSetSpeed
import com.qtekfun.ultimatevideoeditor.domain.GroupTransitions
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.MarkerHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * What is selected on the timeline: taps on the canvas, the multi-select mode and the group operations on it (copy, paste, delete, duplicate,
 * align, move to other lanes...). The clipboard lives here.
 */
internal class SelectionHandler(
    host: EditorHost,
    private val playback: PlaybackController,
    private val markers: MarkerHandler,
) : EditorHost by host {

    private var clipboard: Clipboard? = null

    /** The library files the clipboard holds a clip of (an undo-safe cleanup of the library must not remove them). */
    fun clipboardAssetIds(): List<String> = clipboard?.entries?.mapNotNull { it.clip.assetId }.orEmpty()

    fun tap(hit: TimelineHit) {
        if (hit.kind != HitKind.MARKER) markers.closeMarkerPopup()
        when (hit.kind) {
            HitKind.RULER, HitKind.PLAYHEAD -> playback.seekTo(hit.frame)
            // First tap: the playhead goes to the marker (the finger may be a few frames off it). A tap on the marker the playhead
            // is already on opens its editor.
            HitKind.MARKER -> history.timeline.markers.getOrNull(hit.clipKey.toInt())?.let {
                if (state.value.playhead.value == it.frame.value) {
                    markers.openMarker(it.id)
                } else {
                    markers.closeMarkerPopup()
                    playback.seekTo(it.frame.value)
                }
            }
            HitKind.CLIP, HitKind.CLIP_LEFT_EDGE, HitKind.CLIP_RIGHT_EDGE -> {
                val clipId = clipKeys.idFor(hit.clipKey)
                if (state.value.selectMode && clipId != null) {
                    toggleInSelection(clipId)
                } else {
                    // A plain tap goes back to a single selection, even on a clip that was in the group.
                    reduce {
                        copy(
                            selectedClipId = clipId,
                            selectedClipIds = emptySet(),
                            selectedTrackId = clipId?.let { timeline.trackOfClip(it)?.id } ?: selectedTrackId,
                        )
                    }
                }
            }
            // In select mode a tap on empty space keeps the selection (the Clear button drops it).
            HitKind.EMPTY_TRACK -> if (state.value.selectMode) {
                reduce { copy(selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId) }
            } else {
                reduce {
                    copy(
                        selectedClipId = null,
                        selectedClipIds = emptySet(),
                        selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId,
                    )
                }
            }
            // A tap on a lane header selects the lane (the up/down and remove buttons then act on it); a long press drags it.
            HitKind.LANE_HEADER -> reduce { copy(selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId) }
            // Above the lanes (room left by the bottom-anchored stack) a tap is a tap on nothing; OUTSIDE only occurs mid-drag.
            HitKind.FADE_IN_HANDLE, HitKind.FADE_OUT_HANDLE, HitKind.VOLUME_POINT -> Unit
            HitKind.NONE, HitKind.ABOVE_LANES, HitKind.OUTSIDE ->
                if (!state.value.selectMode) reduce { copy(selectedClipId = null, selectedClipIds = emptySet()) }
        }
    }

    /** Adds [clipId] to the selection, or removes it when it is already in; the clip toggled on becomes the primary one. */
    private fun toggleInSelection(clipId: String) {
        val chosen = LinkedHashSet(state.value.selection)
        if (!chosen.remove(clipId)) chosen += clipId
        setSelection(chosen, primary = clipId)
    }

    /** Makes [ids] the selection; [primary] (when among them, else the last one) is the clip the inspector edits. */
    private fun setSelection(ids: Set<String>, primary: String? = null) {
        val picked = primary?.takeIf { it in ids } ?: ids.lastOrNull()
        reduce {
            copy(
                selectedClipIds = if (ids.size > 1) LinkedHashSet(ids) else emptySet(),
                selectedClipId = picked,
                selectedTrackId = picked?.let { history.timeline.trackOfClip(it)?.id } ?: selectedTrackId,
            )
        }
    }

    fun selectionIntent(intent: SelectionIntent) {
        val timeline = history.timeline
        when (intent) {
            SelectionIntent.ToggleSelectMode -> reduce { copy(selectMode = !selectMode) }
            is SelectionIntent.LongPress -> {
                val onClip = intent.hit.kind == HitKind.CLIP || intent.hit.kind == HitKind.CLIP_LEFT_EDGE || intent.hit.kind == HitKind.CLIP_RIGHT_EDGE
                clipKeys.idFor(intent.hit.clipKey)?.takeIf { onClip }?.let(::toggleInSelection)
            }
            is SelectionIntent.Marquee -> {
                val ids = intent.clipKeys.mapNotNull { clipKeys.idFor(it) }.filter { timeline.trackOfClip(it) != null }
                if (ids.isNotEmpty()) setSelection(LinkedHashSet(state.value.selection + ids), primary = ids.last())
            }
            SelectionIntent.SelectLane -> {
                val lane = state.value.selectedTrackId
                val ids = lane?.let { ClipSelection.allInLane(timeline, it) }.orEmpty()
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_tap_a_lane_that_has))) else setSelection(ids, state.value.selectedClipId)
            }
            SelectionIntent.SelectFromPlayhead -> {
                val ids = ClipSelection.fromPlayhead(timeline, state.value.playhead)
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_clips_after_the_playhead))) else setSelection(ids)
            }
            SelectionIntent.SelectAll -> {
                val ids = timeline.tracks.flatMapTo(LinkedHashSet()) { track -> track.clips.map { it.id } }
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_there_are_no_clips_to))) else setSelection(ids, state.value.selectedClipId)
            }
            SelectionIntent.ClearSelection -> reduce { copy(selectedClipId = null, selectedClipIds = emptySet()) }
            SelectionIntent.Copy -> copySelection(announce = true)
            SelectionIntent.Cut -> if (copySelection(announce = false)) deleteSelection()
            SelectionIntent.Paste -> pasteClipboard()
            SelectionIntent.PasteContent -> {
                reduce { copy(pasteChoice = null) }
                pasteContent()
            }
            SelectionIntent.PasteStyle -> pasteStyle()
            SelectionIntent.DismissPasteChoice -> reduce { copy(pasteChoice = null) }
            SelectionIntent.Duplicate -> withGroup { runGroupCommand(GroupDuplicate(it), selectNew = true) }
            SelectionIntent.DeleteSelection -> deleteSelection()
            SelectionIntent.PasteAttributes -> {
                val source = clipboard?.primary
                if (source == null) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_copy_a_clip_first_then)))
                else withGroup { execute(GroupPasteAttributes(ClipAttributes.of(source), it)) }
            }
            is SelectionIntent.SetGroupSpeed -> withGroup { execute(GroupSetSpeed(it, intent.num, intent.den)) }
            is SelectionIntent.SetGroupGain -> withGroup { execute(GroupSetGain(it, intent.gainDb)) }
            is SelectionIntent.SetGroupOpacity -> withGroup { execute(GroupSetOpacity(it, intent.opacity)) }
            is SelectionIntent.Align -> withGroup { execute(GroupAlign(it, intent.edge)) }
            is SelectionIntent.ApplyTransitions -> withGroup { ids ->
                val frames = state.value.fps.microsToFrames(TRANSITION_DEFAULT_MICROS).coerceAtLeast(Transition.MIN_DURATION_FRAMES)
                val lengths = ids.mapNotNull { id ->
                    val clip = timeline.trackOfClip(id)?.clip(id) ?: return@mapNotNull null
                    assetLengthFrames(clip.assetId)?.let { id to it }
                }.toMap()
                execute(GroupTransitions(ids, frames, intent.mode, lengths))
            }
        }
    }

    /** Runs [block] with the selected clip ids, or says that nothing is selected. */
    private inline fun withGroup(block: (List<String>) -> Unit) {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_first))) else block(ids)
    }

    private fun copySelection(announce: Boolean): Boolean {
        val board = Clipboard.capture(history.timeline, state.value.selection)
        if (board == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_clips_to_copy_first)))
            return false
        }
        clipboard = board
        val count = board.entries.size
        reduce { copy(clipboardCount = count) }
        if (announce) emit(EditorEffect.ShowMessage(if (count == 1) UiText.res(R.string.ed_vm_copied_clip) else UiText.res(R.string.ed_vm_copied_clips, count)))
        return true
    }

    /**
     * Paste. With another clip selected (one that is not among the copied ones) it asks what to paste: the content (a copy of the
     * copied clips at the playhead) or the style (their look and sound onto the selected clips). With nothing else selected it pastes
     * the content at once, as it always did.
     */
    private fun pasteClipboard() {
        val board = clipboard
        if (board == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_nothing_to_paste_copy_some)))
            return
        }
        val copied = board.entries.mapTo(HashSet()) { it.clip.id }
        val destinations = state.value.selection.filter { it !in copied && history.timeline.trackOfClip(it) != null }
        if (destinations.isEmpty()) {
            pasteContent()
        } else {
            reduce { copy(pasteChoice = PasteChoice(destinations, board.entries.size)) }
        }
    }

    private fun pasteContent() {
        val board = clipboard
        if (board == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_nothing_to_paste_copy_some)))
            return
        }
        runGroupCommand(GroupPaste(board, state.value.playhead), selectNew = true)
    }

    private fun pasteStyle() {
        val choice = state.value.pasteChoice
        reduce { copy(pasteChoice = null) }
        val source = clipboard?.primary
        if (choice == null || source == null) return
        val destinations = choice.destinations.filter { history.timeline.trackOfClip(it) != null }
        if (destinations.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_first)))
            return
        }
        execute(GroupPasteAttributes(ClipAttributes.of(source), destinations))
    }

    private fun deleteSelection() = withGroup { execute(GroupDelete(it)) }

    /** Runs a group [command] as one undo step; with [selectNew] the clips it created become the selection. */
    private fun runGroupCommand(command: EditCommand, selectNew: Boolean = false): Boolean {
        val before = history.timeline.tracks.flatMapTo(HashSet()) { track -> track.clips.map { it.id } }
        if (!execute(command)) return false
        if (selectNew) {
            val created = history.timeline.tracks.flatMap { track -> track.clips.map { it.id } }.filter { it !in before }
            if (created.isNotEmpty()) setSelection(LinkedHashSet(created), primary = created.first())
        }
        return true
    }
}
