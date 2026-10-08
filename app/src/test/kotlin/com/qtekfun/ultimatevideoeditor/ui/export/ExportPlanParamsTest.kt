package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.BezierHandle
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.Effect
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.Keyframe
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.ParamOps
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.fxAt
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.export.ExportKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanParamsTest {
    private val fps = FrameRate(30, 1)
    private val asset = MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)
    private val contrast = ParamIds.fx("c1", 0)

    private fun animated() = run {
        var t = timeline(track("v1", clip("c", 10, 50, asset = "a")))
        t = TimelineOps.addEffect(t, "c", Effect("c1", EffectType.CONTRAST)).getOrFail()
        t = ParamOps.setKey(t, "c", contrast, ParamKey(0, 0.5)).getOrFail()
        ParamOps.setKey(t, "c", contrast, ParamKey(49, 1.5)).getOrFail()
    }

    @Test
    fun `a clip without animated effects sends no per frame table`() {
        val t = TimelineOps.addEffect(timeline(track("v1", clip("c", 0, 20, asset = "a"))), "c", Effect("c1", EffectType.CONTRAST)).getOrFail()
        val spec = buildExportPlan(t, listOf(asset), fps)!!.videoClips.single()
        assertNull(spec.fxFrames)
    }

    @Test
    fun `animated effect values go over as the effects of every project frame of the clip`() {
        val spec = buildExportPlan(animated(), listOf(asset), fps)!!.videoClips.single()
        val frames = checkNotNull(spec.fxFrames)
        assertEquals(50, frames.size)
        assertEquals(spec.durationFrames.toInt(), frames.size)
        assertEquals(0.5, frames.first().effects.single().values[0], 1e-9)
        assertEquals(1.5, frames.last().effects.single().values[0], 1e-9)
        // The same numbers the preview evaluates at the matching project frame.
        val clip = animated().track("v1")!!.clip("c")!!
        for (i in frames.indices) {
            assertEquals(clip.fxAt(i.toLong()).effects.single().values[0], frames[i].effects.single().values[0], 1e-12)
        }
    }

    @Test
    fun `a Bezier pose segment is baked into linear keys for the native evaluator`() {
        var t = timeline(track("v1", clip("c", 0, 60, asset = "a")))
        t = TimelineOps.setKeyframe(t, "c", Keyframe(0, ClipTransform(positionX = 0.0), Interpolation.BEZIER, out = BezierHandle(0.8, 0.0))).getOrFail()
        t = TimelineOps.setKeyframe(t, "c", Keyframe(30, ClipTransform(positionX = 300.0), Interpolation.LINEAR, inn = BezierHandle(0.2, 0.0))).getOrFail()
        val spec = buildExportPlan(t, listOf(asset), fps)!!.videoClips.single()
        assertTrue(spec.keyframes.none { it.interpolation == Interpolation.BEZIER.code })
        assertEquals(31, spec.keyframes.size) // one per frame over the segment, the last key ends it
        val clip = t.track("v1")!!.clip("c")!!
        for (key: ExportKeyframe in spec.keyframes) {
            assertEquals(clip.transformAt(key.frame).positionX, key.positionX, 1e-9)
        }
    }
}
