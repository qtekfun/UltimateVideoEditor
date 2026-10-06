package com.ultimatevideo.uveditor.ui.frame

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.FrameSource
import com.ultimatevideo.uveditor.domain.stillframe.frameFileName
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.mvi.MviViewModel
import com.ultimatevideo.uveditor.ui.editor.formatTimecode
import com.ultimatevideo.uveditor.ui.export.ExportAvailability
import com.ultimatevideo.uveditor.ui.export.ExportJobHost
import com.ultimatevideo.uveditor.ui.export.exportAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drives the "Save frame as image" dialog (SPECS 5.35): settings, the document picker round trip, drawing and encoding off
 * the main thread, and the result. Refuses while an export runs: only one engine job at a time touches the decoders.
 */
class StillFrameViewModel(
    private val renderer: FrameRenderer,
    private val encoder: FrameEncoder,
    private val sink: FrameSink,
    private val exports: ExportJobHost,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val projectId: String = "",
) : MviViewModel<StillFrameState, StillFrameIntent, StillFrameEffect>(StillFrameState()) {

    private var input: StillFrameInput? = null
    private var job: Job? = null

    override fun onIntent(intent: StillFrameIntent) {
        when (intent) {
            is StillFrameIntent.Open -> open(intent.input)
            StillFrameIntent.Dismiss -> dismiss()
            StillFrameIntent.ChooseLocation -> chooseLocation()
            is StillFrameIntent.LocationChosen -> intent.uri?.let(::save)
            StillFrameIntent.Cancel -> cancel()
            StillFrameIntent.Back -> if (!state.value.isWorking) reduce { copy(phase = StillFramePhase.Configuring) }
            StillFrameIntent.Share -> share()
            else -> reduce { edited(intent) }
        }
    }

    private fun open(newInput: StillFrameInput) {
        if (state.value.isWorking) return
        refusalWhileExporting()?.let {
            emit(StillFrameEffect.Message(it))
            return
        }
        input = newInput
        reduce { openedState(newInput, formatTimecode(newInput.frame, newInput.fps)) }
    }

    private fun refusalWhileExporting(): String? = when (val availability = exportAvailability(exports.state.value, projectId)) {
        ExportAvailability.Available -> null
        is ExportAvailability.BlockedBy -> "Cannot save a frame: another export is running (${availability.projectName})."
        ExportAvailability.RunningHere -> "Cannot save a frame while this project is exporting. Wait for the export to finish."
    }

    private fun dismiss() {
        if (state.value.isWorking) return // Cancel is the way out of a running save
        input = null
        reduce { copy(visible = false, phase = StillFramePhase.Configuring) }
    }

    private fun chooseLocation() {
        val current = state.value
        if (current.phase !is StillFramePhase.Configuring && current.phase !is StillFramePhase.Failed) return
        refusalWhileExporting()?.let {
            reduce { copy(phase = StillFramePhase.Failed(it)) }
            return
        }
        reduce { copy(phase = StillFramePhase.Configuring) }
        emit(StillFrameEffect.LaunchCreateDocument(frameFileName(current.projectName, current.timecode, current.format), current.format.mime))
    }

    private fun cancel() {
        if (state.value.isWorking) job?.cancel()
    }

    private fun share() {
        val phase = state.value.phase
        if (phase is StillFramePhase.Saved) emit(StillFrameEffect.ShareFile(phase.uri, state.value.format.mime))
    }

    private fun save(uri: String) {
        val source = input ?: return
        val current = state.value
        if (current.isWorking) return
        refusalWhileExporting()?.let {
            sink.delete(uri)
            reduce { copy(phase = StillFramePhase.Failed(it)) }
            return
        }
        val timeline = frameTimeline(current.source, source.timeline, source.selectedClipId)
        if (timeline == null) {
            sink.delete(uri)
            reduce { copy(phase = StillFramePhase.Failed("Select a clip under the playhead to save only that clip.")) }
            return
        }
        reduce { copy(phase = StillFramePhase.Working) }
        val plan = current.renderPlan
        val target = current.target
        val limit = if (current.format == FrameFormat.JPEG) current.sizeLimit else null
        job = viewModelScope.launch(ioDispatcher) {
            try {
                val frame = renderer.render(
                    FrameRenderJob(
                        timeline = timeline,
                        assets = source.assets,
                        fps = source.fps,
                        projectWidth = source.projectWidth,
                        projectHeight = source.projectHeight,
                        frame = source.frame,
                        plan = plan,
                        missingAssetIds = source.missingAssetIds,
                    ),
                )
                val encoded = encoder.encode(frame, current.format, current.jpegQuality, limit)
                sink.write(uri, encoded.bytes)
                val notes = notesFor(current, plan.shrunk, target.reduced, encoded, limit)
                reduce {
                    copy(
                        phase = StillFramePhase.Saved(
                            uri = uri,
                            fileName = sink.displayName(uri) ?: frameFileName(projectName, timecode, format),
                            width = frame.width,
                            height = frame.height,
                            bytes = encoded.bytes.size.toLong(),
                            quality = encoded.quality,
                            notes = notes,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                sink.delete(uri)
                reduce { copy(phase = StillFramePhase.Configuring) }
                throw e
            } catch (e: StillFrameException) {
                failed(uri, e.message ?: "The picture could not be made.")
            } catch (e: ExportException) {
                failed(uri, "The picture could not be drawn: ${e.message}")
            } catch (e: IOException) {
                failed(uri, "The file could not be written: ${e.message}")
            } catch (e: OutOfMemoryError) {
                failed(uri, "Not enough memory for a picture this large. Choose a smaller size.")
            }
        }
    }

    /** A failed save never leaves an empty file behind; when the provider refuses to delete it, the message says so. */
    private fun failed(uri: String, message: String) {
        val removed = sink.delete(uri)
        val extra = if (removed) "" else " An empty file may remain: ${sink.displayName(uri) ?: "the chosen file"}."
        reduce { copy(phase = StillFramePhase.Failed(message + extra)) }
    }

    override fun onCleared() {
        job?.cancel()
    }
}

/** What the saved file's result line should add: size limits, lowered quality, a reduced size. */
internal fun notesFor(state: StillFrameState, surfaceShrunk: Boolean, targetReduced: Boolean, encoded: EncodedFrame, limit: Long?): List<String> {
    val notes = ArrayList<String>()
    if (targetReduced || surfaceShrunk) notes += "The picture was made smaller than asked: the limit is 4096 px."
    when (state.format) {
        FrameFormat.JPEG -> {
            val used = encoded.quality
            if (limit != null && used != null && used < state.jpegQuality) notes += "JPEG quality lowered to $used to stay under 2 MB."
            if (limit != null && !encoded.fitsLimit) notes += "The file is still over 2 MB at the lowest quality. Choose a smaller size."
        }
        FrameFormat.PNG -> if (state.sizeLimit?.let { encoded.bytes.size > it } == true) {
            notes += "The PNG is over 2 MB, which YouTube refuses for thumbnails. Save it as JPEG."
        }
    }
    return notes
}

/** The kind of picture the dialog describes for its source choice. */
internal fun FrameSource.describe(): String = when (this) {
    FrameSource.WHOLE_PICTURE -> "Whole picture"
    FrameSource.SELECTED_CLIP -> "Selected clip only"
}
