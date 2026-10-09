package com.qtekfun.ultimatevideoeditor.ui.editor.multicam

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleCut
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleFeed
import com.qtekfun.ultimatevideoeditor.domain.multicam.AudioSync
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamAngle
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamOps
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamPlanner
import com.qtekfun.ultimatevideoeditor.engine.multicam.MulticamServices
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The logic behind the multicam sheet, kept apart from `EditorViewModel` (which only forwards intents and
 * lends its state, undo history and messages through [Host]). Picking angles, syncing them by their sound,
 * creating the clip, and cutting between angles live or by tapping.
 */
internal class MulticamController(
    private val host: Host,
    private val services: MulticamServices,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    interface Host {
        val editor: EditorState
        fun update(change: (MulticamUiState) -> MulticamUiState)

        /** Runs [command] through the undo history; false (after telling the user why) when it failed. */
        fun execute(command: EditCommand): Boolean
        fun message(text: UiText)
        fun newId(): String

        /** Length of a library file in project frames, or null for a picture or an unknown file. */
        fun assetLengthFrames(assetId: String): Long?
    }

    private val ui: MulticamUiState get() = host.editor.multicam

    fun handle(intent: MulticamIntent) {
        when (intent) {
            MulticamIntent.Open -> host.update { it.copy(open = true) }
            MulticamIntent.Close -> host.update { it.copy(open = false, recording = false, pendingCuts = emptyList()) }
            is MulticamIntent.ToggleAsset -> toggleAsset(intent.assetId)
            MulticamIntent.Sync -> syncDraft()
            is MulticamIntent.NudgeDraft -> host.update {
                it.copy(draft = it.draft.copy(offsets = it.draft.offsets + (intent.assetId to it.draft.offsetOf(intent.assetId) + intent.deltaFrames)))
            }
            MulticamIntent.Create -> create()
            is MulticamIntent.CutTo -> cutTo(intent.angle)
            MulticamIntent.ToggleRecording -> toggleRecording()
            is MulticamIntent.NudgeAngle -> host.execute(EditCommand.NudgeMulticamAngle(intent.groupId, intent.angle, intent.deltaFrames))
            is MulticamIntent.SetAudioAngle -> host.execute(EditCommand.SetMulticamAudioAngle(intent.groupId, intent.angle))
            is MulticamIntent.RemoveCutHere -> removeCutHere(intent.groupId)
            is MulticamIntent.Resync -> resync(intent.groupId)
            is MulticamIntent.Flatten -> host.execute(EditCommand.FlattenMulticam(intent.groupId))
        }
    }

    /** The multicam clip the controls act on: the one the selected clip belongs to, else the only one there is. */
    fun activeGroup(editor: EditorState = host.editor): MulticamClip? = groupOf(editor.timeline, editor.selectedClipId)

    /** Where each angle of [group] gets its picture from while [active] is on screen, within the decoder budget. */
    fun feeds(group: MulticamClip, active: Int): List<AngleFeed> {
        val assets = host.editor.assets.associateBy { it.id }
        val proxyReady = group.angles.indices.filter { i -> assets[group.angles[i].assetId]?.let(services.hasProxy) == true }.toSet()
        return MulticamPlanner.plan(group.angles.size, active.coerceIn(group.angles.indices), proxyReady, services.maxDecoders)
    }

    private fun toggleAsset(assetId: String) {
        val asset = host.editor.assets.firstOrNull { it.id == assetId }
        if (asset == null || !asset.hasAudio || asset.isImage) {
            host.message(UiText.res(R.string.ed_s3_mc_only_sound))
            return
        }
        val draft = ui.draft
        if (assetId in draft.assetIds) {
            host.update { it.copy(draft = MulticamDraft(draft.assetIds - assetId)) }
        } else if (draft.assetIds.size >= MulticamOps.MAX_ANGLES) {
            host.message(UiText.res(R.string.ed_s3_mc_max_angles, MulticamOps.MAX_ANGLES))
        } else {
            host.update { it.copy(draft = MulticamDraft(draft.assetIds + assetId)) }
        }
    }

    private fun syncDraft() {
        val ids = ui.draft.assetIds
        if (ids.size < MulticamOps.MIN_ANGLES) {
            host.message(UiText.res(R.string.ed_s3_mc_pick_at_least, MulticamOps.MIN_ANGLES))
            return
        }
        host.update { it.copy(syncing = true) }
        scope.launch {
            val outcomes = syncAgainstFirst(ids)
            host.update { state ->
                val offsets = ids.associateWith { id ->
                    (outcomes[id] as? SyncOutcome.Found)?.takeIf { it.confident }?.offsetFrames ?: 0L
                }
                state.copy(syncing = false, draft = state.draft.copy(offsets = offsets, outcomes = outcomes))
            }
            val failed = outcomes.values.count { it is SyncOutcome.Failed || (it is SyncOutcome.Found && !it.confident) }
            if (failed > 0) host.message(UiText.plural(R.plurals.ed_s3_mc_not_synced, failed))
        }
    }

    /** Offsets of every asset in [ids] against the first one, from the loudness of their audio. */
    private suspend fun syncAgainstFirst(ids: List<String>): Map<String, SyncOutcome> = withContext(dispatcher) {
        val fps = host.editor.fps
        val reference = services.envelopes.envelope(ids.first())
        ids.mapIndexed { index, id ->
            id to when {
                index == 0 -> SyncOutcome.Reference
                reference == null -> SyncOutcome.Failed("the waveform of the first angle is not ready yet") // i18n-ok: a log reason, not shown
                else -> {
                    val other = services.envelopes.envelope(id)
                    val result = other?.let { AudioSync.offsetOf(reference, it, fps.num.toLong(), fps.den.toLong()) }
                    when {
                        other == null -> SyncOutcome.Failed("its waveform is not ready yet") // i18n-ok: a log reason, not shown
                        result == null -> SyncOutcome.Failed("no usable sound to match") // i18n-ok: a log reason, not shown
                        else -> SyncOutcome.Found(result.offsetFrames, result.confidence, result.isConfident)
                    }
                }
            }
        }.toMap()
    }

    private fun create() {
        val editor = host.editor
        val ids = ui.draft.assetIds
        if (ids.size < MulticamOps.MIN_ANGLES) {
            host.message(UiText.res(R.string.ed_s3_mc_pick_at_least, MulticamOps.MIN_ANGLES))
            return
        }
        val assets = ids.map { id -> editor.assets.first { it.id == id } }
        val angles = assets.mapIndexed { i, asset ->
            val length = host.assetLengthFrames(asset.id)
            if (length == null) {
                host.message(UiText.res(R.string.ed_s3_mc_no_length, displayName(asset)))
                return
            }
            MulticamAngle("angle-${host.newId()}", "Cam ${'A' + i}" // i18n-ok: a stored angle name, asset.id, ui.draft.offsetOf(asset.id), length)
        }
        val inFrame = angles.maxOf { it.coverageStart }
        val length = angles.minOf { it.coverageEnd } - inFrame
        if (length < MIN_LENGTH_FRAMES) {
            host.message(UiText.res(R.string.ed_s3_mc_hardly_overlap))
            return
        }
        val timeline = editor.timeline
        val base = ClipDeletion.baseTrack(timeline)
        if (base == null) {
            host.message(UiText.res(R.string.ed_s3_mc_no_base))
            return
        }
        val start = editor.playhead.value
        val audioLane = timeline.tracks.firstOrNull { track ->
            track.type == TrackType.AUDIO && track.clips.none { it.timelineStart.value < start + length && start < it.timelineEnd.value }
        }
        val group = MulticamClip(
            id = host.newId(),
            name = "Multicam", // i18n-ok: a stored clip name
            angles = angles,
            audioAngle = 0,
            videoTrackId = base.id,
            audioTrackId = audioLane?.id,
            startFrame = start,
            inFrame = inFrame,
            lengthFrames = length,
            cuts = listOf(AngleCut(0, 0)),
        )
        if (host.execute(EditCommand.CreateMulticam(group))) {
            host.update { it.copy(draft = MulticamDraft()) }
            if (audioLane == null) host.message(UiText.res(R.string.ed_s3_mc_no_audio_lane))
        }
    }

    private fun cutTo(angle: Int) {
        val group = activeGroup() ?: return host.message(UiText.res(R.string.ed_s3_mc_select_first))
        if (angle !in group.angles.indices) return
        val frame = (host.editor.playhead.value - group.startFrame).coerceIn(0, group.lengthFrames - 1)
        if (ui.recording) {
            host.update { it.copy(pendingCuts = it.pendingCuts + AngleCut(frame, angle)) }
        } else {
            host.execute(EditCommand.CutMulticam(group.id, frame, angle))
        }
    }

    private fun toggleRecording() {
        if (!ui.recording) {
            if (activeGroup() == null) return host.message(UiText.res(R.string.ed_s3_mc_select_first))
            host.update { it.copy(recording = true, pendingCuts = emptyList()) }
            host.message(UiText.res(R.string.ed_s3_mc_recording_hint))
            return
        }
        val cuts = ui.pendingCuts
        val group = activeGroup()
        host.update { it.copy(recording = false, pendingCuts = emptyList()) }
        if (group != null && cuts.isNotEmpty()) host.execute(EditCommand.RecordMulticam(group.id, cuts))
    }

    private fun removeCutHere(groupId: String) {
        val group = host.editor.timeline.multicam(groupId) ?: return
        val frame = host.editor.playhead.value - group.startFrame
        val cut = group.cuts.lastOrNull { it.frame <= frame && it.frame > 0 }
            ?: return host.message(UiText.res(R.string.ed_s3_mc_no_cut))
        host.execute(EditCommand.RemoveMulticamCut(groupId, cut.frame))
    }

    private fun resync(groupId: String) {
        val group = host.editor.timeline.multicam(groupId) ?: return
        host.update { it.copy(syncing = true) }
        scope.launch {
            val outcomes = syncAgainstFirst(group.angles.map { it.assetId })
            host.update { it.copy(syncing = false) }
            val reference = group.angles.first().offsetFrames
            val offsets = group.angles.mapIndexed { i, angle ->
                val found = outcomes[angle.assetId] as? SyncOutcome.Found
                if (i == 0) angle.offsetFrames else if (found != null && found.confident) reference + found.offsetFrames else angle.offsetFrames
            }
            if (offsets == group.angles.map { it.offsetFrames }) {
                host.message(UiText.res(R.string.ed_s3_mc_nothing_lined))
            } else {
                host.execute(EditCommand.SetMulticamOffsets(groupId, offsets))
            }
        }
    }

    private fun displayName(asset: MediaAssetDto): String = asset.displayName?.ifBlank { null } ?: asset.id

    companion object {
        /** Anything shorter than this overlap is not worth a multicam clip. */
        const val MIN_LENGTH_FRAMES = 15L

        private val realised = Regex("^mc-(.+)-(v\\d+|a)$")

        /** The multicam clip that [clipId] is one of the realised clips of, else the only multicam clip of [timeline]. */
        fun groupOf(timeline: Timeline, clipId: String?): MulticamClip? {
            val id = clipId?.let { realised.matchEntire(it)?.groupValues?.get(1) }
            return id?.let(timeline::multicam) ?: timeline.multicams.singleOrNull()
        }
    }
}
