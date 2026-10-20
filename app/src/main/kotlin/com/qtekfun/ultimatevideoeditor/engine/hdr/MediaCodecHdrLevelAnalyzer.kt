package com.qtekfun.ultimatevideoeditor.engine.hdr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.engine.verify.MediaCodecFrameSource
import com.qtekfun.ultimatevideoeditor.engine.verify.VerifyExpectation
import com.qtekfun.ultimatevideoeditor.ui.editor.HdrLevelAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Finds the diffuse level of an HDR file by decoding a dozen frames of the original with the platform decoder (the code that
 * checks exported files, see [MediaCodecFrameSource]) and measuring them; never the proxy. Reads only the file the user
 * imported, on the device. A file that cannot be read or decoded has no level and is shown as recorded.
 */
class MediaCodecHdrLevelAnalyzer(private val context: Context) : HdrLevelAnalyzer {
    override suspend fun diffuseLevel(asset: MediaAssetDto, space: SourceColorSpace): Double? = withContext(Dispatchers.IO) {
        if (space == SourceColorSpace.SDR || !asset.hasVideo || asset.durationFrames <= 0) return@withContext null
        try {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r") ?: return@withContext null
            val expectation = VerifyExpectation(asset.durationFrames, asset.nativeFpsNum, asset.nativeFpsDen, hasAudio = false, hdr = true)
            val frames = HdrLevelMeasure.sampleFrames(asset.durationFrames).toSet()
            val job = currentCoroutineContext()
            MediaCodecFrameSource.open(descriptor.fileDescriptor, expectation, frames, descriptor).use { source ->
                HdrLevelMeasure.diffuseLevel(source, asset.durationFrames, space) { !job.isActive }
            }
        } catch (e: IOException) {
            Log.w(TAG, "HDR level of ${asset.id} not measured: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "HDR level of ${asset.id} not measured, no permission: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "HDR level of ${asset.id} not measured: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "UVHdrLevel"
    }
}
