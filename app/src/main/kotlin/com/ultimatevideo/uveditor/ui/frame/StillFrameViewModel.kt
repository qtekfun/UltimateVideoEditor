package com.ultimatevideo.uveditor.ui.frame

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.domain.stillframe.FrameContent
import com.ultimatevideo.uveditor.domain.stillframe.JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.frameContent
import com.ultimatevideo.uveditor.domain.stillframe.frameFileName
import com.ultimatevideo.uveditor.domain.stillframe.frameNotice
import com.ultimatevideo.uveditor.domain.stillframe.frameTarget
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.mvi.MviViewModel
import com.ultimatevideo.uveditor.ui.export.ExportAvailability
import com.ultimatevideo.uveditor.ui.export.ExportJobHost
import com.ultimatevideo.uveditor.ui.export.exportAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDateTime

/**
 * "Save frame as image" in one tap (SPECS 5.35): draws the frame under the playhead at the project size with the exporter's
 * engine, encodes a JPEG and stores it in Pictures/ultimateVE. No dialog, no options; the outcome is shown as snackbars.
 * Refuses while an export runs: only one engine job at a time touches the hardware decoders.
 */
class StillFrameViewModel(
    private val renderer: FrameRenderer,
    private val encoder: FrameEncoder,
    private val sink: FrameSink,
    private val exports: ExportJobHost,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val projectId: String = "",
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : MviViewModel<StillFrameState, StillFrameIntent, StillFrameEffect>(StillFrameState()) {

    private var job: Job? = null
    private var lastSaved: SavedFrame? = null

    override fun onIntent(intent: StillFrameIntent) {
        when (intent) {
            is StillFrameIntent.Save -> save(intent.input)
            StillFrameIntent.Cancel -> if (state.value.isSaving) job?.cancel()
            StillFrameIntent.Share -> lastSaved?.let { emit(StillFrameEffect.ShareFile(it.uri)) }
            StillFrameIntent.Open -> lastSaved?.let { emit(StillFrameEffect.OpenFile(it.uri)) }
        }
    }

    private fun refusalWhileExporting(): String? = when (val availability = exportAvailability(exports.state.value, projectId)) {
        ExportAvailability.Available -> null
        is ExportAvailability.BlockedBy -> "Cannot save a frame: another export is running (${availability.projectName})."
        ExportAvailability.RunningHere -> "Cannot save a frame while this project is exporting. Wait for the export to finish."
    }

    private fun save(input: StillFrameInput) {
        if (state.value.isSaving) return
        refusalWhileExporting()?.let {
            emit(StillFrameEffect.Message(it))
            return
        }
        val content = frameContent(input.timeline, input.frame)
        if (content == FrameContent.EMPTY) {
            emit(StillFrameEffect.Message("There is nothing to save yet. Add a clip to the timeline."))
            return
        }
        val target = frameTarget(input.projectWidth, input.projectHeight)
        val name = frameFileName(input.projectName, input.frame, input.fps, now())
        reduce { copy(phase = StillFramePhase.Saving) }
        emit(StillFrameEffect.Started)
        job = viewModelScope.launch(ioDispatcher) {
            try {
                val frame = renderer.render(
                    FrameRenderJob(
                        timeline = input.timeline,
                        assets = input.assets,
                        fps = input.fps,
                        projectWidth = input.projectWidth,
                        projectHeight = input.projectHeight,
                        frame = input.frame,
                        width = target.size.width,
                        height = target.size.height,
                        missingAssetIds = input.missingAssetIds,
                    ),
                )
                val bytes = encoder.encodeJpeg(frame, JPEG_QUALITY)
                val saved = sink.saveJpeg(name, bytes)
                val notes = buildList {
                    if (target.reduced) add("The project is larger than 4096 px: saved at ${target.size.width} x ${target.size.height}.")
                    frameNotice(content)?.let(::add)
                }
                val result = SavedFrame(saved.uri, saved.displayName, saved.folder, frame.width, frame.height, notes)
                lastSaved = result
                reduce { copy(phase = StillFramePhase.Saved(result)) }
                emit(StillFrameEffect.Saved(result))
            } catch (e: CancellationException) {
                reduce { copy(phase = StillFramePhase.Idle) }
                emit(StillFrameEffect.Message("Saving the frame was cancelled."))
                throw e
            } catch (e: StillFrameException) {
                failed(e.message ?: "The picture could not be made.")
            } catch (e: ExportException) {
                failed("The picture could not be drawn: ${e.message}")
            } catch (e: IOException) {
                failed("The picture could not be saved: ${e.message}")
            } catch (e: OutOfMemoryError) {
                failed("Not enough memory for a picture this large.")
            }
        }
    }

    private fun failed(message: String) {
        reduce { copy(phase = StillFramePhase.Failed(message)) }
        emit(StillFrameEffect.Message(message))
    }

    override fun onCleared() {
        job?.cancel()
    }
}
