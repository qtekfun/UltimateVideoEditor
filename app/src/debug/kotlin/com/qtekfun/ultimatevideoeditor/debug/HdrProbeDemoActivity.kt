package com.qtekfun.ultimatevideoeditor.debug

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import java.io.File

/**
 * Debug-only: writes `files/hdr-probe.txt` with every HEVC encoder, its profiles, and the outcome of trial-configuring HLG
 * (Main10) formats at several sizes, rates and key sets. Used to see why the export dialog does not offer HDR on a device.
 *   adb shell am start -n com.qtekfun.ultimatevideoeditor/.debug.HdrProbeDemoActivity
 */
class HdrProbeDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = StringBuilder()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = list.codecInfos.filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
        for (info in encoders) {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            out.appendLine("encoder ${info.name} hw=${info.isHardwareAccelerated} sw=${info.isSoftwareOnly} vendor=${info.isVendor}")
            out.appendLine("  profiles/levels: " + caps.profileLevels.joinToString { "${it.profile}/${it.level}" })
            val v = caps.videoCapabilities ?: continue
            out.appendLine("  sizes w=${v.supportedWidths} h=${v.supportedHeights} bitrate=${v.bitrateRange} fps=${v.supportedFrameRates}")
            out.appendLine("  4K60 supported=${v.areSizeAndRateSupported(3840, 2160, 60.0)} 4K30=${v.areSizeAndRateSupported(3840, 2160, 30.0)}")
            out.appendLine("  colorFormats: " + caps.colorFormats.joinToString())
            for ((w, h, fps) in listOf(Triple(1920, 1080, 30), Triple(3840, 2160, 30), Triple(3840, 2160, 60))) {
                for (variant in VARIANTS) {
                    val f = format(w, h, fps, variant)
                    val found = list.findEncoderForFormat(f)
                    out.appendLine("  ${w}x$h@$fps $variant: findEncoderForFormat=$found isFormatSupported=${caps.isFormatSupported(f)} configure=${trial(info.name, f)}")
                }
            }
        }
        val support = com.qtekfun.ultimatevideoeditor.engine.export.MediaCodecHdrExportSupport()
        for ((w, h, fps) in listOf(Triple(1920, 1080, 30), Triple(3840, 2160, 30), Triple(3840, 2160, 60))) {
            out.appendLine("MediaCodecHdrExportSupport ${w}x$h@$fps = ${support.supportsHlgExport(w, h, fps, 1)}")
        }
        File(getExternalFilesDir(null),"hdr-probe.txt").writeText(out.toString())
        finish()
    }

    private val VARIANTS = listOf("probe", "probe+gop+vbr", "no-color-keys", "no-profile", "probe+level51")

    private fun format(w: Int, h: Int, fps: Int, variant: String): MediaFormat {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
        if (variant != "no-profile") f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        if (variant != "no-color-keys") {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        if (variant == "probe+gop+vbr") {
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        if (variant == "probe+level51") f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
        return f
    }

    private fun trial(name: String, format: MediaFormat): String {
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: java.io.IOException) {
            return "create failed: $e"
        }
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            "ok"
        } catch (e: Exception) {
            "FAILED ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            codec.release()
        }
    }
}
