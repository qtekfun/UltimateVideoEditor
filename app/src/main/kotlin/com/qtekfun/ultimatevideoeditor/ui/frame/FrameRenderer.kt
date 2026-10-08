package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.CubeLut
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.lutKeys
import com.qtekfun.ultimatevideoeditor.domain.stillframe.isUniformPicture
import com.qtekfun.ultimatevideoeditor.domain.toDirectBuffer
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportLut
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.export.ExportSettings
import com.qtekfun.ultimatevideoeditor.engine.export.ExportTitle
import com.qtekfun.ultimatevideoeditor.engine.export.StillFrameTarget
import com.qtekfun.ultimatevideoeditor.engine.export.VideoClipSpec
import com.qtekfun.ultimatevideoeditor.engine.still.StillRasterException
import com.qtekfun.ultimatevideoeditor.engine.still.StillRasterizer
import com.qtekfun.ultimatevideoeditor.engine.still.StillRef
import com.qtekfun.ultimatevideoeditor.engine.title.TitleRasterException
import com.qtekfun.ultimatevideoeditor.engine.title.TitleRasterizer
import com.qtekfun.ultimatevideoeditor.ui.export.ExportIO
import com.qtekfun.ultimatevideoeditor.ui.export.StillPictureProvider
import com.qtekfun.ultimatevideoeditor.ui.export.buildExportPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/** A frame that could not be drawn, with the reason for the user. */
class StillFrameException(message: String) : Exception(message)

/** What to draw: [frame] of [timeline] on a surface of [width] x [height] (the whole picture, letterboxed from the project canvas). */
class FrameRenderJob(
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
    val fps: FrameRate,
    val projectWidth: Int,
    val projectHeight: Int,
    val frame: Long,
    val width: Int,
    val height: Int,
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
    /** Where the plan and the result of each frame are logged (tag UVFrame on a device). */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
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
        // Hard guard: a frame with visible layers must not come out as one flat colour (nothing drawn or read). Never save that.
        val layers = request.videoClips.size
        val uniform = isUniformPicture(still.pixels, still.cropWidth, still.cropHeight)
        log("frame ${job.frame} fps ${job.fps.num}/${job.fps.den} ${still.cropWidth}x${still.cropHeight} layers $layers uniform=$uniform first=${firstPixel(still.pixels)}")
        if (layers > 0 && uniform) {
            throw StillFrameException("The picture came out as one flat colour although the frame has video. Nothing was saved; try again.")
        }
        return RenderedFrame(still.cropWidth, still.cropHeight, still.pixels)
    }

    private fun firstPixel(buffer: ByteBuffer): String {
        val v = buffer.duplicate()
        return "%02x%02x%02x%02x".format(v.get(0), v.get(1), v.get(2), v.get(3))
    }

    private fun prepare(job: FrameRenderJob): ExportRequest {
        val plan = buildFramePlan(job.timeline, job.assets, job.fps, job.projectWidth, job.projectHeight, job.frame)
            ?: throw StillFrameException("There is nothing to save yet. Add a clip to the timeline.")
        log(
            "plan frame ${job.frame} project ${job.projectWidth}x${job.projectHeight} fps ${job.fps.num}/${job.fps.den} covering " +
                plan.clips.joinToString { "[start ${it.startFrame} len ${it.durationFrames} srcIn ${it.sourceInFrame} layer ${it.layer} asset ${it.assetKey} title ${it.titleKey}]" },
        )
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
        val pixels = ByteBuffer.allocateDirect(job.width * job.height * BYTES_PER_PIXEL)
        return ExportRequest(
            // The picture is drawn at the project's frame rate, so the output frame is the project frame.
            settings = ExportSettings(
                width = job.width,
                height = job.height,
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
            still = StillFrameTarget(job.frame, 0, 0, job.width, job.height, pixels),
        )
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val TAG = "UVFrame"
    }
}
