package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanKeyframesTest {

    private val fps = FrameRate(30, 1)
    private val asset = MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    @Test
    fun `keyframes reach the export spec with their origin and interpolation code`() {
        val base = timeline(track("v1", clip("A", 40, 100, asset = "a")))
        val animated = TimelineOps.setKeyframe(
            base,
            "A",
            Keyframe(0, ClipTransform(positionX = 10.0, scaleX = 2.0, scaleY = 3.0, rotationDegrees = 45.0, opacity = 0.5), Interpolation.EASE),
        ).getOrFail().let { TimelineOps.setKeyframe(it, "A", Keyframe(60, ClipTransform(positionY = -5.0), Interpolation.HOLD)).getOrFail() }

        val spec = buildExportPlan(animated, listOf(asset), fps)!!.videoClips.single()

        assertEquals(40L, spec.keyframeOriginFrame)
        assertEquals(listOf(0L, 60L), spec.keyframes.map { it.frame })
        assertEquals(listOf(Interpolation.EASE.code, Interpolation.HOLD.code), spec.keyframes.map { it.interpolation })
        val first = spec.keyframes.first()
        assertEquals(listOf(10.0, 0.0, 2.0, 3.0, 45.0, 0.5), listOf(first.positionX, first.positionY, first.scaleX, first.scaleY, first.rotationDegrees, first.opacity))
    }

    @Test
    fun `clips without keyframes export none`() {
        val plan = buildExportPlan(timeline(track("v1", clip("A", 0, 100, asset = "a"))), listOf(asset), fps)!!

        assertTrue(plan.videoClips.single().keyframes.isEmpty())
    }
}
