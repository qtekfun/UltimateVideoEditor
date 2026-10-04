package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.AnimationTiming

/**
 * The animation of an animated GIF or WebP asset, or null for a photo, a video and a project file whose delays
 * are missing or invalid (it then shows its first frame, like any photo).
 */
fun MediaAssetDto.animationTiming(): AnimationTiming? {
    if (!isImage) return null
    val delays = animationDelaysMs ?: return null
    if (delays.size < 2 || delays.any { it !in 1..AnimationTiming.MAX_DELAY_MS }) return null
    return AnimationTiming(delays, (animationPlays ?: AnimationTiming.INFINITE).coerceIn(0, AnimationTiming.MAX_PLAYS))
}
