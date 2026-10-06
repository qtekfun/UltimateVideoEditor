package com.ultimatevideo.uveditor.ui.export

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
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
) : MviViewModel<ExportState, ExportIntent, ExportEffect>(ExportState()) {

    private var input: ExportInput? = null
    private var outputUri: String? = null
    private var handle: ExportHandle? = null
    private val lock = Any()
    private var estimator: ExportEstimator? = null

    override fun onIntent(intent: ExportIntent) {
        when (intent) {
            is ExportIntent.Open -> open(intent.input)
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
            ExportIntent.Cancel -> synchronized(lock) { handle?.cancel() }
            ExportIntent.Share -> share()
        }
    }

    private fun open(newInput: ExportInput) {
        if (state.value.isRunning) return
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

    private fun dismiss() {
        if (!state.value.isRunning) reduce { copy(visible = false, phase = ExportPhase.Configuring) }
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
        val totalFrames = request.totalFrames
        synchronized(lock) {
            estimator = ExportEstimator(
                totalFrames = totalFrames,
                movieSeconds = totalFrames * rate.den.toDouble() / rate.num,
                startedAtMs = (state.value.phase as? ExportPhase.Running)?.startedAtMs ?: clock(),
            )
        }
        val started = runner.start(
            request,
            object : ExportListener {
                override fun onProgress(permille: Int) {
                    val now = clock()
                    val estimate = synchronized(lock) { estimator?.onProgress(permille, now) } ?: ExportEstimate()
                    reduce {
                        val running = phase as? ExportPhase.Running
                        if (running != null) copy(phase = running.copy(progressPermille = permille, estimate = estimate)) else this
                    }
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
        val finished = synchronized(lock) {
            estimator = null
            handle.also { handle = null }
        }
        finished?.close()
        val uri = outputUri
        if (error == null && uri != null) {
            reduce { copy(phase = ExportPhase.Done(uri, io.displayName(uri) ?: suggestedFileName(projectName))) }
            return
        }
        // A failed export never leaves a half-written file unmentioned: when the provider refuses to delete it, say so.
        val leftover = if (uri != null && !io.deleteOutput(uri)) " A partly written file could not be removed: ${io.displayName(uri) ?: "the chosen file"}. It is incomplete; delete it." else ""
        outputUri = null
        reduce {
            if (error?.code == ExportErrorCode.CANCELLED) copy(phase = ExportPhase.Configuring) else copy(phase = ExportPhase.Failed(describe(error) + leftover))
        }
    }

    private fun describe(error: ExportException?): String = when (error?.code) {
        null -> "The export failed"
        ExportErrorCode.UNSUPPORTED_FORMAT ->
            "This device cannot encode with these settings: ${error.message}." +
                if (state.value.hdr) " Export as SDR instead." else ""
        ExportErrorCode.IO_ERROR -> "A file error stopped the export: ${error.message}"
        else -> "The export failed: ${error.message}"
    }

    override fun onCleared() {
        // Leaving the screen aborts a running export; the engine joins its thread in close().
        val running = synchronized(lock) { handle.also { handle = null } }
        running?.cancel()
        running?.close()
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
