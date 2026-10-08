package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewScenesTest {

    private val fps = FrameRate(30, 1)

    private fun asset(id: String, hasVideo: Boolean = true) =
        MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR", hasVideo = hasVideo, hasAudio = true)

    private fun keyOf(id: String): Int = id.last().digitToInt()

    private fun requests(tl: Timeline, frame: Long, vararg assets: MediaAssetDto) =
        previewRequestsAt(tl, assets.toList(), fps, FrameIndex(frame), ::keyOf)

    @Test
    fun `without transitions there is one layer per track and the source frame follows the clip`() {
        val tl = timeline(
            track("v2", clip("top", 50, 100, srcIn = 10, asset = "a2")),
            track("v1", clip("bottom", 0, 200, asset = "a1")),
        )

        val layers = requests(tl, 60, asset("a1"), asset("a2"))

        assertEquals(listOf(1, 2), layers.map { it.assetKey }) // bottom track first
        assertEquals(listOf(60L, 20L), layers.map { it.sourceFrame })
        assertTrue(layers.all { it.transform.opacity == 1.0 && it.title == null })
    }

    @Test
    fun `a fast clip shows source frames at twice the pace and has no playback end`() {
        val tl = TimelineOps.setSpeed(timeline(track("v1", clip("c", 10, 100, srcIn = 20, asset = "a1"))), "c", 2, 1).getOrFail()

        val atStart = requests(tl, 10, asset("a1")).single()
        val later = requests(tl, 30, asset("a1")).single()

        assertEquals(20L, atStart.sourceFrame)
        assertEquals(60L, later.sourceFrame) // 20 frames in at 2x
        assertNull(later.endFrame)
        assertEquals(false, later.reverse)
    }

    @Test
    fun `a reversed clip plays from its last frame down and tells the decoder`() {
        val tl = TimelineOps.setReverse(timeline(track("v1", clip("c", 0, 100, srcIn = 10, asset = "a1"))), "c", true).getOrFail()

        val first = requests(tl, 0, asset("a1")).single()
        val later = requests(tl, 40, asset("a1")).single()

        assertEquals(109L, first.sourceFrame)
        assertEquals(69L, later.sourceFrame)
        assertTrue(first.reverse && later.reverse)
        assertNull(later.endFrame)
    }

    @Test
    fun `a frozen frame stays on one source frame`() {
        val tl = TimelineOps.freezeFrame(timeline(track("v1", clip("c", 0, 100, asset = "a1"))), "v1", FrameIndex(40), 30, "fz", "c2").getOrFail()

        // Frames 40..69 hold source frame 40; the second half then carries on from it.
        assertEquals(setOf(40L), listOf(40L, 41L, 55L, 69L).map { requests(tl, it, asset("a1")).single().sourceFrame }.toSet())
        assertEquals(40L, requests(tl, 70, asset("a1")).single().sourceFrame)
        assertEquals(41L, requests(tl, 71, asset("a1")).single().sourceFrame)
        assertEquals(39L, requests(tl, 39, asset("a1")).single().sourceFrame)
    }

    @Test
    fun `playback stops each video layer at its clip's out point`() {
        val tl = timeline(
            track("v2", clip("top", 50, 100, srcIn = 10, asset = "a2")),
            track("v1", clip("bottom", 0, 200, asset = "a1")),
        )

        val layers = requests(tl, 60, asset("a1"), asset("a2"))

        // Exclusive source frames: srcIn + duration.
        assertEquals(listOf(200L, 110L), layers.map { it.endFrame })
    }

    @Test
    fun `a transition lets the outgoing clip run on to the end of its overlap`() {
        val base = timeline(track("v1", clip("A", 0, 100, asset = "a1"), clip("B", 100, 100, srcIn = 50, asset = "a2")))
        val tl = TimelineOps.addTransition(base, Transition("t", "A", "B", 10)).getOrFail()

        val layers = requests(tl, 100, asset("a1"), asset("a2"))

        assertEquals(105L, layers[0].endFrame)  // 100 frames of A plus the 5 after the cut
        assertEquals(50L - 5 + 100 + 5 + 0, layers[1].endFrame)
    }

    @Test
    fun `a gap on every track has no layers`() {
        val tl = timeline(track("v1", clip("c", 100, 50, asset = "a1")))

        assertTrue(requests(tl, 10, asset("a1")).isEmpty())
    }

    @Test
    fun `a transition shows both clips with the incoming one fading in over the outgoing one`() {
        val base = timeline(track("v1", clip("A", 0, 100, asset = "a1"), clip("B", 100, 100, srcIn = 50, asset = "a2")))
        val tl = TimelineOps.addTransition(base, Transition("t", "A", "B", 10)).getOrFail()

        val layers = requests(tl, 100, asset("a1"), asset("a2"))

        assertEquals(listOf(1, 2), layers.map { it.assetKey })
        assertEquals(1.0, layers[0].transform.opacity, 0.0)
        assertEquals(0.55, layers[1].transform.opacity, 1e-12)
        // The outgoing clip keeps playing past its out point, the incoming one reads media before its in point.
        assertEquals(100L, layers[0].sourceFrame)
        assertEquals(50L, layers[1].sourceFrame)
        assertEquals(1, requests(tl, 94, asset("a1"), asset("a2")).size)
        assertEquals(1, requests(tl, 105, asset("a1"), asset("a2")).size)
    }

    @Test
    fun `cuts of the same media use separate decoder keys during a transition only`() {
        val base = timeline(track("v1", clip("A", 0, 100, asset = "a1"), clip("B", 100, 100, srcIn = 50, asset = "a1")))
        val tl = TimelineOps.addTransition(base, Transition("t", "A", "B", 10)).getOrFail()

        val during = requests(tl, 100, asset("a1"))
        val after = requests(tl, 150, asset("a1"))

        assertEquals(listOf(1, 1 + LANE_STRIDE), during.map { it.assetKey })
        assertEquals(listOf(1 + LANE_STRIDE), after.map { it.assetKey })
    }

    @Test
    fun `titles are layers without media above the video of lower tracks`() {
        val title = Clip("T", null, FrameIndex(10), FrameIndex(0), FrameIndex(60), title = TitleContent("Hi"))
        val tl = timeline(
            track("t1", title, type = TrackType.TITLE),
            track("v1", clip("c", 0, 200, asset = "a1")),
        )

        val layers = requests(tl, 20, asset("a1"))

        assertEquals(2, layers.size)
        assertNull(layers[0].title)
        assertEquals("Hi", layers[1].title?.text)
        assertEquals(0, layers[1].assetKey)
    }

    @Test
    fun `a title alone still shows over a gap`() {
        val title = Clip("T", null, FrameIndex(10), FrameIndex(0), FrameIndex(60), title = TitleContent("Hi"))
        val tl = timeline(track("t1", title, type = TrackType.TITLE), track("v1"))

        assertEquals(1, requests(tl, 20).size)
        assertTrue(requests(tl, 100).isEmpty())
    }

    @Test
    fun `audio only media and unknown media are left out`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "a1"), clip("c2", 100, 100, asset = "missing")),
            track("v2", clip("c3", 0, 100, asset = "a2")),
        )

        assertTrue(requests(tl, 10, asset("a1", hasVideo = false), asset("a2", hasVideo = false)).isEmpty())
        assertTrue(requests(tl, 150, asset("a1")).isEmpty())
    }

    @Test
    fun `transitions between titles fade the text`() {
        fun title(id: String, start: Long, text: String) =
            Clip(id, null, FrameIndex(start), FrameIndex(0), FrameIndex(60), title = TitleContent(text))
        val base = timeline(track("t1", title("T1", 0, "one"), title("T2", 60, "two"), type = TrackType.TITLE))
        val tl = TimelineOps.addTransition(base, Transition("x", "T1", "T2", 20)).getOrFail()

        val layers = requests(tl, 60)

        assertEquals(listOf("one", "two"), layers.map { it.title?.text })
        assertEquals(0.525, layers[1].transform.opacity, 1e-12)
    }
}
