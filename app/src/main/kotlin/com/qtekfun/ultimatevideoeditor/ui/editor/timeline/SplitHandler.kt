package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.GroupDelete
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Cutting at the playhead (the selected clips, or every clip when none is selected) and the ripple delete that closes the gap.
 */
internal class SplitHandler(
    host: EditorHost,
    private val playback: PlaybackController,
) : EditorHost by host {

    /**
     * With nothing selected cuts every clip under the playhead ([splitAllAtPlayhead]); otherwise cuts the selected clip, or every selected clip that spans the playhead, at the playhead (one undo step). The cut frame is
     * read before anything else because the playhead keeps moving while playing. Afterwards playback is paused and the
     * playhead sits exactly on the cut, the first frame of the right-hand part (LumaFusion pauses there too), the preview
     * shows that frame, and the selection moves to the right-hand parts so the next cut can follow without selecting again.
     */
    fun splitAtPlayhead() {
        if (state.value.selectedClipId == null && state.value.selection.isEmpty()) splitAllAtPlayhead() else splitSelectedAtPlayhead()
    }

    /**
     * Nothing selected: cuts every clip of every track that spans the playhead (LumaFusion), as one undo step. A linked
     * video+audio pair is cut by [ClipLinks.settle] after the batch (both halves are fresh, so the right-hand parts get
     * their own link); the selection stays empty.
     */
    private fun splitAllAtPlayhead() {
        val cut = state.value.playhead
        val commands = history.timeline.tracks.flatMap { track ->
            track.clips.filter { cut > it.timelineStart && cut < it.timelineEnd }
                .map { EditCommand.Split(track.id, cut, "${it.id}~${idGenerator()}") }
        }
        if (commands.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_a)))
            return
        }
        if (!execute(commands.singleOrNull() ?: EditCommand.Batch(commands))) return
        playback.pausePlayback()
        reduce { copy(playhead = cut) }
        playback.playbackOutput?.seek(cut.value)
    }

    private fun splitSelectedAtPlayhead() = withSelection { primary ->
        val cut = state.value.playhead
        val timeline = history.timeline
        val selected = state.value.selection
        fun splitOf(trackId: String, clip: Clip) = clip.id to EditCommand.Split(trackId, cut, "${clip.id}~${idGenerator()}")
        val spanning = selected.mapNotNull { id ->
            val track = timeline.trackOfClip(id) ?: return@mapNotNull null
            track.clip(id)?.takeIf { cut > it.timelineStart && cut < it.timelineEnd }?.let { splitOf(track.id, it) }
        }
        // None of the selected clips is under the playhead: the clip under it on the primary clip's lane is cut, as always.
        val commands = spanning.ifEmpty {
            val track = timeline.trackOfClip(primary)
            listOfNotNull(track?.clips?.firstOrNull { cut > it.timelineStart && cut < it.timelineEnd }?.let { splitOf(track.id, it) })
        }
        if (commands.isEmpty()) {
            // Nothing to cut: let the command say why ("Move the playhead inside the selected clip").
            execute(EditCommand.Split(timeline.trackOfClip(primary)?.id ?: return@withSelection, cut, "$primary~${idGenerator()}"))
            return@withSelection
        }
        val command = commands.singleOrNull()?.second ?: EditCommand.Batch(commands.map { it.second })
        if (!execute(command)) return@withSelection
        val rightOf = commands.associate { (id, split) -> id to split.newClipId } // the new id is the right-hand part
        playback.pausePlayback()
        // Only the parts that were cut stay selected: a selected clip the playhead was not over must not be taken by the delete that follows.
        val cutParts = if (spanning.isEmpty()) selected.toList() else rightOf.values.toList()
        reduce {
            copy(
                playhead = cut,
                selectedClipId = rightOf[primary] ?: cutParts.firstOrNull() ?: primary,
                selectedClipIds = if (cutParts.size > 1) cutParts.mapTo(LinkedHashSet()) { rightOf[it] ?: it } else emptySet(),
            )
        }
        playback.playbackOutput?.seek(cut.value)
    }

    /**
     * Deletes the selected clip(s) and parks the playhead where the cut was: the start of what was removed, which is where the
     * clip after it now begins (a delete closes the gap). Cut, delete the left part, and the cursor sits on the join instead
     * of staying over footage that has moved. Playback stops there, as after a split.
     */
    fun deleteSelectedAtCut() {
        val timeline = history.timeline
        val group = state.value.selection
        val ids = if (group.size > 1) group.toList() else listOfNotNull(state.value.selectedClipId)
        if (ids.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_first)))
            return
        }
        val cut = ids.mapNotNull { timeline.trackOfClip(it)?.clip(it)?.timelineStart?.value }.minOrNull()
        val done = if (group.size > 1) execute(GroupDelete(ids)) else execute(EditCommand.DeleteClip(ids.first()))
        if (done && cut != null) {
            playback.pausePlayback()
            playback.setPlayhead(cut)
        }
    }
}
