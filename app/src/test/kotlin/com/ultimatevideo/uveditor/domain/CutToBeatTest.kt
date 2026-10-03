package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutToBeatTest {
    // Overlay on top, base last.
    private fun scene(vararg overlay: Clip) = timeline(
        track("v2", *overlay),
        track("v1", clip("a", 0, 100), clip("b", 100, 150), clip("c", 250, 80)),
    )

    private val beats = listOf(0L, 60, 120, 180, 240, 300)
    private val plenty = mapOf<String, Long?>("a" to 1000L, "b" to 1000L, "c" to 1000L)

    @Test
    fun `every selected clip ends on the nearest beat and the base stays gap free`() {
        val result = CutToBeat(listOf("a", "b", "c"), beats, plenty).apply(scene()).getOrFail()
        // a: aim 100 -> 120 (extend); b: starts 120, aim 270 -> 240 (tie with 300 goes earlier); c: starts 240, aim 320 -> 300.
        assertLayout(result, "v1", at("a", 0, 120), at("b", 120, 240), at("c", 240, 300))
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `a clip cannot be lengthened past its media so the latest reachable beat is used`() {
        val result = CutToBeat(listOf("a"), beats, mapOf("a" to 100L)).apply(scene()).getOrFail()
        // The nearest beat is 120 but a only has 100 frames of media, so 60 is the latest beat it can reach.
        assertLayout(result, "v1", at("a", 0, 60), at("b", 60, 210), at("c", 210, 290))
    }

    @Test
    fun `overlays follow the ripple of the base`() {
        val result = CutToBeat(listOf("a"), beats, plenty).apply(scene(clip("x", 300, 20))).getOrFail()
        // a grows by 20 frames, so the base clips after it and the overlay at 300 move 20 frames later.
        assertLayout(result, "v1", at("a", 0, 120), at("b", 120, 270), at("c", 270, 350))
        assertLayout(result, "v2", at("x", 320, 340))
    }

    @Test
    fun `shortening ripples earlier and an overlay inside the cut is removed`() {
        val result = CutToBeat(listOf("b"), listOf(180L), plenty).apply(scene(clip("x", 200, 20), clip("y", 260, 10))).getOrFail()
        // b: starts 100, aim 250 -> only beat after the start is 180, so it now ends there (70 frames removed from 180..250).
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 180), at("c", 180, 260))
        assertLayout(result, "v2", at("y", 190, 200))
    }

    @Test
    fun `stills and titles may be lengthened freely`() {
        val still = Clip("s", "img", f(0), FrameIndex.ZERO, f(30), still = StillKind.PHOTO)
        val t = timeline(track("v1", still, clip("n", 30, 40)))
        val result = CutToBeat(listOf("s"), listOf(0L, 90L), emptyMap()).apply(t).getOrFail()
        assertLayout(result, "v1", at("s", 0, 90), at("n", 90, 130))
    }

    @Test
    fun `a clip with no reachable beat is left alone`() {
        val result = CutToBeat(listOf("a"), listOf(0L), plenty).apply(scene()).getOrFail()
        assertEquals(scene(), result)
    }

    @Test
    fun `the first clip keeps its start`() {
        val t = timeline(track("v2"), track("v1", clip("a", 0, 100), clip("b", 100, 100)))
        val result = CutToBeat(listOf("b"), listOf(90L, 210L), plenty).apply(t).getOrFail()
        assertEquals(100L, result.track("v1")!!.clip("b")!!.timelineStart.value)
        assertEquals(210L, result.track("v1")!!.clip("b")!!.timelineEnd.value)
    }

    @Test
    fun `selecting clips off the base or having no beats is reported`() {
        val t = scene(clip("x", 0, 50))
        assertTrue(CutToBeat(listOf("x"), beats, plenty).apply(t).errorOrFail() is EditError.CutToBeatUnavailable)
        assertTrue(CutToBeat(listOf("a"), emptyList(), plenty).apply(t).errorOrFail() is EditError.CutToBeatUnavailable)
        assertTrue(CutToBeat(emptyList(), beats, plenty).apply(t).errorOrFail() is EditError.CutToBeatUnavailable)
        assertTrue(CutToBeat(listOf("a"), beats, plenty).apply(timeline(track("a1", type = TrackType.AUDIO))).errorOrFail() is EditError.CutToBeatUnavailable)
    }

    @Test
    fun `it is one undo step`() {
        val original = scene(clip("x", 300, 20))
        var history = EditHistory(original)
        history = (history.execute(CutToBeat(listOf("a", "b", "c"), beats, plenty)) as EditResult.Success).value
        assertEquals(1, history.undoDepth)
        assertTrue(history.timeline != original)
        assertEquals(original, history.undo().timeline)
    }

    @Test
    fun `the base never gets a gap whatever the beats are`() {
        for (offset in 1L..59L step 7) {
            val shifted = beats.map { it + offset }
            val result = CutToBeat(listOf("a", "b", "c"), shifted, plenty).apply(scene(clip("x", 120, 40))).getOrFail()
            assertEquals(emptyList<String>(), result.invariantViolations())
            assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
        }
    }
}
