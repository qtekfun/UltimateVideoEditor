package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.domain.Stabilise
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProxyAssistTest {
    private val fps = FrameRate(30, 1)
    private val sdrAsset = MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR")
    private val hlgAsset = MediaAssetDto("h", "content://h", 600, 30, 1, "Rec2020-HLG")
    private val proxy720 = ExportProxy("file:///p/a.mp4", 1280, 720)
    private val proxyH = ExportProxy("file:///p/h.mp4", 1280, 720)

    private fun assist(
        canvas: Pair<Int, Int> = 3840 to 2160,
        output: Pair<Int, Int> = 3840 to 2160,
        hdr: Boolean = false,
        proxies: Map<String, ExportProxy> = mapOf("a" to proxy720, "h" to proxyH),
    ) = ExportProxyAssist(proxies, canvas.first, canvas.second, output.first, output.second, hdr)

    private fun scaled(scale: Double, base: Clip = clip("A", 0, 100, asset = "a")) =
        base.copy(transform = ClipTransform(scaleX = scale, scaleY = scale))

    private fun plan(clip: Clip, assist: ExportProxyAssist?, vararg assets: MediaAssetDto = arrayOf(sdrAsset, hlgAsset)) =
        buildExportPlan(timeline(track("v1", clip)), assets.toList(), fps, 3840, 2160, assist)!!

    // ---- the size rule -------------------------------------------------------------------------------------------

    @Test
    fun `a layer exactly as large as the proxy qualifies, one pixel more does not`() {
        // A 16:9 layer at one third of a 4K canvas covers 1280x720 output pixels: exactly the proxy.
        assertTrue(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 1.0 / 3.0, 1.0 / 3.0))
        assertTrue(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.3333333333, 0.3333333333))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 1281.0 / 3840.0, 1281.0 / 3840.0))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.34, 0.34))
        // Smaller than the proxy: fine.
        assertTrue(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.25, 0.25))
    }

    @Test
    fun `the full canvas needs the original`() {
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 1.0, 1.0))
    }

    @Test
    fun `a smaller export size makes more layers qualify`() {
        // The same full-canvas layer exported at 720p is exactly the proxy; at 1080p it is not.
        assertTrue(proxyCoversLayer(3840, 2160, 1280, 720, 1280, 720, 1.0, 1.0))
        assertFalse(proxyCoversLayer(3840, 2160, 1920, 1080, 1280, 720, 1.0, 1.0))
    }

    @Test
    fun `each axis is checked on its own`() {
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.2, 0.5))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.5, 0.2))
        assertTrue(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.3, 0.3333))
    }

    @Test
    fun `a portrait proxy on a landscape canvas is fitted by its height`() {
        // 720x1280 proxy fitted into 3840x2160: fit = 2160/1280 = 1.6875, so scale 0.5926 covers 720 x 1280... = exactly 1:1.
        assertTrue(proxyCoversLayer(3840, 2160, 3840, 2160, 720, 1280, 1280.0 / 2160.0, 1280.0 / 2160.0))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 720, 1280, 0.6, 0.6))
    }

    @Test
    fun `degenerate sizes and scales never qualify`() {
        assertFalse(proxyCoversLayer(0, 2160, 3840, 2160, 1280, 720, 0.1, 0.1))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 0, 720, 0.1, 0.1))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, 0.0, 0.1))
        assertFalse(proxyCoversLayer(3840, 2160, 3840, 2160, 1280, 720, Double.NaN, 0.1))
    }

    // ---- the plan ------------------------------------------------------------------------------------------------

    @Test
    fun `without the option nothing changes`() {
        val plan = plan(scaled(0.25), null)
        assertTrue(plan.proxyAssets.isEmpty())
        assertEquals(setOf("a"), plan.assetKeys.keys)
        assertEquals(plan.assetKeys["a"], plan.videoClips.single().assetKey)
        // Same plan as when the option is on but no proxy is ready.
        val noneReady = plan(scaled(0.25), assist(proxies = emptyMap()))
        assertEquals(plan.videoClips, noneReady.videoClips)
        assertEquals(plan.assetKeys, noneReady.assetKeys)
    }

    @Test
    fun `a small layer reads the proxy under its own key and as SDR, audio keeps the original`() {
        val plan = plan(scaled(0.25).copy(), assist())
        val spec = plan.videoClips.single()
        val proxyKey = plan.proxyAssets.keys.single()
        assertEquals(proxy720, plan.proxyAssets.getValue(proxyKey))
        assertEquals(proxyKey, spec.assetKey)
        assertFalse("proxy key must not be an original's key", proxyKey in plan.assetKeys.values)
        assertEquals(0, spec.colorMode)
        // Frame mapping is untouched.
        assertEquals(0L, spec.sourceInFrame)
        assertEquals(100L, spec.durationFrames)
        // The original is still listed for the audio.
        assertEquals(setOf("a"), plan.assetKeys.keys)
        assertNotEquals(proxyKey, plan.assetKeys.getValue("a"))
    }

    @Test
    fun `a full-size layer keeps the original`() {
        val plan = plan(clip("A", 0, 100, asset = "a"), assist())
        assertTrue(plan.proxyAssets.isEmpty())
        assertEquals(plan.assetKeys.getValue("a"), plan.videoClips.single().assetKey)
    }

    @Test
    fun `a clip uses the largest scale it reaches over its keyframes`() {
        val base = timeline(track("v1", clip("A", 0, 100, asset = "a").copy(transform = ClipTransform(scaleX = 0.2, scaleY = 0.2))))
        val grows = TimelineOps.setKeyframe(base, "A", Keyframe(0, ClipTransform(scaleX = 0.2, scaleY = 0.2), Interpolation.LINEAR)).getOrFail()
            .let { TimelineOps.setKeyframe(it, "A", Keyframe(80, ClipTransform(scaleX = 0.8, scaleY = 0.8), Interpolation.LINEAR)).getOrFail() }
        val withKeys = buildExportPlan(grows, listOf(sdrAsset), fps, 3840, 2160, assist())!!
        assertTrue("it grows past the proxy's size, so it needs the original", withKeys.proxyAssets.isEmpty())

        val stays = TimelineOps.setKeyframe(base, "A", Keyframe(0, ClipTransform(scaleX = 0.2, scaleY = 0.2), Interpolation.EASE)).getOrFail()
            .let { TimelineOps.setKeyframe(it, "A", Keyframe(80, ClipTransform(scaleX = 0.3, scaleY = 0.3), Interpolation.EASE)).getOrFail() }
        assertEquals(1, buildExportPlan(stays, listOf(sdrAsset), fps, 3840, 2160, assist())!!.proxyAssets.size)
    }

    @Test
    fun `the fixed scale is ignored once keyframes replace it`() {
        // The clip's own transform is large but every key is small: the keys are what the engine draws.
        val big = clip("A", 0, 100, asset = "a").copy(transform = ClipTransform(scaleX = 1.0, scaleY = 1.0))
        val tl = TimelineOps.setKeyframe(timeline(track("v1", big)), "A", Keyframe(0, ClipTransform(scaleX = 0.2, scaleY = 0.2), Interpolation.LINEAR)).getOrFail()
        assertEquals(1, buildExportPlan(tl, listOf(sdrAsset), fps, 3840, 2160, assist())!!.proxyAssets.size)
    }

    @Test
    fun `rotation does not change the decision`() {
        val rotated = scaled(0.3).copy(transform = ClipTransform(scaleX = 0.3, scaleY = 0.3, rotationDegrees = 37.0))
        assertEquals(1, plan(rotated, assist()).proxyAssets.size)
        val tooBig = scaled(0.5).copy(transform = ClipTransform(scaleX = 0.5, scaleY = 0.5, rotationDegrees = 37.0))
        assertTrue(plan(tooBig, assist()).proxyAssets.isEmpty())
    }

    @Test
    fun `effects stabilisation smooth slow motion and still pictures never use a proxy`() {
        val effect = scaled(0.2).copy(fx = ClipFx(effects = listOf(Effect("e", EffectType.BRIGHTNESS))))
        assertTrue(plan(effect, assist()).proxyAssets.isEmpty())
        val stab = scaled(0.2).copy(stabilise = Stabilise())
        assertTrue(plan(stab, assist()).proxyAssets.isEmpty())
        val smooth = scaled(0.2).copy(smoothSlowMo = true)
        assertTrue(plan(smooth, assist()).proxyAssets.isEmpty())
        // A photo clip is not decoded video at all: it has no asset key and no proxy.
        val photo = scaled(0.2).copy(still = StillKind.PHOTO)
        val photoAsset = sdrAsset.copy(isImage = true, hasVideo = false)
        val photoPlan = plan(photo, assist(proxies = mapOf("a" to proxy720)), photoAsset)
        assertTrue(photoPlan.proxyAssets.isEmpty())
        assertEquals(0L, photoPlan.videoClips.single().assetKey)
    }

    @Test
    fun `a colour override that differs from the asset's keeps the original`() {
        val override = scaled(0.2).copy(colorOverride = SourceColorSpace.HLG)
        assertTrue(plan(override, assist()).proxyAssets.isEmpty())
        // An override equal to what the asset is changes nothing the proxy was made with.
        val same = scaled(0.2).copy(colorOverride = SourceColorSpace.SDR)
        assertEquals(1, plan(same, assist()).proxyAssets.size)
    }

    @Test
    fun `an HLG source uses its tone-mapped proxy for an SDR export but not for an HDR one`() {
        val clip = scaled(0.2, clip("A", 0, 100, asset = "h"))
        val sdr = plan(clip, assist(hdr = false))
        assertEquals(1, sdr.proxyAssets.size)
        assertEquals("the proxy is read as SDR Rec.709", 0, sdr.videoClips.single().colorMode)

        val hdr = plan(clip, assist(hdr = true))
        assertTrue(hdr.proxyAssets.isEmpty())
        assertEquals("the original keeps its HLG reading", SourceColorSpace.HLG.nativeModeValue, hdr.videoClips.single().colorMode)
    }

    @Test
    fun `an SDR source may use its proxy in an HDR export`() {
        assertEquals(1, plan(scaled(0.2), assist(hdr = true)).proxyAssets.size)
    }

    @Test
    fun `one asset can mix proxied and original clips and each opens once`() {
        val small = scaled(0.2, clip("A", 0, 100, asset = "a"))
        val full = clip("B", 100, 100, asset = "a")
        val plan = buildExportPlan(timeline(track("v1", small, full)), listOf(sdrAsset), fps, 3840, 2160, assist())!!
        assertEquals(1, plan.proxyAssets.size)
        assertEquals(1, plan.assetKeys.size)
        assertEquals(2, plan.videoClips.map { it.assetKey }.distinct().size)
        assertEquals(setOf(plan.assetKeys.getValue("a"), plan.proxyAssets.keys.single()), plan.videoClips.map { it.assetKey }.toSet())
    }

    @Test
    fun `three layers in thirds all use their proxies on a 4K export`() {
        val clips = (0 until 3).map { i ->
            track("v$i", clip("C$i", 0, 100, asset = "a").copy(transform = ClipTransform(scaleX = 1.0 / 3.0, scaleY = 1.0 / 3.0, positionX = (i - 1) * 1280.0)))
        }
        val plan = buildExportPlan(timeline(*clips.toTypedArray()), listOf(sdrAsset), fps, 3840, 2160, assist())!!
        assertEquals(3, plan.videoClips.size)
        assertTrue(plan.videoClips.all { it.assetKey in plan.proxyAssets })
    }
}
