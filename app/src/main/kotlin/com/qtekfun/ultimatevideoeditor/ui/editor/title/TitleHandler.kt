package com.qtekfun.ultimatevideoeditor.ui.editor.title

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.AddCaptions
import com.qtekfun.ultimatevideoeditor.domain.AddCaptionsToTrack
import com.qtekfun.ultimatevideoeditor.domain.AddTextTemplate
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.LayerPlacement
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.SetTitleMotion
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.TextTemplates
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import com.qtekfun.ultimatevideoeditor.domain.TitleMotion
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.captions.CAPTION_ID_PREFIX
import com.qtekfun.ultimatevideoeditor.engine.still.StickerIds
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Titles, stickers and captions on the timeline: adding them, the text and style editor (an edit in progress is shown live and committed as
 * one undo step), layer gestures on the preview, motion presets and text templates.
 */
internal class TitleHandler(
    host: EditorHost,
) : EditorHost by host {

    /** A title text/style edit in progress: shown live, committed as one undo step. */
    private class TitleSession(val clipId: String, val base: TitleContent, var content: TitleContent)

    private var titleEdit: TitleSession? = null

    /** Forgets the edit in progress without committing it (the timeline changed under it). */
    fun discard() {
        titleEdit = null
    }

    fun applyTextTemplate(templateId: String, text: String) = applyTemplate(TextTemplates.find(templateId), text)

    /** Puts [template] (a built-in or a saved preset) on the timeline at the playhead as one undo step and selects it. */
    fun applyTemplate(template: TextTemplate?, text: String) {
        if (template == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_text_template_is_not)))
            return
        }
        val fps = state.value.fps
        val frames = fps.microsToFrames((template.defaultSeconds * MICROS_PER_SECOND).toLong()).coerceAtLeast(2)
        val token = idGenerator()
        val ids = List(TextTemplates.idCount(template)) { "tpl-$token-$it" }
        val command = AddTextTemplate(
            templateId = template.id,
            template = template,
            text = text.ifBlank { template.defaultText },
            start = state.value.playhead,
            durationFrames = frames,
            canvasWidth = state.value.canvasWidth,
            canvasHeight = state.value.canvasHeight,
            fps = fps,
            ids = ids,
        )
        if (!execute(command)) return
        // A template is one title clip: select it so the inspector shows what the user will want to change.
        val clipId = ids[0]
        val trackId = history.timeline.trackOfClip(clipId)?.id
        reduce { copy(selectedClipId = clipId, selectedTrackId = trackId, inspectorOpen = true) }
    }

    fun addTitle() {
        val timeline = history.timeline
        val trackId = timeline.tracks.firstOrNull { it.type == TrackType.TITLE }?.id ?: run {
            val track = Track(uniqueTrackId(timeline.tracks, "track-t"), TrackType.TITLE)
            // Titles go above everything, like the top layer of a mixer.
            if (!execute(EditCommand.AddTrack(track, 0))) return
            track.id
        }
        val fps = state.value.fps
        val frames = (fps.microsToFrames(TITLE_DEFAULT_MICROS)).coerceAtLeast(1)
        val clip = Clip(
            id = "title-${idGenerator()}",
            assetId = null,
            timelineStart = state.value.playhead,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(frames),
            title = TitleContent(DEFAULT_TITLE_TEXT),
        )
        if (!execute(EditCommand.Overwrite(trackId, clip))) return
        reduce { copy(selectedClipId = clip.id, selectedTrackId = trackId, inspectorOpen = true) }
    }

    fun addSticker(stickerId: String) {
        if (!StickerIds.isKnown(stickerId)) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_sticker_is_not_available)))
            return
        }
        val timeline = history.timeline
        val base = ClipDeletion.baseTrack(timeline)
        val selected = timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        val overlay = selected?.takeIf { it.type == TrackType.VIDEO && it.id != base?.id }
            ?: timeline.tracks.firstOrNull { it.type == TrackType.VIDEO && it.id != base?.id }
        val trackId = overlay?.id ?: run {
            // Only the base exists: stickers get a lane above it (its own undo step).
            val track = Track(uniqueTrackId(timeline.tracks, "track-v"), TrackType.VIDEO)
            val index = timeline.tracks.indexOfFirst { it.type == TrackType.VIDEO }.coerceAtLeast(0)
            if (!execute(EditCommand.AddTrack(track, index))) return
            track.id
        }
        val frames = state.value.fps.microsToFrames(STICKER_DEFAULT_MICROS).coerceAtLeast(1)
        val clip = Clip(
            id = "sticker-${idGenerator()}",
            assetId = stickerId,
            timelineStart = state.value.playhead,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(frames),
            still = StillKind.STICKER,
        )
        if (!execute(EditCommand.Overwrite(trackId, clip))) return
        reduce { copy(selectedClipId = clip.id, selectedTrackId = trackId, inspectorOpen = true) }
    }

    fun addCaptionClips(clips: List<Clip>, intoExistingTrack: Boolean) {
        if (clips.isEmpty()) return
        // Typed captions share one caption track; an imported file gets a track of its own (one per language).
        val existing = history.timeline.tracks.firstOrNull { track ->
            track.type == TrackType.TITLE && track.clips.isNotEmpty() && track.clips.all { it.id.startsWith(CAPTION_ID_PREFIX) }
        }
        if (intoExistingTrack && existing != null) {
            if (!execute(AddCaptionsToTrack(existing.id, clips))) return
            reduce { copy(selectedTrackId = existing.id, selectedClipId = clips.first().id) }
            return
        }
        val track = Track(uniqueTrackId(history.timeline.tracks, "track-t"), TrackType.TITLE)
        // Captions go above everything, like any title, on their own track so they never cut an existing title.
        if (!execute(AddCaptions(track, 0, clips))) return
        reduce { copy(selectedTrackId = track.id, selectedClipId = clips.first().id) }
    }

    fun updateTitle(content: TitleContent) {
        val clipId = state.value.selectedClipId ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val base = clip.title ?: return
        // An invalid value (an empty text while typing) is not shown; the last valid one stays.
        if (content.problem() != null) return
        val session = titleEdit?.takeIf { it.clipId == clipId } ?: TitleSession(clipId, base, base).also { titleEdit = it }
        session.content = content
        val result = EditCommand.SetTitle(clipId, content).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    fun endTitleEdit(commit: Boolean) {
        val session = titleEdit ?: return
        titleEdit = null
        if (commit && session.content != session.base && execute(EditCommand.SetTitle(session.clipId, session.content))) return
        reduce { copy(dragPreview = null) }
    }

    fun selectTitleLayer(index: Int) {
        val clipId = state.value.selectedClipId
        reduce { copy(titleLayerRef = if (clipId != null && index >= 0) TitleLayerRef(clipId, index) else null) }
    }

    /** A step of a preview gesture on the selected layer of a multilayer title: moves, scales and turns just that layer. */
    fun layerGesture(step: EditorIntent.LayerGesture) {
        val ref = state.value.titleLayerRef ?: return
        val clipId = state.value.selectedClipId?.takeIf { it == ref.clipId } ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val base = titleEdit?.takeIf { it.clipId == clipId }?.content ?: clip.title ?: return
        if (!base.isLayered || ref.index !in base.layers.indices) return
        val layer = base.layers[ref.index]
        val p = layer.placement
        // Gestures are in canvas pixels; offsets are canvas fractions inside a title that the clip's own scale then enlarges.
        val clipScale = clip.transform.scaleX.coerceAtLeast(MIN_CLIP_SCALE)
        val moved = p.copy(
            offsetX = (p.offsetX + step.panX / (state.value.canvasWidth * clipScale)).coerceIn(-LayerPlacement.MAX_OFFSET, LayerPlacement.MAX_OFFSET),
            offsetY = (p.offsetY + step.panY / (state.value.canvasHeight * clipScale)).coerceIn(-LayerPlacement.MAX_OFFSET, LayerPlacement.MAX_OFFSET),
            scale = (p.scale * step.zoom).coerceIn(LayerPlacement.MIN_SCALE, LayerPlacement.MAX_SCALE),
            rotationDegrees = p.rotationDegrees + step.rotationDegrees,
        )
        updateTitle(TitleLayerEdit.replace(base, ref.index, layer.withPlacement(moved)))
    }

    /** In and out animation of the selected title as keyframes, replacing the clip's own. */
    fun applyTitleMotion(intro: MotionPreset, outro: MotionPreset) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId)
        if (clip?.title == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_title_first)))
            return@withSelection
        }
        val edge = state.value.fps.microsToFrames((TitleMotion.DEFAULT_EDGE_SECONDS * MICROS_PER_SECOND).toLong()).coerceAtLeast(1)
        execute(SetTitleMotion(clipId, intro, outro, edge, state.value.canvasWidth, state.value.canvasHeight))
    }
}
