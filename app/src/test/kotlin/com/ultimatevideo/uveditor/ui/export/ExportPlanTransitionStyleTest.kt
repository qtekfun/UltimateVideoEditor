package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TransitionDirection
import com.ultimatevideo.uveditor.domain.TransitionType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.export.VideoClipSpec
import com.ultimatevideo.uveditor.proxy.ResolvedSource
import com.ultimatevideo.uveditor.ui.editor.previewRequestsOnCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanTransitionStyleTest {
    private val fps = FrameRate(30, 1)
    private val assets = listOf(asset("a"), asset("b"))
    private val canvasWidth = 1920
    private val canvasHeight = 1080

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    /** A (0..100) and B (100..200) with a 10-frame transition: A runs to 105, B starts at 95. */
    private fun scene(type: TransitionType, direction: TransitionDirection = TransitionDirection.LEFT): Timeline {
        val base = timeline(track("v1", clip("A", 0, 100, asset = "a"), clip("B", 100, 100, srcIn = 50, asset = "b")))
        return TimelineOps.addTransition(base, Transition("t", "A", "B", 10, type, direction)).getOrFail()
    }

    private fun specs(tl: Timeline): Pair<VideoClipSpec, VideoClipSpec> {
        val (outgoing, incoming) = buildExportPlan(tl, assets, fps, canvasWidth, canvasHeight)!!.videoClips.sortedBy { it.startFrame }
        return outgoing to incoming
    }

    @Test
    fun `only a crossfade and a light leak keep the native fade of the incoming picture`() {
        for (type in TransitionType.entries) {
            val (_, incoming) = specs(scene(type))
            assertEquals("$type", if (type.fadesVideo) 10L else 0L, incoming.crossfadeInFrames)
        }
    }

    @Test
    fun `a crossfade exports exactly what it did before the pack`() {
        val (outgoing, incoming) = specs(scene(TransitionType.CROSSFADE))
        assertTrue(outgoing.keyframes.isEmpty() && incoming.keyframes.isEmpty())
        assertNull(outgoing.fxFrames)
        assertNull(incoming.fxFrames)
    }

    @Test
    fun `a slide exports the pose of every frame of the transition and settles exactly after it`() {
        val tl = scene(TransitionType.SLIDE)
        val (outgoing, incoming) = specs(tl)
        val b = tl.renderClips().first { it.clipId == "B" }
        for (frame in 95L until 105L) {
            val key = incoming.keyframes.single { it.frame == frame - incoming.keyframeOriginFrame }
            val pose = b.appearanceAt(frame, canvasWidth, canvasHeight)
            assertEquals("x at $frame", pose.positionX, key.positionX, 1e-9)
            assertEquals("opacity at $frame", 1.0, key.opacity, 0.0)
        }
        // The key just after the transition is the clip's plain pose, so nothing is left over from the slide.
        val after = incoming.keyframes.single { it.frame == 105L - incoming.keyframeOriginFrame }
        assertEquals(0.0, after.positionX, 0.0)
        // The old picture of a slide stays where it is, so it needs no keys.
        assertTrue(outgoing.keyframes.isEmpty())
        // Sorted, so the native evaluator can walk them.
        assertEquals(incoming.keyframes.map { it.frame }, incoming.keyframes.map { it.frame }.sorted())
    }

    @Test
    fun `a push moves both pictures`() {
        val (outgoing, incoming) = specs(scene(TransitionType.PUSH))
        assertTrue(outgoing.keyframes.size >= 10 && incoming.keyframes.size >= 10)
        assertTrue(outgoing.keyframes.any { it.positionX < -100.0 })
        assertTrue(incoming.keyframes.any { it.positionX > 100.0 })
    }

    @Test
    fun `a wipe and a light leak export per frame effects, not poses`() {
        val (_, wipe) = specs(scene(TransitionType.WIPE))
        assertTrue(wipe.keyframes.isEmpty())
        val frames = checkNotNull(wipe.fxFrames)
        assertEquals(wipe.durationFrames.toInt(), frames.size)
        // Frame 100 is mid-transition for B (it starts at 95): the mask is there; long after the transition it is gone.
        assertNotNull(frames[(100 - wipe.startFrame).toInt()].mask)
        assertNull(frames[(150 - wipe.startFrame).toInt()].mask)

        val (outgoing, leak) = specs(scene(TransitionType.LIGHT_LEAK))
        for (spec in listOf(outgoing, leak)) {
            val fx = checkNotNull(spec.fxFrames)
            val mid = fx[(100 - spec.startFrame).toInt()]
            assertTrue(mid.effects.single { it.type == EffectType.EXPOSURE }.values.single() > 0.5)
            // Well outside the transition (A is at frame 60, B at frame 150): no glow.
            val outside = fx[((if (spec.startFrame == 0L) 60L else 150L) - spec.startFrame).toInt()]
            assertTrue(outside.effects.none { it.type == EffectType.EXPOSURE })
        }
    }

    @Test
    fun `the preview draws the same pose as the exporter for every moving look`() {
        for (type in listOf(TransitionType.SLIDE, TransitionType.PUSH, TransitionType.ZOOM, TransitionType.SPIN, TransitionType.GLITCH, TransitionType.WHIP_PAN)) {
            val tl = scene(type, TransitionDirection.UP)
            val (outgoing, incoming) = specs(tl)
            for (frame in 95L until 105L) {
                val layers = previewRequestsOnCanvas(tl, assets, fps, FrameIndex(frame), canvasWidth, canvasHeight, { ResolvedSource(it.uri) }) { 1 }
                // Bottom first: the outgoing A, then the incoming B.
                assertEquals(2, layers.size)
                for ((layer, spec) in listOf(layers[0] to outgoing, layers[1] to incoming)) {
                    val key = spec.keyframes.firstOrNull { it.frame == frame - spec.keyframeOriginFrame }
                    val t = layer.transform
                    if (key != null) {
                        assertEquals("$type x at $frame", t.positionX, key.positionX, 1e-9)
                        assertEquals("$type y at $frame", t.positionY, key.positionY, 1e-9)
                        assertEquals("$type scale at $frame", t.scaleX, key.scaleX, 1e-9)
                        assertEquals("$type rotation at $frame", t.rotationDegrees, key.rotationDegrees, 1e-9)
                        assertEquals("$type opacity at $frame", t.opacity, key.opacity, 1e-9)
                    } else {
                        // No key means the transition leaves this picture alone: the plain pose.
                        assertEquals(0.0, t.positionX, 1e-9)
                        assertEquals(1.0, t.scaleX, 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun `the preview shapes the incoming picture and the old one stays under it`() {
        val tl = scene(TransitionType.SLIDE)
        val layers = previewRequestsOnCanvas(tl, assets, fps, FrameIndex(95), canvasWidth, canvasHeight, { ResolvedSource(it.uri) }) { 1 }
        val (below, above) = layers
        assertEquals(0.0, below.transform.positionX, 0.0)
        assertTrue(above.transform.positionX > 0.9 * canvasWidth)
        assertEquals(1.0, above.transform.opacity, 0.0)
        // Outside the transition nothing changes.
        val plain = previewRequestsOnCanvas(tl, assets, fps, FrameIndex(30), canvasWidth, canvasHeight, { ResolvedSource(it.uri) }) { 1 }.single()
        assertEquals(0.0, plain.transform.positionX, 0.0)
    }

    @Test
    fun `the preview crossfade still fades by opacity and a wipe carries its mask`() {
        val fade = previewRequestsOnCanvas(scene(TransitionType.CROSSFADE), assets, fps, FrameIndex(95), canvasWidth, canvasHeight, { ResolvedSource(it.uri) }) { 1 }
        assertTrue(fade[1].transform.opacity < 0.2)
        val wipe = previewRequestsOnCanvas(scene(TransitionType.WIPE), assets, fps, FrameIndex(100), canvasWidth, canvasHeight, { ResolvedSource(it.uri) }) { 1 }
        assertNotNull(wipe[1].fx.mask)
        assertNull(wipe[0].fx.mask)
        assertEquals(1.0, wipe[1].transform.opacity, 0.0)
    }
}
