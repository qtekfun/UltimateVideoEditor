package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.FrameRate
import java.util.Locale

/** Formats a frame as `HH:MM:SS:FF` (non-drop-frame, using the nominal integer frame rate). */
fun formatTimecode(frame: Long, fps: FrameRate): String {
    val nominal = ((fps.num + fps.den / 2) / fps.den).coerceAtLeast(1)
    val frames = frame % nominal
    val totalSeconds = frame / nominal
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
}
