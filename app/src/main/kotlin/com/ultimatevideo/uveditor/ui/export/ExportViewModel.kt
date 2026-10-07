package com.ultimatevideo.uveditor.ui.export

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.domain.CubeLut
import com.ultimatevideo.uveditor.domain.lutKeys
import com.ultimatevideo.uveditor.domain.toDirectBuffer
import com.ultimatevideo.uveditor.engine.export.ExportLut
import com.ultimatevideo.uveditor.engine.export.ExportPictureProvider
import com.ultimatevideo.uveditor.engine.export.ExportTitle
import com.ultimatevideo.uveditor.engine.export.HdrExportSupport
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.engine.still.StillRasterException
import com.ultimatevideo.uveditor.engine.still.StillRasterizer
import com.ultimatevideo.uveditor.engine.title.TitleRasterException
import com.ultimatevideo.uveditor.engine.title.TitleRasterizer
import com.ultimatevideo.uveditor.mvi.MviViewModel
import com.ultimatevideo.uveditor.ui.hub.aspectLabelOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drives the export dialog: settings, the document picker round trip, progress and cancellation.
 * The heavy lifting is on the native export thread; descriptors are opened off the main thread.
 */
class ExportViewModel(
    private val io: ExportIO,
    private val runner: ExportRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val titleRasterizer: TitleRasterizer = TitleRasterizer { _, _, _ ->
        throw TitleRasterException("This build cannot draw titles")
    },
    private val hdrSupport: HdrExportSupport = HdrExportSupport.NONE,
    private val stillRasterizer: StillRasterizer = StillRasterizer { _, _, _ ->
        throw StillRasterException("This build cannot draw pictures")
    },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Reads a LUT of the library by key; null when it is missing (its effect then leaves the clip ungraded). */
    private val lutLoader: (Int) -> CubeLut? = { null },
    /**
     * Runs the export and outlives this view model (the app passes the process-wide one, see [ExportCenter]). The default
     * is a private executor on [runner], which is what the unit tests use.
     */
    private val executor: ExportExecutor = ExportExecutor(io, runner, CoroutineScope(SupervisorJob() + ioDispatcher), ioDispatcher, clock),
    /** The project this dialog belongs to: it only mirrors an export of this project and refuses to start while another one runs. */
    private val projectId: String = "",
) : MviViewModel<ExportState, ExportIntent, ExportEffect>(ExportState()) {

    private var input: ExportInput? = null

    /** The last outcome of this project's export that the dialog shows; the project list clearing it (Idle) closes the dialog. */
    private var mirrored: ExportJobState? = null

    init {
        // The dialog is only a view of the executor: a new view model (after the editor was left and entered again, or
        // the activity was recreated) picks up an export that is already running or has just finished.
        viewModelScope.launch { executor.state.collect(::onJobState) }
    }

    override fun onIntent(intent: ExportIntent) {
        when (intent) {
            is ExportIntent.Open -> open(intent.input)
            ExportIntent.ShowProgress -> showProgress()
            ExportIntent.Dismiss -> dismiss()
            is ExportIntent.SelectResolution -> editSettings { copy(resolution = intent.option) }
            is ExportIntent.SelectFrameRate -> editSettings { copy(frameRate = intent.rate) }
            is ExportIntent.SelectCodec -> editSettings { copy(codec = intent.codec, hdr = hdr && intent.codec == ExportCodec.HEVC) }
            is ExportIntent.SelectHdr -> selectHdr(intent.hdr)
            is ExportIntent.SelectBitrate ->
                if (!state.value.isRunning) reduce { copy(bitrateMbps = intent.mbps, preset = null, phase = ExportPhase.Configuring) }
            is ExportIntent.SelectPreset -> selectPreset(intent.preset)
            ExportIntent.ChooseLocation -> chooseLocation()
            is ExportIntent.LocationChosen -> intent.uri?.let(::start)
            ExportIntent.Cancel -> executor.cancel()
            ExportIntent.Share -> share()
        }
    }

    private fun open(newInput: ExportInput) {
        val job = executor.state.value
        when (val availability = exportAvailability(job, projectId)) {
            is ExportAvailability.BlockedBy -> {
                emit(ExportEffect.Message(availability.message))
                return
            }
            ExportAvailability.RunningHere -> {
                // The button of the exporting project shows its progress instead of new settings.
                reduce { copy(visible = true, hiddenWhileRunning = false) }
                return
            }
            ExportAvailability.Available -> Unit
        }
        mirrored = null
        executor.acknowledge(projectId)
        input = newInput
        val resolutions = resolutionOptions(newInput.projectWidth, newInput.projectHeight)
        val rates = frameRateOptions(newInput.fps)
        val resolution = resolutions.first()
        // An HDR project exports as HDR (HEVC Main10 HLG) when the device can; otherwise as SDR, HLG clips tone-mapped.
        val projectHdr = newInput.colorSpace.isHdr
        val hdrAvailable = projectHdr && hdrSupport.supportsHlgExport(resolution.width, resolution.height, rates.first().num, rates.first().den)
        val codec = if (hdrAvailable) ExportCodec.HEVC else state.value.codec
        reduce {
            copy(
                visible = true,
                codec = codec,
                hdrAvailable = hdrAvailable,
                hdr = hdrAvailable,
                hdrUnsupportedNotice = projectHdr && !hdrAvailable,
                projectName = newInput.projectName,
                resolutions = resolutions,
                resolution = resolution,
                frameRates = rates,
                frameRate = rates.first(),
                bitrateMbps = suggestedBitrateMbps(resolution.width, resolution.height, rates.first(), codec),
                phase = ExportPhase.Configuring,
                preset = null,
                projectAspect = aspectLabelOf(newInput.projectWidth, newInput.projectHeight),
            )
        }
    }

    /** Fills the settings for an upload destination; changing any of them by hand afterwards drops the preset. */
    private fun selectPreset(preset: ExportPreset) {
        val current = state.value
        if (current.isRunning || current.resolutions.isEmpty() || current.frameRates.isEmpty()) return
        val choice = resolvePreset(preset, current.resolutions, current.frameRates)
        reduce {
            copy(
                resolution = choice.resolution,
                frameRate = choice.frameRate,
                codec = choice.codec,
                hdr = false, // upload presets are SDR recommendations
                bitrateMbps = choice.bitrateMbps,
                preset = preset,
                phase = ExportPhase.Configuring,
            )
        }
    }

    /** Closes the dialog. A running export goes on: only the dialog is hidden (the notification and the project list still show it). */
    private fun dismiss() {
        if (state.value.isRunning) {
            reduce { copy(visible = false, hiddenWhileRunning = true) }
            return
        }
        mirrored = null
        executor.acknowledge(projectId)
        reduce { copy(visible = false, phase = ExportPhase.Configuring) }
    }

    /** Opens the dialog on this project's export, whatever its state; nothing to show (or another project's export) changes nothing. */
    private fun showProgress() {
        val job = executor.state.value
        if (job.projectId != projectId || job is ExportJobState.Cancelled) return
        reduce { copy(hiddenWhileRunning = false) }
        onJobState(job)
    }

    /**
     * Mirrors the executor into the dialog, for this project's export only. [ExportJobState.Idle] changes nothing, except
     * when the project list dismissed a result this dialog was showing: then it closes too (the dialog may otherwise hold
     * its own failure, such as missing media).
     */
    private fun onJobState(job: ExportJobState) {
        if (job.projectId != projectId && job != ExportJobState.Idle) {
            reduce { copy(blockedBy = (job as? ExportJobState.Running)?.projectName) }
            return
        }
        when (job) {
            ExportJobState.Idle -> {
                val shown = mirrored
                mirrored = null
                reduce { copy(blockedBy = null) }
                if (shown is ExportJobState.Done || shown is ExportJobState.Failed || shown is ExportJobState.Running) {
                    reduce { copy(visible = false, phase = ExportPhase.Configuring, hiddenWhileRunning = false) }
                }
            }
            is ExportJobState.Running -> {
                mirrored = job
                reduce {
                    copy(
                        visible = !hiddenWhileRunning,
                        blockedBy = null,
                        projectName = if (visible) projectName else job.projectName,
                        phase = ExportPhase.Running(job.progressPermille, job.startedAtMs, job.estimate),
                    )
                }
            }
            is ExportJobState.Done -> {
                mirrored = job
                reduce { copy(visible = true, hiddenWhileRunning = false, phase = ExportPhase.Done(job.uri, job.fileName, job.note)) }
            }
            is ExportJobState.Failed -> {
                mirrored = job
                reduce {
                    copy(visible = true, hiddenWhileRunning = false, phase = ExportPhase.Failed(describeExportFailure(job.error, hdr) + job.leftoverNote))
                }
            }
            is ExportJobState.Cancelled -> {
                // Back to the settings; a view model that never opened the dialog has none to go back to.
                mirrored = null
                reduce { copy(visible = resolutions.isNotEmpty(), hiddenWhileRunning = false, phase = ExportPhase.Configuring) }
                executor.acknowledge(projectId)
            }
        }
    }

    /** Changing the size, rate or codec re-suggests a bitrate for the new settings. */
    private fun editSettings(change: ExportState.() -> ExportState) {
        if (state.value.isRunning) return
        reduce {
            val changed = change().copy(preset = null)
            val resolution = changed.resolution
            val rate = changed.frameRate
            // HDR depends on the size and rate: re-ask the encoder whenever they change.
            val projectHdr = input?.colorSpace?.isHdr == true
            val available = projectHdr && resolution != null && rate != null &&
                hdrSupport.supportsHlgExport(resolution.width, resolution.height, rate.num, rate.den)
            val next = changed.copy(hdrAvailable = available, hdr = changed.hdr && available, hdrUnsupportedNotice = projectHdr && !available)
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

    private fun selectHdr(enabled: Boolean) {
        if (state.value.isRunning || (enabled && !state.value.hdrAvailable)) return
        editSettings { copy(hdr = enabled, codec = if (enabled) ExportCodec.HEVC else codec) }
    }

    private fun chooseLocation() {
        val current = state.value
        if (current.isRunning || current.resolution == null || current.frameRate == null) return
        // Refuse before asking where to save: a movie with holes where the clips should be is not what anyone wants.
        missingMediaProblem()?.let { reason ->
            reduce { copy(phase = ExportPhase.Failed(reason)) }
            return
        }
        emit(ExportEffect.LaunchCreateDocument(suggestedFileName(current.projectName)))
    }

    /** Names the clips that need unreadable media, or null when every file can be opened. */
    private fun missingMediaProblem(): String? {
        val source = input ?: return null
        val clips = MissingMedia.clipsUsing(source.timeline, source.missingAssetIds, source.fps)
        if (clips.isEmpty()) return null
        val shown = clips.take(MAX_NAMED_CLIPS).joinToString(", ") { it.where }
        val more = if (clips.size > MAX_NAMED_CLIPS) " and ${clips.size - MAX_NAMED_CLIPS} more" else ""
        return "Cannot export: the media for ${clips.size} clip(s) is missing ($shown$more). Relink it in the editor first."
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
        missingMediaProblem()?.let { reason ->
            reduce { copy(phase = ExportPhase.Failed(reason)) }
            return
        }
        if (current.hdr && !hdrSupport.supportsHlgExport(resolution.width, resolution.height, rate.num, rate.den)) {
            reduce { copy(phase = ExportPhase.Failed("This device cannot export HDR at ${resolution.label}. Choose SDR or a lower resolution.")) }
            return
        }
        reduce { copy(phase = ExportPhase.Running(0, startedAtMs = clock())) }
        val job = ExportJob(projectId, current.projectName, uri) { buildRequest(source, uri, resolution, rate, current) }
        if (!executor.start(job)) reduce { copy(phase = ExportPhase.Failed("Another export is already running.")) }
    }

    /** Opens every descriptor and builds what the engine needs. Runs on the executor's IO dispatcher. */
    private fun buildRequest(
        source: ExportInput,
        outputUri: String,
        resolution: ResolutionOption,
        rate: FrameRate,
        current: ExportState,
    ): ExportRequest {
        val plan = buildExportPlan(source.timeline, source.assets, source.fps, source.projectWidth, source.projectHeight)
            ?: throw ExportException(ExportErrorCode.INVALID_ARGUMENT, "There is nothing to export yet. Add a clip to the timeline.")
        val titleImages = plan.titles.map { (key, content) ->
            val bitmap = try {
                titleRasterizer.rasterize(content, source.projectWidth, source.projectHeight)
            } catch (e: TitleRasterException) {
                throw ExportException(ExportErrorCode.INVALID_ARGUMENT, "A title could not be drawn: ${e.message}")
            }
            ExportTitle(key, bitmap.width, bitmap.height, bitmap.pixels)
        }
        // Photos, stickers and frames of animations reach the engine as pictures too, drawn by the same path as in
        // the preview, but one at a time as the render needs them: an animation of hundreds of frames is never held in
        // memory all at once. A picture that cannot be read at all is found now, not halfway through the export.
        val stillByKey = plan.stills
        for (still in stillByKey.values.distinctBy { it.kind to it.id }) {
            try {
                stillRasterizer.rasterize(still.copy(frame = 0), source.projectWidth, source.projectHeight)
            } catch (e: StillRasterException) {
                throw ExportException(ExportErrorCode.INVALID_ARGUMENT, "A picture could not be drawn: ${e.message}")
            }
        }
        val pictures = StillPictureProvider(stillByKey, stillRasterizer, source.projectWidth, source.projectHeight)
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
            hdr = current.hdr,
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
            titles = titleImages,
            pictureProvider = pictures.takeIf { stillByKey.isNotEmpty() },
            luts = source.timeline.lutKeys().mapNotNull { key -> lutLoader(key)?.let { ExportLut(key, it.size, it.toDirectBuffer()) } },
        )
        return request
    }

    override fun onCleared() {
        // A running export is not ours to stop: it goes on in the executor, kept alive by the foreground service. A
        // finished one that nobody looked at is dropped so that the next editor does not open on a stale result.
        executor.acknowledge(projectId)
    }

    private companion object {
        const val MAX_NAMED_CLIPS = 3
        const val BITS_PER_MEGABIT = 1_000_000
    }
}

/**
 * Makes the pictures of an export on request: [stills] maps the keys the plan put in the clips to what they show, and
 * [rasterizer] decodes one (animations replay forward, one frame per step). Called from the native render thread.
 */
internal class StillPictureProvider(
    private val stills: Map<Int, StillRef>,
    private val rasterizer: StillRasterizer,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
) : ExportPictureProvider {
    @Volatile
    override var lastError: String? = null
        private set

    override fun load(key: Int): ExportTitle? {
        val still = stills[key] ?: run {
            lastError = "an unknown picture was requested"
            return null
        }
        return try {
            val bitmap = rasterizer.rasterize(still, canvasWidth, canvasHeight)
            ExportTitle(key, bitmap.width, bitmap.height, bitmap.pixels, bitmap.displayWidth, bitmap.displayHeight)
        } catch (e: StillRasterException) {
            lastError = e.message
            null
        }
    }
}
