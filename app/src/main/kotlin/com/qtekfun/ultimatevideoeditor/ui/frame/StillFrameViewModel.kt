package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.domain.stillframe.FrameContent
import com.qtekfun.ultimatevideoeditor.domain.stillframe.JPEG_QUALITY
import com.qtekfun.ultimatevideoeditor.domain.stillframe.frameContent
import com.qtekfun.ultimatevideoeditor.domain.stillframe.frameFileName
import com.qtekfun.ultimatevideoeditor.domain.stillframe.frameTarget
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.mvi.MviViewModel
import com.qtekfun.ultimatevideoeditor.ui.export.ExportAvailability
import com.qtekfun.ultimatevideoeditor.ui.export.ExportJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.exportAvailability
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

    private fun refusalWhileExporting(): UiText? = when (val availability = exportAvailability(exports.state.value, projectId)) {
        ExportAvailability.Available -> null
        is ExportAvailability.BlockedBy -> UiText.res(R.string.ed_s3_frame_busy_other, availability.projectName)
        ExportAvailability.RunningHere -> UiText.res(R.string.ed_s3_frame_busy_here)
    }

    private fun save(input: StillFrameInput) {
        if (state.value.isSaving) return
        refusalWhileExporting()?.let {
            emit(StillFrameEffect.Message(it))
            return
        }
        val content = frameContent(input.timeline, input.frame)
        if (content == FrameContent.EMPTY) {
            emit(StillFrameEffect.Message(UiText.res(R.string.ed_s3_frame_nothing)))
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
                    if (target.reduced) add(UiText.res(R.string.ed_s3_frame_reduced, target.size.width, target.size.height))
                    frameNoticeText(content)?.let(::add)
                }
                val result = SavedFrame(saved.uri, saved.displayName, saved.folder, frame.width, frame.height, notes)
                lastSaved = result
                reduce { copy(phase = StillFramePhase.Saved(result)) }
                emit(StillFrameEffect.Saved(result))
            } catch (e: CancellationException) {
                reduce { copy(phase = StillFramePhase.Idle) }
                emit(StillFrameEffect.Message(UiText.res(R.string.ed_s3_frame_cancelled)))
                throw e
            } catch (e: StillFrameException) {
                failed(e.text)
            } catch (e: ExportException) {
                failed(UiText.res(R.string.ed_s3_frame_not_drawn, e.message.orEmpty()))
            } catch (e: IOException) {
                failed(UiText.res(R.string.ed_s3_frame_not_saved, e.message.orEmpty()))
            } catch (e: OutOfMemoryError) {
                failed(UiText.res(R.string.ed_s3_frame_memory))
            }
        }
    }

    private fun failed(message: UiText) {
        reduce { copy(phase = StillFramePhase.Failed(message)) }
        emit(StillFrameEffect.Message(message))
    }

    override fun onCleared() {
        job?.cancel()
    }
}

/** The note that goes with a picture that is black on purpose, or null when the frame has a picture. */
internal fun frameNoticeText(content: FrameContent): UiText? = when (content) {
    FrameContent.PICTURE -> null
    FrameContent.GAP -> UiText.res(R.string.ed_s3_frame_notice_gap)
    FrameContent.PAST_END -> UiText.res(R.string.ed_s3_frame_notice_past_end)
    FrameContent.EMPTY -> UiText.res(R.string.ed_s3_frame_notice_empty)
}
