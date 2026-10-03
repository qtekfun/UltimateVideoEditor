package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTest {

    private val hello = TitleContent("Hello")

    private fun titleClip(id: String, start: Long, len: Long, content: TitleContent = hello) = Clip(
        id = id,
        assetId = null,
        timelineStart = f(start),
        sourceIn = f(0),
        sourceOut = f(len),
        title = content,
    )

    private fun titles(vararg clips: Clip) = timeline(
        track("v1", clip("c1", 0, 100)),
        track("t1", *clips, type = TrackType.TITLE),
    )

    @Test
    fun `overwrite places a title on a title track`() {
        val result = TimelineOps.overwrite(titles(), "t1", titleClip("T1", 20, 40)).getOrFail()

        assertLayout(result, "t1", at("T1", 20, 60))
        assertEquals(hello, result.track("t1")!!.clip("T1")!!.title)
    }

    @Test
    fun `a title cannot go on a video track`() {
        val error = TimelineOps.overwrite(titles(), "v1", titleClip("T1", 200, 40)).errorOrFail()

        assertTrue(error is EditError.InvalidClip)
    }

    @Test
    fun `a media clip cannot go on a title track`() {
        val error = TimelineOps.overwrite(titles(), "t1", clip("x", 0, 10)).errorOrFail()

        assertTrue(error is EditError.InvalidClip)
    }

    @Test
    fun `blank text and absurd sizes are rejected`() {
        val blank = TimelineOps.overwrite(titles(), "t1", titleClip("T1", 0, 10, TitleContent("   "))).errorOrFail()
        val huge = TimelineOps.overwrite(titles(), "t1", titleClip("T1", 0, 10, TitleContent("x", sizeFraction = 0.9))).errorOrFail()
        val tiny = TimelineOps.overwrite(titles(), "t1", titleClip("T1", 0, 10, TitleContent("x", sizeFraction = 0.0))).errorOrFail()

        assertTrue(blank is EditError.InvalidClip)
        assertTrue(huge is EditError.InvalidClip)
        assertTrue(tiny is EditError.InvalidClip)
    }

    @Test
    fun `setTitle replaces the content and keeps placement`() {
        val base = titles(titleClip("T1", 20, 40))
        val changed = TitleContent("Bye", sizeFraction = 0.2, colorArgb = 0xFFFF0000.toInt(), alignment = TitleAlignment.LEFT, bold = true)

        val result = TimelineOps.setTitle(base, "T1", changed).getOrFail()

        assertEquals(changed, result.track("t1")!!.clip("T1")!!.title)
        assertLayout(result, "t1", at("T1", 20, 60))
    }

    @Test
    fun `setTitle rejects media clips and unknown ids and bad content`() {
        val base = titles(titleClip("T1", 20, 40))

        assertEquals(EditError.NotATitle("c1"), TimelineOps.setTitle(base, "c1", hello).errorOrFail())
        assertEquals(EditError.ClipNotFound("nope"), TimelineOps.setTitle(base, "nope", hello).errorOrFail())
        assertTrue(TimelineOps.setTitle(base, "T1", TitleContent("")).errorOrFail() is EditError.InvalidAppearance)
    }

    @Test
    fun `split keeps the text on both halves with source ranges from zero`() {
        val result = TimelineOps.split(titles(titleClip("T1", 10, 60)), "t1", f(30), "T2").getOrFail()

        assertLayout(result, "t1", at("T1", 10, 30), at("T2", 30, 70))
        val right = result.track("t1")!!.clip("T2")!!
        assertEquals(hello, right.title)
        assertEquals(f(0), right.sourceIn)
        assertEquals(40, right.durationFrames)
    }

    @Test
    fun `a title can be trimmed longer than its original length`() {
        val base = titles(titleClip("T1", 10, 20))

        val longer = TimelineOps.trim(base, "T1", TrimEdge.END, f(200), sourceLength = 20).getOrFail()
        val earlier = TimelineOps.trim(longer, "T1", TrimEdge.START, f(0)).getOrFail()

        assertLayout(earlier, "t1", at("T1", 0, 200))
        assertEquals(f(0), earlier.track("t1")!!.clip("T1")!!.sourceIn)
    }

    @Test
    fun `titles move and ripple like clips and collide like clips`() {
        val base = titles(titleClip("T1", 0, 20), titleClip("T2", 30, 20))

        val moved = TimelineOps.move(base, "T2", f(60)).getOrFail()
        assertLayout(moved, "t1", at("T1", 0, 20), at("T2", 60, 80))

        assertTrue(TimelineOps.move(base, "T2", f(10)).errorOrFail() is EditError.Overlap)

        val rippled = TimelineOps.rippleDelete(base, "T1").getOrFail()
        assertLayout(rippled, "t1", at("T2", 10, 30))
    }

    @Test
    fun `transform edits apply to titles`() {
        val base = titles(titleClip("T1", 0, 20))

        val result = TimelineOps.setTransform(base, "T1", ClipTransform(positionY = 300.0, opacity = 0.5)).getOrFail()

        assertEquals(300.0, result.track("t1")!!.clip("T1")!!.transform.positionY, 0.0)
    }

    @Test
    fun `the invariant check flags a mismatched title`() {
        val broken = timeline(track("t1", clip("m", 0, 10), type = TrackType.TITLE))

        assertTrue(broken.invariantViolations().any { "no title" in it })
        assertNull(titles().invariantViolations().firstOrNull())
    }
}
