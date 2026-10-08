package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TransitionLooksTest {
    private val w = 1920
    private val h = 1080
    private val frames = 20L

    private fun look(type: TransitionType, direction: TransitionDirection = TransitionDirection.LEFT) = TransitionLook(type, direction, frames)

    private fun mod(type: TransitionType, incoming: Boolean, k: Long, direction: TransitionDirection = TransitionDirection.LEFT, frame: Long = 100 + k) =
        TransitionLooks.modAt(look(type, direction), incoming, k, w, h, frame)

    @Test
    fun `a crossfade changes nothing but the fade`() {
        for (k in 0 until frames) {
            assertTrue(mod(TransitionType.CROSSFADE, true, k).isNone)
            assertTrue(mod(TransitionType.CROSSFADE, false, k).isNone)
        }
    }

    @Test
    fun `slide brings the new picture in from the side it travels from and leaves the old one still`() {
        // LEFT: the new picture moves left, so it starts to the right of the canvas.
        val start = mod(TransitionType.SLIDE, true, 0)
        assertTrue(start.offsetX > 0.9 * w)
        assertEquals(0.0, start.offsetY, 1e-9)
        assertEquals(1.0, start.opacity, 1e-12)
        val end = mod(TransitionType.SLIDE, true, frames - 1)
        assertTrue(abs(end.offsetX) < 0.01 * w)
        assertTrue(mod(TransitionType.SLIDE, false, 5).isNone)
        assertTrue(mod(TransitionType.SLIDE, true, 0, TransitionDirection.RIGHT).offsetX < -0.9 * w)
        assertTrue(mod(TransitionType.SLIDE, true, 0, TransitionDirection.UP).offsetY > 0.9 * h)
        assertTrue(mod(TransitionType.SLIDE, true, 0, TransitionDirection.DOWN).offsetY < -0.9 * h)
    }

    @Test
    fun `the incoming picture only ever moves towards its place`() {
        for (type in listOf(TransitionType.SLIDE, TransitionType.PUSH, TransitionType.WHIP_PAN)) {
            var previous = Double.MAX_VALUE
            for (k in 0 until frames) {
                val x = abs(mod(type, true, k).offsetX)
                assertTrue("$type at $k", x <= previous + 1e-9)
                previous = x
            }
        }
    }

    @Test
    fun `push also carries the old picture out the way the new one travels`() {
        val early = mod(TransitionType.PUSH, false, 2)
        val late = mod(TransitionType.PUSH, false, frames - 2)
        assertTrue(early.offsetX < 0 && late.offsetX < early.offsetX)
        assertTrue(late.offsetX < -0.9 * w)
    }

    @Test
    fun `zoom grows the old picture and scales the new one up while it fades in`() {
        val first = mod(TransitionType.ZOOM, true, 0)
        val last = mod(TransitionType.ZOOM, true, frames - 1)
        assertTrue(first.scale < last.scale && last.scale <= 1.0)
        assertTrue(first.opacity < 0.1 && last.opacity > 0.9)
        assertTrue(mod(TransitionType.ZOOM, false, frames - 1).scale > 1.3)
    }

    @Test
    fun `spin turns the new picture to rest and the two directions turn opposite ways`() {
        val first = mod(TransitionType.SPIN, true, 0)
        val last = mod(TransitionType.SPIN, true, frames - 1)
        assertTrue(abs(first.rotationDegrees) > 150 && abs(last.rotationDegrees) < 2)
        val a = mod(TransitionType.SPIN, true, 3, TransitionDirection.LEFT).rotationDegrees
        val b = mod(TransitionType.SPIN, true, 3, TransitionDirection.RIGHT).rotationDegrees
        assertTrue(a * b < 0)
    }

    @Test
    fun `glitch is deterministic, flickers the new picture on and off, and adds valid effects`() {
        val a = mod(TransitionType.GLITCH, true, 7, frame = 507)
        val b = mod(TransitionType.GLITCH, true, 7, frame = 507)
        assertEquals(a, b)
        val opacities = (0 until frames).map { mod(TransitionType.GLITCH, true, it).opacity }.toSet()
        assertEquals(setOf(0.0, 1.0), opacities)
        assertTrue(a.effects.isNotEmpty())
        for (effect in a.effects) assertNull(effect.problem())
        // The old picture never disappears.
        assertTrue((0 until frames).all { mod(TransitionType.GLITCH, false, it).opacity == 1.0 })
        // A different frame gives a different jitter.
        assertNotEquals(mod(TransitionType.GLITCH, true, 7, frame = 507).offsetX, mod(TransitionType.GLITCH, true, 7, frame = 508).offsetX)
    }

    @Test
    fun `wipe reveals the new picture with a valid growing mask from the edge it comes from`() {
        var previousWidth = 0.0
        for (k in 0 until frames) {
            val m = mod(TransitionType.WIPE, true, k)
            val mask = m.mask
            if (mask == null) {
                assertEquals(0.0, m.opacity, 0.0)
                continue
            }
            assertNull("mask at $k", mask.problem())
            assertTrue(mask.width >= previousWidth - 1e-9)
            previousWidth = mask.width
            // Coming from the right (LEFT), the mask is anchored to the right edge of the picture.
            assertTrue(mask.centerX + mask.width / 2 >= 0.5 - 1e-9)
        }
        assertTrue(mod(TransitionType.WIPE, true, frames - 1).mask!!.width > 1.0)
        assertTrue(mod(TransitionType.WIPE, false, 5).isNone)
        val fromBelow = mod(TransitionType.WIPE, true, 10, TransitionDirection.UP).mask!!
        assertTrue(fromBelow.centerY > 0 && fromBelow.height < 1.1 && fromBelow.width >= 1.1)
        val fromLeft = mod(TransitionType.WIPE, true, 10, TransitionDirection.RIGHT).mask!!
        assertTrue(fromLeft.centerX < 0)
    }

    @Test
    fun `whip pan blurs most in the middle and light leak glows most there`() {
        fun blur(k: Long) = mod(TransitionType.WHIP_PAN, true, k).effects.single { it.type == EffectType.BLUR }.values.single()
        assertTrue(blur(frames / 2) > blur(0) && blur(frames / 2) > blur(frames - 1))
        fun glow(k: Long) = mod(TransitionType.LIGHT_LEAK, true, k).effects.single { it.type == EffectType.EXPOSURE }.values.single()
        assertTrue(glow(frames / 2) > 1.0 && glow(0) < 0.1 && glow(frames - 1) < 0.1)
        assertEquals(glow(7), mod(TransitionType.LIGHT_LEAK, false, 7).effects.single { it.type == EffectType.EXPOSURE }.values.single(), 1e-12)
    }

    @Test
    fun `every look gives valid values at every frame, direction and side`() {
        for (type in TransitionType.entries) for (direction in TransitionDirection.entries) for (incoming in listOf(true, false)) {
            for (k in 0 until frames) {
                val m = mod(type, incoming, k, direction)
                assertTrue(m.offsetX.isFinite() && m.offsetY.isFinite() && m.rotationDegrees.isFinite())
                assertTrue("$type scale", m.scale > 0.0 && m.scale.isFinite())
                assertTrue("$type opacity", m.opacity in 0.0..1.0)
                for (effect in m.effects) assertNull("$type ${effect.id}", effect.problem())
                m.mask?.let { assertNull("$type mask", it.problem()) }
            }
        }
    }

    @Test
    fun `a transition of no frames does nothing`() {
        assertTrue(TransitionLooks.modAt(TransitionLook(TransitionType.SLIDE, TransitionDirection.LEFT, 0), true, 0, w, h, 0).isNone)
    }

    @Test
    fun `transition effects respect the clip's own mask and the effect limit`() {
        val own = ClipMask(width = 0.5, height = 0.5)
        val wipe = mod(TransitionType.WIPE, true, 10)
        assertEquals(own, ClipFx(mask = own).withTransition(wipe).mask)
        assertEquals(wipe.mask, ClipFx().withTransition(wipe).mask)

        val full = ClipFx(effects = List(ClipFx.MAX_EFFECTS) { Effect("e$it", EffectType.BRIGHTNESS) })
        assertEquals(ClipFx.MAX_EFFECTS, full.withTransition(mod(TransitionType.GLITCH, true, 5)).effects.size)
        val some = ClipFx(effects = List(ClipFx.MAX_EFFECTS - 1) { Effect("e$it", EffectType.BRIGHTNESS) })
        assertEquals(ClipFx.MAX_EFFECTS, some.withTransition(mod(TransitionType.GLITCH, true, 5)).effects.size)
        assertEquals(ClipFx(), ClipFx().withTransition(TransitionMod.NONE))
    }

    @Test
    fun `the hash is fixed so a glitch looks the same everywhere`() {
        assertEquals(TransitionLooks.hash01(42), TransitionLooks.hash01(42), 0.0)
        assertTrue((0L until 1000L).all { TransitionLooks.hash01(it) in 0.0..1.0 })
        // A cheap spread check: both halves of the range occur.
        assertTrue((0L until 100L).any { TransitionLooks.hash01(it) < 0.5 } && (0L until 100L).any { TransitionLooks.hash01(it) >= 0.5 })
    }
}
