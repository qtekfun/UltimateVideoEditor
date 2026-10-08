package com.qtekfun.ultimatevideoeditor.ui.export

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExportPlanTransitionTest {

    private val fps = FrameRate(30, 1)

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    private fun withTransition(a: String, b: String): Timeline {
        val base = timeline(track("v1", clip("A", 0, 100, asset = a), clip("B", 100, 100, srcIn = 50, asset = b)))
        return TimelineOps.addTransition(base, Transition("t", "A", "B", 10)).getOrFail()
    }

    @Test
    fun `a transition overlaps the clips and fades the incoming one in`() {
        val plan = buildExportPlan(withTransition("a", "b"), listOf(asset("a"), asset("b")), fps)!!

        val (outgoing, incoming) = plan.videoClips.sortedBy { it.startFrame }
        assertEquals(0L to 105L, outgoing.startFrame to (outgoing.startFrame + outgoing.durationFrames))
        assertEquals(95L to 200L, incoming.startFrame to (incoming.startFrame + incoming.durationFrames))
        assertEquals(45L, incoming.sourceInFrame)
        assertEquals(0L, outgoing.crossfadeInFrames)
        assertEquals(10L, incoming.crossfadeInFrames)
        assertEquals(outgoing.layer, incoming.layer)
        // The project still ends where the last clip does.
        assertEquals(200L, plan.projectFrames)
    }

    @Test
    fun `cuts of one file in a transition get separate decoder lanes`() {
        val same = buildExportPlan(withTransition("a", "a"), listOf(asset("a")), fps)!!
        val different = buildExportPlan(withTransition("a", "b"), listOf(asset("a"), asset("b")), fps)!!

        assertEquals(listOf(0, 1), same.videoClips.sortedBy { it.startFrame }.map { it.lane })
        assertEquals(listOf(0, 0), different.videoClips.sortedBy { it.startFrame }.map { it.lane })
    }

    @Test
    fun `the audio overlaps and fades with the picture`() {
        val plan = buildExportPlan(withTransition("a", "b"), listOf(asset("a"), asset("b")), fps)!!

        val clips = plan.audio!!.clips.sortedBy { it.startFrame }
        assertEquals(10L, clips[0].fadeOutFrames)
        assertEquals(10L, clips[1].fadeInFrames)
        assertEquals(clips[0].startFrame + clips[0].durationFrames - clips[0].fadeOutFrames, clips[1].startFrame)
    }

    @Test
    fun `titles become clips with a title key and equal texts share a key`() {
        fun title(id: String, start: Long, text: String) =
            Clip(id, null, FrameIndex(start), FrameIndex(0), FrameIndex(30), title = TitleContent(text))
        val tl = timeline(
            track("t1", title("T1", 0, "one"), title("T2", 40, "two"), title("T3", 80, "one"), type = TrackType.TITLE),
            track("v1", clip("c", 0, 120, asset = "a")),
        )

        val plan = buildExportPlan(tl, listOf(asset("a")), fps)!!

        val titles = plan.videoClips.filter { it.titleKey != 0 }.sortedBy { it.startFrame }
        assertEquals(listOf(1, 2, 1), titles.map { it.titleKey })
        assertEquals(mapOf(1 to TitleContent("one"), 2 to TitleContent("two")), plan.titles)
        assertEquals(0, titles.first().layer) // above the video track
        assertEquals(1, plan.videoClips.first { it.titleKey == 0 }.layer)
    }

    @Test
    fun `a project of titles alone can be exported`() {
        val title = Clip("T", null, FrameIndex(0), FrameIndex(0), FrameIndex(60), title = TitleContent("hi"))
        val plan = buildExportPlan(timeline(track("t1", title, type = TrackType.TITLE)), emptyList(), fps)

        assertNotNull(plan)
        assertEquals(60L, plan!!.projectFrames)
        assertNull(plan.audio)
    }
}
