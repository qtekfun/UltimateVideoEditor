package com.ultimatevideo.uveditor.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Whether the device can encode an HLG (HEVC Main10, BT.2020) movie of a given size. HDR export is
 * only offered when this says yes; otherwise the project exports as SDR, with HLG clips tone-mapped.
 * (The native exporter additionally needs a ten-bit encoder surface and fails with
 * [ExportErrorCode.UNSUPPORTED_FORMAT] if the GPU cannot provide one.)
 */
fun interface HdrExportSupport {
    fun supportsHlgExport(width: Int, height: Int, fpsNum: Int, fpsDen: Int): Boolean

    companion object {
        /** For builds and tests without an encoder to ask. */
        val NONE = HdrExportSupport { _, _, _, _ -> false }
    }
}

/** Asks the platform codec list. */
class MediaCodecHdrExportSupport : HdrExportSupport {
    override fun supportsHlgExport(width: Int, height: Int, fpsNum: Int, fpsDen: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_FRAME_RATE, (fpsNum.toDouble() / fpsDen).roundToInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_BIT_RATE, HINT_BITRATE)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findEncoderForFormat(format) ?: return false
        val info = list.codecInfos.firstOrNull { it.name == name } ?: return false
        val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        if (capabilities.profileLevels.none { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }) return false
        return canConfigure(name, format)
    }

    /**
     * The capability list is not enough: the Huawei MatePad's hisi HEVC encoder lists Main10 but its `configureCodec`
     * fails (-38) at any size, which used to leave an HDR option that could only end in "the encoder rejected the settings".
     * Configuring the encoder once, without starting it, tells the truth.
     */
    private fun canConfigure(codecName: String, format: MediaFormat): Boolean {
        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (e: IOException) {
            return false
        }
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            true
        } catch (e: MediaCodec.CodecException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        } finally {
            codec.release()
        }
    }

    private companion object {
        const val HINT_BITRATE = 20_000_000
    }
}
