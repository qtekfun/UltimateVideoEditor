package com.ultimatevideo.uveditor.ui.export

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.mvi.MviViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Drives the export dialog: settings, the document picker round trip, progress and cancellation.
 * The heavy lifting is on the native export thread; descriptors are opened off the main thread.
 */
class ExportViewModel(
    private val io: ExportIO,
    private val runner: ExportRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MviViewModel<ExportState, ExportIntent, ExportEffect>(ExportState()) {

    private var input: ExportInput? = null
    private var outputUri: String? = null
    private var handle: ExportHandle? = null
    private val lock = Any()

    override fun onIntent(intent: ExportIntent) {
        when (intent) {
            is ExportIntent.Open -> open(intent.input)
            ExportIntent.Dismiss -> dismiss()
            is ExportIntent.SelectResolution -> editSettings { copy(resolution = intent.option) }
            is ExportIntent.SelectFrameRate -> editSettings { copy(frameRate = intent.rate) }
            is ExportIntent.SelectCodec -> editSettings { copy(codec = intent.codec) }
            is ExportIntent.SelectBitrate -> if (!state.value.isRunning) reduce { copy(bitrateMbps = intent.mbps, phase = ExportPhase.Configuring) }
            ExportIntent.ChooseLocation -> chooseLocation()
            is ExportIntent.LocationChosen -> intent.uri?.let(::start)
            ExportIntent.Cancel -> synchronized(lock) { handle?.cancel() }
            ExportIntent.Share -> share()
        }
    }

    private fun open(newInput: ExportInput) {
        if (state.value.isRunning) return
        input = newInput
        val resolutions = resolutionOptions(newInput.projectWidth, newInput.projectHeight)
        val rates = frameRateOptions(newInput.fps)
        val codec = state.value.codec
        val resolution = resolutions.first()
        reduce {
            copy(
                visible = true,
                projectName = newInput.projectName,
                resolutions = resolutions,
                resolution = resolution,
                frameRates = rates,
                frameRate = rates.first(),
                bitrateMbps = suggestedBitrateMbps(resolution.width, resolution.height, rates.first(), codec),
                phase = ExportPhase.Configuring,
            )
        }
    }

    private fun dismiss() {
        if (!state.value.isRunning) reduce { copy(visible = false, phase = ExportPhase.Configuring) }
    }

    /** Changing the size, rate or codec re-suggests a bitrate for the new settings. */
    private fun editSettings(change: ExportState.() -> ExportState) {
        if (state.value.isRunning) return
        reduce {
            val next = change()
            val resolution = next.resolution
            val rate = next.frameRate
            if (resolution == null || rate == null) {
                next.copy(phase = ExportPhase.Configuring)
            } else {
                next.copy(
                    bitrateMbps = suggestedBitrateMbps(resolution.width, resolution.height, rate, next.codec),
                    phase = ExportPhase.Configuring,
                )
            }
        }
    }

    private fun chooseLocation() {
        val current = state.value
        if (current.isRunning || current.resolution == null || current.frameRate == null) return
        emit(ExportEffect.LaunchCreateDocument(suggestedFileName(current.projectName)))
    }

    private fun share() {
        val phase = state.value.phase
        if (phase is ExportPhase.Done) emit(ExportEffect.ShareFile(phase.uri))
    }

    private fun start(uri: String) {
        val source = input ?: return
        val current = state.value
        val resolution = current.resolution ?: return
        val rate = current.frameRate ?: return
        if (current.isRunning) return
        reduce { copy(phase = ExportPhase.Running(0)) }
        outputUri = uri
        viewModelScope.launch {
            val failure = try {
                withContext(ioDispatcher) { launchExport(source, uri, resolution, rate, current) }
                null
            } catch (e: ExportException) {
                e
            } catch (e: IllegalArgumentException) {
                ExportException(ExportErrorCode.INVALID_ARGUMENT, "Invalid export settings: ${e.message}")
            }
            if (failure != null) withContext(ioDispatcher) { finish(failure) }
        }
    }

    /** Opens every descriptor, then hands them to the engine. Runs on [ioDispatcher]. */
    private fun launchExport(
        source: ExportInput,
        outputUri: String,
        resolution: ResolutionOption,
        rate: FrameRate,
        current: ExportState,
    ) {
        val plan = buildExportPlan(source.timeline, source.assets, source.fps)
            ?: throw ExportException(ExportErrorCode.INVALID_ARGUMENT, "There is nothing to export yet. Add a clip to the timeline.")
        val uriByAsset = source.assets.associate { it.id to it.uri }
        val opened = LinkedHashMap<Long, Int>()
        var outputFd = -1
        try {
            for ((assetId, key) in plan.assetKeys) {
                val uri = uriByAsset[assetId] ?: throw IOException("A clip refers to media that is no longer in the project")
                opened[key] = io.openAsset(uri)
            }
            outputFd = io.openOutput(outputUri)
        } catch (e: IOException) {
            opened.values.forEach(io::close)
            if (outputFd >= 0) io.close(outputFd)
            throw ExportException(ExportErrorCode.IO_ERROR, "Cannot open a file for the export: ${e.message}")
        }

        val settings = ExportSettings(
            width = resolution.width,
            height = resolution.height,
            fpsNum = rate.num,
            fpsDen = rate.den,
            codec = current.codec,
            videoBitrate = current.bitrateMbps * BITS_PER_MEGABIT,
        )
        val request = ExportRequest(
            settings = settings,
            projectFpsNum = source.fps.num,
            projectFpsDen = source.fps.den,
            canvasWidth = source.projectWidth,
            canvasHeight = source.projectHeight,
            totalFrames = outputFrameCount(plan.projectFrames, source.fps, rate),
            assetFds = opened,
            videoClips = plan.videoClips,
            audioSnapshot = plan.audio?.encode(),
            outputFd = outputFd,
        )
        val started = runner.start(
            request,
            object : ExportListener {
                override fun onProgress(permille: Int) {
                    reduce { if (phase is ExportPhase.Running) copy(phase = ExportPhase.Running(permille)) else this }
                }

                override fun onFinished(error: ExportException?) {
                    viewModelScope.launch(ioDispatcher) { finish(error) }
                }
            },
        )
        synchronized(lock) { handle = started }
    }

    /** Releases the engine (joins its thread), then publishes the outcome and cleans up on failure. */
    private fun finish(error: ExportException?) {
        val finished = synchronized(lock) { handle.also { handle = null } }
        finished?.close()
        val uri = outputUri
        if (error == null && uri != null) {
            reduce { copy(phase = ExportPhase.Done(uri, suggestedFileName(projectName))) }
            return
        }
        if (uri != null) io.deleteOutput(uri)
        outputUri = null
        reduce {
            if (error?.code == ExportErrorCode.CANCELLED) copy(phase = ExportPhase.Configuring) else copy(phase = ExportPhase.Failed(describe(error)))
        }
    }

    private fun describe(error: ExportException?): String = when (error?.code) {
        null -> "The export failed"
        ExportErrorCode.UNSUPPORTED_FORMAT -> "This device cannot encode with these settings: ${error.message}"
        ExportErrorCode.IO_ERROR -> "A file error stopped the export: ${error.message}"
        else -> "The export failed: ${error.message}"
    }

    override fun onCleared() {
        // Leaving the screen aborts a running export; the engine joins its thread in close().
        val running = synchronized(lock) { handle.also { handle = null } }
        running?.cancel()
        running?.close()
        super.onCleared()
    }

    private companion object {
        const val BITS_PER_MEGABIT = 1_000_000
    }
}
