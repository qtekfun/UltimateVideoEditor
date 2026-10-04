package com.ultimatevideo.uveditor.ui.editor.multicam

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.ClipDeletion
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.multicam.AngleCut
import com.ultimatevideo.uveditor.domain.multicam.AngleFeed
import com.ultimatevideo.uveditor.domain.multicam.AudioSync
import com.ultimatevideo.uveditor.domain.multicam.MulticamAngle
import com.ultimatevideo.uveditor.domain.multicam.MulticamClip
import com.ultimatevideo.uveditor.domain.multicam.MulticamOps
import com.ultimatevideo.uveditor.domain.multicam.MulticamPlanner
import com.ultimatevideo.uveditor.engine.multicam.MulticamServices
import com.ultimatevideo.uveditor.ui.editor.EditorState
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
        fun message(text: String)
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
            host.message("Only a video or audio file with sound can be an angle")
            return
        }
        val draft = ui.draft
        if (assetId in draft.assetIds) {
            host.update { it.copy(draft = MulticamDraft(draft.assetIds - assetId)) }
        } else if (draft.assetIds.size >= MulticamOps.MAX_ANGLES) {
            host.message("A multicam clip has at most ${MulticamOps.MAX_ANGLES} angles")
        } else {
            host.update { it.copy(draft = MulticamDraft(draft.assetIds + assetId)) }
        }
    }

    private fun syncDraft() {
        val ids = ui.draft.assetIds
        if (ids.size < MulticamOps.MIN_ANGLES) {
            host.message("Pick at least ${MulticamOps.MIN_ANGLES} angles first")
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
            if (failed > 0) host.message("$failed angle(s) could not be lined up with confidence: nudge them by ear")
        }
    }

    /** Offsets of every asset in [ids] against the first one, from the loudness of their audio. */
    private suspend fun syncAgainstFirst(ids: List<String>): Map<String, SyncOutcome> = withContext(dispatcher) {
        val fps = host.editor.fps
        val reference = services.envelopes.envelope(ids.first())
        ids.mapIndexed { index, id ->
            id to when {
                index == 0 -> SyncOutcome.Reference
                reference == null -> SyncOutcome.Failed("the waveform of the first angle is not ready yet")
                else -> {
                    val other = services.envelopes.envelope(id)
                    val result = other?.let { AudioSync.offsetOf(reference, it, fps.num.toLong(), fps.den.toLong()) }
                    when {
                        other == null -> SyncOutcome.Failed("its waveform is not ready yet")
                        result == null -> SyncOutcome.Failed("no usable sound to match")
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
            host.message("Pick at least ${MulticamOps.MIN_ANGLES} angles first")
            return
        }
        val assets = ids.map { id -> editor.assets.first { it.id == id } }
        val angles = assets.mapIndexed { i, asset ->
            val length = host.assetLengthFrames(asset.id)
            if (length == null) {
                host.message("${displayName(asset)} has no length to cut")
                return
            }
            MulticamAngle("angle-${host.newId()}", "Cam ${'A' + i}", asset.id, ui.draft.offsetOf(asset.id), length)
        }
        val inFrame = angles.maxOf { it.coverageStart }
        val length = angles.minOf { it.coverageEnd } - inFrame
        if (length < MIN_LENGTH_FRAMES) {
            host.message("The angles hardly overlap in time: sync them or nudge their start first")
            return
        }
        val timeline = editor.timeline
        val base = ClipDeletion.baseTrack(timeline)
        if (base == null) {
            host.message("A multicam clip goes on the base track, and there is none")
            return
        }
        val start = editor.playhead.value
        val audioLane = timeline.tracks.firstOrNull { track ->
            track.type == TrackType.AUDIO && track.clips.none { it.timelineStart.value < start + length && start < it.timelineEnd.value }
        }
        val group = MulticamClip(
            id = host.newId(),
            name = "Multicam",
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
            if (audioLane == null) host.message("No free audio lane: each angle keeps its own sound when you cut")
        }
    }

    private fun cutTo(angle: Int) {
        val group = activeGroup() ?: return host.message("Select a multicam clip first")
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
            if (activeGroup() == null) return host.message("Select a multicam clip first")
            host.update { it.copy(recording = true, pendingCuts = emptyList()) }
            host.message("Recording: play the video and tap an angle to cut to it")
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
            ?: return host.message("There is no cut at or before the playhead to remove")
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
                host.message("Nothing could be lined up with confidence: nudge the angles by ear")
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
