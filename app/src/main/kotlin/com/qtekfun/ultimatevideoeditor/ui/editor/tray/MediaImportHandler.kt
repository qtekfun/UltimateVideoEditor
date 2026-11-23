package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.folders.DocumentKey
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.AnimationTiming
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.PlacementHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.moveAsset
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import kotlinx.coroutines.launch

/**
 * Importing files: probing a file and registering it in the library, importing straight onto the timeline or only into the tray, and
 * reordering the tray.
 */
internal class MediaImportHandler(
    host: EditorHost,
    private val importer: MediaImporter,
    private val placement: PlacementHandler,
) : EditorHost by host {

    fun importMedia(uris: List<String>, atEnd: Boolean = false) {
        if (uris.isEmpty()) return
        scope.launch {
            reduce { copy(isImporting = true) }
            var cursor = if (atEnd) history.timeline.tracks.maxOfOrNull { it.end } ?: FrameIndex.ZERO else state.value.playhead
            for (uri in uris) {
                try {
                    val asset = assetFor(uri)
                    cursor = placement.place(asset, cursor) ?: cursor
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_could_not_import_the_file))))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    fun importToTray(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch {
            reduce { copy(isImporting = true) }
            for (uri in uris) {
                try {
                    assetFor(uri)
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_could_not_import_the_file))))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    /** The library order is the order of `mediaLibrary` in the project file, so it saves with the project. */
    fun reorderAsset(assetId: String, toIndex: Int) {
        val moved = moveAsset(state.value.assets, assetId, toIndex)
        if (moved === state.value.assets) return
        reduce { copy(assets = moved) }
        scheduleSave()
    }

    /** Returns the library entry for [uri], importing and registering it first if it is new. */
    suspend fun assetFor(uri: String): MediaAssetDto {
        // The same file reached another way (the picker, or a linked folder) is the same asset: compare what the provider calls it.
        val key = DocumentKey.of(uri)
        state.value.assets.firstOrNull { it.uri == uri || (key != null && DocumentKey.of(it.uri) == key) }?.let { return it }
        val probed = importer.import(uri)
        val project = state.value.fps
        if (probed.isImage) {
            // A picture has no length of its own; the asset's "duration" is the default length of a clip of it.
            val image = MediaAssetDto(
                id = "asset-${idGenerator()}",
                uri = uri,
                // An animated picture starts at one pass of its animation; a photo at the default still length.
                durationFrames = project.microsToFrames(
                    probed.animationDelaysMs?.let { AnimationTiming(it).periodMicros } ?: PHOTO_DEFAULT_MICROS,
                ).coerceAtLeast(1),
                nativeFpsNum = project.num,
                nativeFpsDen = project.den,
                colorSpace = probed.colorSpace,
                hasVideo = false,
                hasAudio = false,
                isImage = true,
                displayName = probed.displayName,
                animationDelaysMs = probed.animationDelaysMs,
                animationPlays = probed.animationPlays.takeIf { probed.animationDelaysMs != null },
            )
            reduce { copy(assets = assets + image) }
            scheduleSave()
            return image
        }
        // Audio-only files have no native frame rate; use the project's.
        val (fpsNum, fpsDen) = if (probed.hasVideo) probed.fpsNum to probed.fpsDen else project.num to project.den
        val durationFrames = FrameRate(fpsNum, fpsDen).microsToFrames(probed.durationMicros)
        if (durationFrames <= 0) throw MediaImportException("too short to place", userText = UiText.res(R.string.mi_too_short))
        val asset = MediaAssetDto(
            id = "asset-${idGenerator()}",
            uri = uri,
            durationFrames = durationFrames,
            nativeFpsNum = fpsNum,
            nativeFpsDen = fpsDen,
            colorSpace = probed.colorSpace,
            hasVideo = probed.hasVideo,
            hasAudio = probed.hasAudio,
            displayName = probed.displayName,
            videoWidth = probed.videoWidth,
            videoHeight = probed.videoHeight,
            videoBitrate = probed.videoBitrate,
            videoCodec = probed.videoCodec,
            tenBit = probed.tenBit,
        )
        reduce { copy(assets = assets + asset) }
        scheduleSave()
        return asset
    }
}
