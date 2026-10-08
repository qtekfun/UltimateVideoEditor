package com.qtekfun.ultimatevideoeditor.domain

import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCutTest {
    private val fps = FrameRate(30, 1)

    /** 100 bins per second; each part is (seconds, level). */
    private fun envelope(vararg parts: Pair<Double, Float>): PeakEnvelope {
        val values = ArrayList<Float>()
        for ((seconds, level) in parts) repeat((seconds * 100).toInt()) { values += level }
        return PeakEnvelope(100.0, values.toFloatArray())
    }

    // region detection

    @Test
    fun `a long silence is found and padded at both ends`() {
        val env = envelope(2.0 to 0.5f, 1.0 to 0.0f, 2.0 to 0.5f)
        val spans = SilenceDetector.detect(env, SilenceSettings(thresholdDb = -40.0, minSilenceSeconds = 0.5, paddingSeconds = 0.1))
        assertEquals(listOf(SilentSpan(2_100_000, 2_900_000)), spans)
    }

    @Test
    fun `a silence shorter than the minimum is ignored`() {
        val env = envelope(2.0 to 0.5f, 0.3 to 0.0f, 2.0 to 0.5f)
        assertEquals(emptyList<SilentSpan>(), SilenceDetector.detect(env, SilenceSettings(minSilenceSeconds = 0.5, paddingSeconds = 0.0)))
    }

    @Test
    fun `a silence that padding would swallow is dropped`() {
        val env = envelope(1.0 to 0.5f, 0.6 to 0.0f, 1.0 to 0.5f)
        assertEquals(emptyList<SilentSpan>(), SilenceDetector.detect(env, SilenceSettings(minSilenceSeconds = 0.5, paddingSeconds = 0.4)))
    }

    @Test
    fun `silence at the very start and end of the window counts`() {
        val env = envelope(1.0 to 0.0f, 1.0 to 0.5f, 1.0 to 0.0f)
        val spans = SilenceDetector.detect(env, SilenceSettings(minSilenceSeconds = 0.5, paddingSeconds = 0.0))
        assertEquals(listOf(SilentSpan(0, 1_000_000), SilentSpan(2_000_000, 3_000_000)), spans)
    }

    @Test
    fun `the threshold decides what is silent`() {
        // 0.05 is about -26 dBFS: silent under a -20 dB threshold, speech under -40 dB.
        val env = envelope(1.0 to 0.5f, 1.0 to 0.05f, 1.0 to 0.5f)
        assertEquals(1, SilenceDetector.detect(env, SilenceSettings(thresholdDb = -20.0, paddingSeconds = 0.0)).size)
        assertEquals(0, SilenceDetector.detect(env, SilenceSettings(thresholdDb = -40.0, paddingSeconds = 0.0)).size)
    }

    @Test
    fun `settings outside their ranges are rejected`() {
        assertTrue(SilenceSettings(thresholdDb = 0.0).problem() != null)
        assertTrue(SilenceSettings(minSilenceSeconds = 0.0).problem() != null)
        assertTrue(SilenceSettings(paddingSeconds = -1.0).problem() != null)
        assertEquals(null, SilenceSettings().problem())
    }

    // endregion

    // region planning

    @Test
    fun `spans map onto the timeline through the clip's source range`() {
        // The clip shows source frames 60..360 at timeline 100..400, and the analysed window began at source time 1 s.
        val clip = clip("a", 100, 300, srcIn = 60)
        val cuts = AutoCutPlanner.cuts(clip, fps, 1_000_000, listOf(SilentSpan(1_000_000, 2_000_000)))
        // Source seconds 2..3 are source frames 60..90: timeline 100..130.
        assertEquals(listOf(CutSpan(100, 130)), cuts)
    }

    @Test
    fun `spans are clipped to the clip and shrunk to whole frames`() {
        val clip = clip("a", 0, 90, srcIn = 30)
        val cuts = AutoCutPlanner.cuts(
            clip,
            fps,
            0,
            listOf(SilentSpan(500_000, 1_500_000), SilentSpan(2_900_000, 4_500_000), SilentSpan(10_000_000, 11_000_000)),
        )
        // First span: source frames 15..45 clipped to 30..45 -> timeline 0..15. Second: 87..135 (ceil/floor) clipped to 87..120 -> timeline 57..90. Third is outside.
        assertEquals(listOf(CutSpan(0, 15), CutSpan(57, 90)), cuts)
        assertEquals(48L, AutoCutPlanner.savedFrames(cuts))
    }

    @Test
    fun `a retimed or reversed clip is not planned`() {
        val retimed = clip("a", 0, 90).copy(retimedFrames = 180)
        assertEquals(emptyList<CutSpan>(), AutoCutPlanner.cuts(retimed, fps, 0, listOf(SilentSpan(0, 1_000_000))))
        val reversed = clip("b", 0, 90).copy(reverse = true)
        assertEquals(emptyList<CutSpan>(), AutoCutPlanner.cuts(reversed, fps, 0, listOf(SilentSpan(0, 1_000_000))))
    }

    // endregion

    // region applying

    private fun scene() = timeline(
        track("v2", clip("o1", 40, 20), clip("o2", 150, 30)),
        track("v1", clip("a", 0, 200), clip("b", 200, 100)),
    )

    @Test
    fun `a cut in the middle of a base clip closes the gap and overlays follow`() {
        val result = AutoCut(listOf(CutSpan(80, 110))).apply(scene()).getOrFail()
        val base = result.layout("v1")
        assertEquals(listOf(0L to 80L, 80L to 170L, 170L to 270L), base.map { it.second to it.third })
        // o1 (40..60) is before the cut and stays; o2 (150..180) is after it and moves left by 30.
        assertLayout(result, "v2", at("o1", 40, 60), at("o2", 120, 150))
    }

    @Test
    fun `several cuts are applied from the end backwards and equal their total`() {
        val cuts = listOf(CutSpan(20, 30), CutSpan(80, 110), CutSpan(150, 160))
        val result = AutoCut(cuts).apply(scene()).getOrFail()
        assertEquals(300L - 50L, result.track("v1")!!.end.value)
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `a cut that starts at a clip start or ends at its end leaves no empty pieces`() {
        val result = AutoCut(listOf(CutSpan(0, 40), CutSpan(150, 200))).apply(scene()).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(300L - 90L, result.track("v1")!!.end.value)
        assertTrue(result.track("v1")!!.clips.all { it.durationFrames > 0 })
    }

    @Test
    fun `a cut covering a whole clip removes it`() {
        val result = AutoCut(listOf(CutSpan(200, 300))).apply(scene()).getOrFail()
        assertEquals(listOf("a"), result.track("v1")!!.clips.map { it.id })
    }

    @Test
    fun `overlays inside a cut are removed like a delete`() {
        val result = AutoCut(listOf(CutSpan(30, 70))).apply(scene()).getOrFail()
        assertEquals(listOf("o2"), result.track("v2")!!.clips.map { it.id })
    }

    @Test
    fun `a span across two clips, with no base or empty is refused`() {
        assertTrue(AutoCut(listOf(CutSpan(190, 210))).apply(scene()).errorOrFail() is EditError.InvalidClip)
        assertTrue(AutoCut(emptyList()).apply(scene()).errorOrFail() is EditError.InvalidClip)
        assertTrue(AutoCut(listOf(CutSpan(0, 10))).apply(Timeline()).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `the whole cut is one undo step`() {
        val before = scene()
        val after = EditHistory(before).execute(AutoCut(listOf(CutSpan(20, 30), CutSpan(80, 110)))).getOrFail()
        assertTrue(after.canUndo)
        assertEquals(before, after.undo().timeline)
    }

    // endregion
}
