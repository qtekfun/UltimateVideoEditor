package com.ultimatevideo.uveditor.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smooth slow motion (fractional source position), speed-curve presets with eased keys, and the 100x limit. */
class SlowMotionTest {

    private fun retimed(span: Long, length: Long, srcIn: Long = 0, reverse: Boolean = false, ramp: List<SpeedKey> = emptyList()) =
        Clip(
            "c", "a", f(0), f(srcIn), f(srcIn + span),
            retimedFrames = length.takeIf { it != span },
            reverse = reverse,
            speedRamp = ramp,
        )

    // ---- mixAt ----

    @Test
    fun `half speed alternates between a frame and the middle of two frames`() {
        val r = retimed(span = 10, length = 20).retime
        assertEquals(SourceMix(0, 0, 0), r.mixAt(0))
        assertEquals(SourceMix(0, 1, 500), r.mixAt(1))
        assertEquals(SourceMix(1, 1, 0), r.mixAt(2))
        assertEquals(SourceMix(1, 2, 500), r.mixAt(3))
        assertTrue(r.mixAt(3).blended)
        assertFalse(r.mixAt(2).blended)
    }

    @Test
    fun `quarter speed steps through quarters exactly`() {
        val r = retimed(span = 10, length = 40, srcIn = 5).retime
        val mixes = (0L..3L).map { r.mixAt(it).mixPermille }
        assertEquals(listOf(0, 250, 500, 750), mixes)
        assertEquals(listOf(5L, 5L, 5L, 5L), (0L..3L).map { r.mixAt(it).frame })
        assertEquals(6L, r.mixAt(4).frame)
    }

    @Test
    fun `the last frame never blends with media the editor cut away`() {
        val r = retimed(span = 10, length = 20).retime
        // Position 9.5: the next frame would be source frame 10, beyond the range.
        assertEquals(SourceMix(9, 9, 0), r.mixAt(19))
    }

    @Test
    fun `speeds of 1x or faster show single frames`() {
        for (r in listOf(retimed(span = 10, length = 10, reverse = true).retime, retimed(span = 20, length = 10).retime)) {
            for (t in 0L until r.timelineFrames) assertEquals("t=$t", 0, r.mixAt(t).mixPermille)
        }
    }

    @Test
    fun `a freeze frame never blends`() {
        val r = retimed(span = 1, length = 30).retime
        for (t in 0L until 30L) assertEquals(0, r.mixAt(t).mixPermille)
    }

    @Test
    fun `a reversed slow clip blends with the frame before`() {
        val r = retimed(span = 10, length = 20, srcIn = 100, reverse = true).retime
        assertEquals(SourceMix(109, 109, 0), r.mixAt(0))
        assertEquals(SourceMix(109, 108, 500), r.mixAt(1))
        assertEquals(SourceMix(108, 108, 0), r.mixAt(2))
        // The frame before the range does not exist for the first frame of the range.
        assertEquals(SourceMix(100, 100, 0), r.mixAt(19))
    }

    @Test
    fun `a ramp blends only where it is slower than real time`() {
        // Slow in the middle (weight 300), fast at both ends (weight 3000): span 60 over 60 frames averages 1x.
        val ramp = listOf(SpeedKey(0, 2400), SpeedKey(29, 300), SpeedKey(59, 2400))
        val r = retimed(span = 60, length = 60, ramp = ramp).retime
        var blendedSomewhere = false
        for (t in 0L until 60L) {
            val advance = r.position(t + 1.0) - r.position(t.toDouble())
            val mix = r.mixAt(t)
            if (advance >= 1.0) assertEquals("fast frame $t is shown whole", 0, mix.mixPermille)
            if (mix.blended) blendedSomewhere = true
            assertEquals(r.sourceFrameAt(t), mix.frame)
        }
        assertTrue(blendedSomewhere)
    }

    // ---- eased (smooth) speed keys ----

    @Test
    fun `an eased ramp still ends exactly at the end of the range`() {
        for (smooth in listOf(false, true)) {
            val ramp = listOf(SpeedKey(0, 400, smooth), SpeedKey(30, 2000, smooth), SpeedKey(59, 700, smooth))
            val r = retimed(span = 120, length = 60, ramp = ramp).retime
            assertEquals(0.0, r.position(0.0), 1e-9)
            assertEquals(120.0, r.position(60.0), 1e-6)
            var previous = -1.0
            for (t in 0..60) {
                val p = r.position(t.toDouble())
                assertTrue("position must rise (smooth=$smooth t=$t)", p > previous)
                previous = p
            }
        }
    }

    @Test
    fun `the eased weight has flat ends and meets the keys`() {
        val keys = listOf(SpeedKey(0, 400, true), SpeedKey(40, 2000, true), SpeedKey(80, 400))
        assertEquals(400.0, SpeedRamps.weightAt(keys, 0.0), 1e-9)
        assertEquals(2000.0, SpeedRamps.weightAt(keys, 40.0), 1e-9)
        // Halfway through an eased segment is exactly halfway between the weights; a quarter in is below the linear value.
        assertEquals(1200.0, SpeedRamps.weightAt(keys, 20.0), 1e-9)
        val linearQuarter = 400 + 1600 * 0.25
        assertTrue(SpeedRamps.weightAt(keys, 10.0) < linearQuarter)
        // The segment starting at the unsmoothed last key is not eased (nothing follows it).
        assertEquals(400.0, SpeedRamps.weightAt(keys, 90.0), 1e-9)
    }

    @Test
    fun `position is the integral of the eased weight`() {
        val keys = listOf(SpeedKey(0, 500, true), SpeedKey(30, 1800, true), SpeedKey(59, 600, true))
        val r = retimed(span = 90, length = 60, ramp = keys).retime
        // Numerically differentiate the position and compare with the normalised weight.
        val total = r.position(60.0)
        assertEquals(90.0, total, 1e-6)
        for (t in listOf(5, 17, 29, 41, 52)) {
            val speed = r.position(t + 0.5) - r.position(t - 0.5)
            val weight = SpeedRamps.weightAt(keys, t.toDouble())
            // speed = span * weight / integral; the integral is position-independent, so the ratio of two speeds equals the ratio of two weights.
            val speed0 = r.position(10.5) - r.position(9.5)
            val weight0 = SpeedRamps.weightAt(keys, 10.0)
            assertEquals("t=$t", weight / weight0, speed / speed0, 0.02)
        }
    }

    @Test
    fun `presets are valid eased ramps that keep the clip range`() {
        val presets = mapOf(
            "montage" to SpeedRamps.montage(90),
            "hero" to SpeedRamps.hero(90),
            "bullet" to SpeedRamps.bullet(90),
            "easeInSmooth" to SpeedRamps.easeInSmooth(90),
            "easeOutSmooth" to SpeedRamps.easeOutSmooth(90),
        )
        for ((name, keys) in presets) {
            assertEquals(name, null, SpeedRamps.problem(keys, 90))
            assertTrue("$name has keys", keys.size >= 4)
            assertTrue("$name is eased", keys.dropLast(1).all { it.smooth })
            val clip = retimed(span = 180, length = 90, ramp = keys)
            assertEquals(name, 180.0, clip.retime.position(90.0), 1e-6)
        }
        assertTrue(SpeedRamps.hero(1).isEmpty())
    }

    @Test
    fun `cropping an eased ramp keeps its keys eased`() {
        val keys = SpeedRamps.hero(90)
        val cropped = SpeedRamps.cropped(keys, 10, 80)
        assertTrue(cropped.isNotEmpty())
        assertTrue(cropped.drop(1).dropLast(1).all { it.smooth })
        assertEquals(null, SpeedRamps.problem(cropped, 70))
    }

    // ---- limits ----

    @Test
    fun `speed limits reach 100x and the edges are exact`() {
        assertEquals(null, SpeedLimits.problem(100, 1))
        assertTrue(SpeedLimits.problem(101, 1) != null)
        assertEquals(null, SpeedLimits.problem(1, 10))
        assertTrue(SpeedLimits.problem(1, 11) != null)
    }

    @Test
    fun `a 100x clip steps a hundred source frames each frame`() {
        val clip = retimed(span = 1000, length = 10)
        assertEquals((0 until 10).map { it * 100L }, (0L until 10L).map { clip.retime.sourceFrameAt(it) })
        for (t in 0L until 10L) assertEquals(0, clip.retime.mixAt(t).mixPermille)
    }

    // ---- operations ----

    private val base = timeline(track("v1", Clip("a", "x", f(0), f(0), f(20), retimedFrames = 40)))

    @Test
    fun `smooth slow motion turns on and off with one undo step each`() {
        val on = TimelineOps.setSmoothSlowMo(base, "a", true).getOrFail()
        assertTrue(on.track("v1")!!.clip("a")!!.smoothSlowMo)
        val history = EditHistory(base).execute(EditCommand.SetSmoothSlowMo("a", true)).getOrFail()
        assertTrue(history.timeline.track("v1")!!.clip("a")!!.smoothSlowMo)
        assertEquals(base, history.undo().timeline)
        val off = TimelineOps.setSmoothSlowMo(on, "a", false).getOrFail()
        assertEquals(base, off)
    }

    @Test
    fun `titles cannot be interpolated and unknown clips fail`() {
        val title = Clip("t", null, f(0), f(0), f(30), title = TitleContent("hi"))
        val withTitle = timeline(Track("t1", TrackType.TITLE, listOf(title)))
        assertTrue(TimelineOps.setSmoothSlowMo(withTitle, "t", true).errorOrFail() is EditError.InvalidSpeed)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setSmoothSlowMo(base, "zz", true).errorOrFail())
    }

    @Test
    fun `splitting and cropping keep the flag on both halves`() {
        val on = TimelineOps.setSmoothSlowMo(base, "a", true).getOrFail()
        val split = TimelineOps.split(on, "v1", f(20), "b").getOrFail()
        assertTrue(split.track("v1")!!.clips.all { it.smoothSlowMo })
    }

    // ---- the render plan ----

    @Test
    fun `the render plan interpolates only clips that ask for it`() {
        val off = base.renderClips().single()
        assertEquals(0, off.sourceMixAt(1).mixPermille)
        val on = TimelineOps.setSmoothSlowMo(base, "a", true).getOrFail().renderClips().single()
        assertEquals(SourceMix(0, 1, 500), on.sourceMixAt(1))
        assertEquals(on.sourceFrameAt(1), on.sourceMixAt(1).frame)
        assertFalse(on.sourceMixAt(0).blended)
    }

    @Test
    fun `a clip played at normal speed is never interpolated`() {
        val plain = timeline(track("v1", Clip("a", "x", f(0), f(0), f(20), smoothSlowMo = true)))
        assertEquals(0, plain.renderClips().single().sourceMixAt(3).mixPermille)
    }

    @Test
    fun `mix never moves the frame the mapping shows`() {
        val clip = retimed(span = 33, length = 100, srcIn = 7, ramp = SpeedRamps.bell(100))
        val r = clip.retime
        for (t in 0L until 100L) {
            assertEquals(r.sourceFrameAt(t), r.mixAt(t).frame)
            val mix = r.mixAt(t)
            if (mix.blended) assertTrue(abs(mix.next - mix.frame) == 1L)
        }
    }
}
