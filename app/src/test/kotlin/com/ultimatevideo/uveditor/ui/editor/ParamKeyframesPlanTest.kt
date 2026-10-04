package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.ParamOps
import com.ultimatevideo.uveditor.domain.RenderClip
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.paramValueAt
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.audio.AutoParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preview and the mixer read the same keyframed parameters; they must agree at every frame. */
class ParamKeyframesPlanTest {
    private val fps = FrameRate(30, 1)
    private val keys = KeyRegistry()
    private val assetKeys = KeyRegistry()
    private val contrast = ParamIds.fx("c1", 0)

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    private fun snapshot(tl: Timeline, vararg assets: MediaAssetDto) =
        audioSnapshotOf(tl, assets.toList(), fps, keys::keyFor, assetKeys::keyFor)

    private fun videoScene(): Timeline {
        var t = timeline(track("v1", clip("c", 20, 60, asset = "a")))
        t = TimelineOps.addEffect(t, "c", Effect("c1", EffectType.CONTRAST)).getOrFail()
        t = ParamOps.setKey(t, "c", contrast, ParamKey(0, 0.0)).getOrFail()
        return ParamOps.setKey(t, "c", contrast, ParamKey(59, 2.0)).getOrFail()
    }

    @Test
    fun `the preview layer carries the effect values of the playhead frame`() {
        val tl = videoScene()
        fun contrastAt(frame: Long): Double =
            previewRequestsAt(tl, listOf(asset("a")), fps, FrameIndex(frame), { 1 }).single().fx.effects.single().values[0]
        assertEquals(0.0, contrastAt(20), 1e-9)
        assertEquals(2.0 * 30 / 59, contrastAt(50), 1e-9) // 30 of the 59 frames between the keys
        assertEquals(2.0, contrastAt(79), 1e-9)
    }

    @Test
    fun `a render clip knows when its effects are animated`() {
        val animated = videoScene().renderClips().single()
        assertTrue(animated.hasAnimatedFx)
        val plain = timeline(track("v1", clip("c", 0, 10, asset = "a"))).renderClips().single()
        assertEquals(false, plain.hasAnimatedFx)
        assertEquals(plain.fx, plain.fxAt(5))
    }

    private fun audioScene(): Timeline {
        var t = timeline(track("a1", clip("m", 30, 60, asset = "a"), type = TrackType.AUDIO))
        t = ParamOps.setKey(t, "m", ParamIds.GAIN_DB, ParamKey(0, -6.0)).getOrFail()
        t = ParamOps.setKey(t, "m", ParamIds.GAIN_DB, ParamKey(30, 0.0, Interpolation.EASE)).getOrFail()
        t = ParamOps.setKey(t, "m", ParamIds.GAIN_DB, ParamKey(59, -12.0)).getOrFail()
        t = ParamOps.setKey(t, "m", ParamIds.PAN, ParamKey(10, -0.5)).getOrFail()
        t = ParamOps.setKey(t, "m", ParamIds.eqGain(3), ParamKey(0, 0.0)).getOrFail()
        return ParamOps.setKey(t, "m", ParamIds.eqGain(3), ParamKey(40, 9.0)).getOrFail()
    }

    @Test
    fun `keyframed audio parameters become lanes that reproduce the curve at every frame`() {
        val tl = audioScene()
        val spec = snapshot(tl, asset("a")).clips.single()
        assertEquals(setOf(AutoParam.GAIN_DB, AutoParam.PAN, AutoParam.eqGain(3)), spec.automation.map { it.param }.toSet())
        val clip = tl.track("a1")!!.clip("m")!!
        fun laneAt(param: AutoParam, frame: Long): Double {
            val points = spec.automation.first { it.param == param }.points
            val hi = points.indexOfFirst { it.frame >= frame }
            if (hi < 0) return points.last().value.toDouble() // the mixer holds the last point
            if (hi == 0) return points.first().value.toDouble()
            val a = points[hi - 1]
            val b = points[hi]
            if (b.frame == frame) return b.value.toDouble()
            val t = (frame - a.frame).toDouble() / (b.frame - a.frame).toDouble()
            return a.value + (b.value - a.value) * t
        }
        for (frame in 0L until 60L) {
            assertEquals("gain @$frame", clip.paramValueAt(ParamIds.GAIN_DB, frame)!!, laneAt(AutoParam.GAIN_DB, frame), 1e-4)
            assertEquals("pan @$frame", clip.paramValueAt(ParamIds.PAN, frame)!!, laneAt(AutoParam.PAN, frame), 1e-4)
            assertEquals("eq @$frame", clip.paramValueAt(ParamIds.eqGain(3), frame)!!, laneAt(AutoParam.eqGain(3), frame), 1e-4)
        }
    }

    @Test
    fun `the volume lane carries the loudness normalise gain like the static gain does`() {
        var tl = audioScene()
        tl = tl.copy(
            tracks = tl.tracks.map { track ->
                track.copy(clips = track.clips.map { it.copy(audio = it.audio.copy(normalizeDb = 4.0)) })
            },
        )
        val spec = snapshot(tl, asset("a")).clips.single()
        val gain = spec.automation.first { it.param == AutoParam.GAIN_DB }
        assertEquals(-6.0f + 4.0f, gain.points.first().value, 1e-5f)
        assertEquals(0.0f + 4.0f, gain.points.first { it.frame == 30L }.value, 1e-5f)
    }

    @Test
    fun `lane frames follow the render window when a transition starts the clip early`() {
        var tl = timeline(
            track(
                "v1",
                clip("x", 0, 40, asset = "a"),
                clip("y", 40, 60, srcIn = 30, asset = "a"),
            ),
        )
        tl = TimelineOps.addTransition(tl, Transition("t1", "x", "y", 10)).getOrFail()
        tl = ParamOps.setKey(tl, "y", ParamIds.PAN, ParamKey(5, -1.0)).getOrFail()
        tl = ParamOps.setKey(tl, "y", ParamIds.PAN, ParamKey(20, 1.0)).getOrFail()
        val render: RenderClip = tl.renderClips().first { it.clipId == "y" }
        val shift = render.keyframeOriginFrame - render.startFrame
        assertTrue(shift > 0)
        val lane = snapshot(tl, asset("a")).clips.first { it.clipKey == keys.keyFor("y") }.automation.single()
        assertEquals(listOf(5L + shift, 20L + shift), lane.points.map { it.frame })
    }
}
