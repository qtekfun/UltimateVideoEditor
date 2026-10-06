package com.ultimatevideo.uveditor.ui.frame

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.CubeLut
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.lutKeys
import com.ultimatevideo.uveditor.domain.stillframe.FrameRenderPlan
import com.ultimatevideo.uveditor.domain.stillframe.FrameSource
import com.ultimatevideo.uveditor.domain.stillframe.timelineOfClip
import com.ultimatevideo.uveditor.domain.toDirectBuffer
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportLut
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.engine.export.ExportTitle
import com.ultimatevideo.uveditor.engine.export.StillFrameTarget
import com.ultimatevideo.uveditor.engine.export.VideoClipSpec
import com.ultimatevideo.uveditor.engine.still.StillRasterException
import com.ultimatevideo.uveditor.engine.still.StillRasterizer
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.engine.title.TitleRasterException
import com.ultimatevideo.uveditor.engine.title.TitleRasterizer
import com.ultimatevideo.uveditor.ui.export.ExportIO
import com.ultimatevideo.uveditor.ui.export.StillPictureProvider
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/** A frame that could not be drawn, with the reason for the user. */
class StillFrameException(message: String) : Exception(message)

/** What to draw: [frame] of [timeline] (the whole project or one clip alone), as [plan] says. */
class FrameRenderJob(
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
    val fps: FrameRate,
    val projectWidth: Int,
    val projectHeight: Int,
    val frame: Long,
    val plan: FrameRenderPlan,
    val missingAssetIds: Set<String>,
)

/** The drawn picture: RGBA8, top row first, [width] x [height], in a direct buffer. */
class RenderedFrame(val width: Int, val height: Int, val rgba: ByteBuffer)

fun interface FrameRenderer {
    /** Draws the frame. Suspends while the engine works and stops it when cancelled. @throws StillFrameException @throws ExportException */
    suspend fun render(job: FrameRenderJob): RenderedFrame
}

/** The part of the export plan that the frame needs: the clips that cover it and only the media, titles and pictures they use. */
internal class FramePlan(
    val clips: List<VideoClipSpec>,
    /** Asset id to native key, for the clips in [clips] only. */
    val assetKeys: Map<String, Long>,
    val titles: Map<Int, TitleContent>,
    val stills: Map<Int, StillRef>,
)

/**
 * Plans the frame through [buildExportPlan], the function the exporter uses (so titles, transitions, retiming, effects and
 * keyframes are the exporter's), then keeps what covers [frame]. Null when the project has nothing to draw at all.
 */
internal fun buildFramePlan(timeline: Timeline, assets: List<MediaAssetDto>, fps: FrameRate, width: Int, height: Int, frame: Long): FramePlan? {
    val plan = buildExportPlan(timeline, assets, fps, width, height) ?: return null
    val covering = plan.videoClips.filter { frame >= it.startFrame && frame < it.startFrame + it.durationFrames }
    val keys = covering.filter { it.titleKey == 0 }.map { it.assetKey }.toSet()
    val pictures = covering.map { it.titleKey }.filter { it != 0 }.toSet()
    return FramePlan(
        clips = covering,
        assetKeys = plan.assetKeys.filterValues { it in keys },
        titles = plan.titles.filterKeys { it in pictures },
        stills = plan.stills.filterKeys { it in pictures },
    )
}

/**
 * Draws one frame with the exporter's own engine in its save-frame mode: the same clip list, the same decoders with their
 * exact seeks, the same compositor, drawn once into an offscreen target and read back (SPECS 5.35). Runs on the engine's
 * own thread and context, so the preview is not touched; the caller refuses while an export runs.
 */
class NativeFrameRenderer(
    private val io: ExportIO,
    private val runner: ExportRunner,
    private val titleRasterizer: TitleRasterizer,
    private val stillRasterizer: StillRasterizer,
    private val lutLoader: (Int) -> CubeLut? = { null },
) : FrameRenderer {

    override suspend fun render(job: FrameRenderJob): RenderedFrame {
        val request = prepare(job)
        val done = CompletableDeferred<ExportException?>()
        val handle = runner.start(
            request,
            object : ExportListener {
                override fun onProgress(permille: Int) = Unit

                override fun onFinished(error: ExportException?) {
                    done.complete(error)
                }
            },
        )
        try {
            val error = done.await()
            if (error != null) throw error
        } finally {
            // Joins the engine thread; it must have stopped before the buffer it writes to is read or dropped.
            withContext(NonCancellable) {
                handle.cancel()
                handle.close()
            }
        }
        val still = checkNotNull(request.still)
        still.pixels.rewind()
        return RenderedFrame(still.cropWidth, still.cropHeight, still.pixels)
    }

    private fun prepare(job: FrameRenderJob): ExportRequest {
        val plan = buildFramePlan(job.timeline, job.assets, job.fps, job.projectWidth, job.projectHeight, job.frame)
            ?: throw StillFrameException("There is nothing to save yet. Add a clip to the timeline.")
        val missing = plan.assetKeys.keys.filter { it in job.missingAssetIds }
        if (missing.isNotEmpty()) {
            throw StillFrameException("The media of a clip at this frame is missing. Relink it in the editor first.")
        }
        val titleImages = plan.titles.map { (key, content) ->
            val bitmap = try {
                titleRasterizer.rasterize(content, job.projectWidth, job.projectHeight)
            } catch (e: TitleRasterException) {
                throw StillFrameException("A title could not be drawn: ${e.message}")
            }
            ExportTitle(key, bitmap.width, bitmap.height, bitmap.pixels)
        }
        for (still in plan.stills.values.distinctBy { it.kind to it.id }) {
            try {
                stillRasterizer.rasterize(still.copy(frame = 0), job.projectWidth, job.projectHeight)
            } catch (e: StillRasterException) {
                throw StillFrameException("A picture could not be drawn: ${e.message}")
            }
        }
        val pictures = StillPictureProvider(plan.stills, stillRasterizer, job.projectWidth, job.projectHeight)
        val uriByAsset = job.assets.associate { it.id to it.uri }
        val opened = LinkedHashMap<Long, Int>()
        try {
            for ((assetId, key) in plan.assetKeys) {
                val uri = uriByAsset[assetId] ?: throw IOException("A clip refers to media that is no longer in the project")
                opened[key] = io.openAsset(uri)
            }
        } catch (e: IOException) {
            opened.values.forEach(io::close)
            throw ExportException(ExportErrorCode.IO_ERROR, "Cannot open a media file: ${e.message}")
        }
        val out = job.plan
        val pixels = ByteBuffer.allocateDirect(out.outWidth * out.outHeight * BYTES_PER_PIXEL)
        return ExportRequest(
            // The picture is drawn at the project's frame rate, so the output frame is the project frame.
            settings = ExportSettings(
                width = out.renderWidth,
                height = out.renderHeight,
                fpsNum = job.fps.num,
                fpsDen = job.fps.den,
                codec = ExportCodec.H264,
                videoBitrate = 1,
                picture = true,
            ),
            projectFpsNum = job.fps.num,
            projectFpsDen = job.fps.den,
            canvasWidth = job.projectWidth,
            canvasHeight = job.projectHeight,
            totalFrames = 1,
            assetFds = opened,
            videoClips = plan.clips,
            audioSnapshot = null,
            outputFd = -1,
            titles = titleImages,
            pictureProvider = pictures.takeIf { plan.stills.isNotEmpty() },
            luts = job.timeline.lutKeys().mapNotNull { key -> lutLoader(key)?.let { ExportLut(key, it.size, it.toDirectBuffer()) } },
            still = StillFrameTarget(job.frame, out.cropX, out.cropY, out.outWidth, out.outHeight, pixels),
        )
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

/** The timeline a [FrameRenderJob] draws from: the whole project, or the selected clip alone. */
internal fun frameTimeline(source: FrameSource, timeline: Timeline, selectedClipId: String?): Timeline? = when (source) {
    FrameSource.WHOLE_PICTURE -> timeline
    FrameSource.SELECTED_CLIP -> selectedClipId?.let { timelineOfClip(timeline, it) }
}
