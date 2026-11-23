package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.HdrBrightness
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.project.ProjectPersistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The HDR brightness setting of the project and the measuring of how bright the diffuse content of each HDR file is (the "HDR level"),
 * so it can be dimmed to reference white. Measurements are cached per file and colour space and not repeated within a session.
 */
internal class HdrLevelHandler(
    host: EditorHost,
    private val hdrAnalyzer: HdrLevelAnalyzer,
    private val hdrLevelCache: HdrLevelCache,
    private val hdrDispatcher: CoroutineDispatcher,
    private val persistence: ProjectPersistence,
) : EditorHost by host {

    private var hdrJob: Job? = null

    /** Files whose level could not be measured this session (the picture is then shown as recorded); not asked again. */
    private val hdrUnmeasured = HashSet<String>()

    fun changeHdrBrightness(brightness: HdrBrightness) {
        if (brightness == state.value.hdr.brightness) return
        persistence.baseProject = persistence.baseProject?.let { it.copy(settings = it.settings.copy(hdrBrightness = brightness.id)) }
        reduce { copy(hdr = hdr.copy(brightness = brightness)) }
        scheduleSave()
        refreshHdrLevels()
    }

    /**
     * Makes sure every HLG or PQ clip that would be dimmed has its file measured: from the cache when it was seen before, else by
     * reading a dozen frames of the original in the background, one file at a time. Until a file is measured its clips are shown as
     * recorded; when it is, [EditorState.hdr] changes and the preview redraws.
     */
    fun refreshHdrLevels() {
        if (hdrJob?.isActive == true) return  // its loop looks again after each file
        if (nextHdrAnalysis() == null) return
        hdrJob = scope.launch {
            while (true) {
                val (asset, space) = nextHdrAnalysis() ?: break
                val key = HdrLevelCache.keyOf(asset, space)
                // The cache is a file and the analysis a decode: neither runs on the main thread.
                val level = withContext(hdrDispatcher) { hdrLevelCache.get(key) ?: hdrAnalyzer.diffuseLevel(asset, space)?.also { hdrLevelCache.put(key, it) } }
                if (level == null) {
                    hdrUnmeasured += key
                } else {
                    reduce { copy(hdr = hdr.copy(levels = hdr.levels + (key to level))) }
                }
            }
        }
    }

    /** The first (file, colour space) a clip needs measured and has not got, or null. */
    private fun nextHdrAnalysis(): Pair<MediaAssetDto, SourceColorSpace>? {
        val current = state.value
        val assets = current.playableAssets.associateBy { it.id }
        if (assets.isEmpty()) return null
        for (track in history.timeline.tracks) {
            if (track.type != TrackType.VIDEO) continue
            for (clip in track.clips) {
                if (!clip.hasMedia || !current.hdr.wantsAnalysis(clip.hdrAutoLevel)) continue
                val asset = clip.assetId?.let(assets::get)?.takeIf { it.hasVideo } ?: continue
                val space = clip.colorOverride ?: SourceColorSpace.fromId(asset.colorSpace)
                if (space == SourceColorSpace.SDR) continue
                val key = HdrLevelCache.keyOf(asset, space)
                if (key !in current.hdr.levels && key !in hdrUnmeasured) return asset to space
            }
        }
        return null
    }
}
