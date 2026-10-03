package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.RenderClip
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLook
import com.ultimatevideo.uveditor.domain.TitleWord
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.captions.CaptionAnimator
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanCaptionsTest {

    private val fps = FrameRate(30, 1)
    private val asset = MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)
    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35))

    private fun planWith(title: TitleContent, start: Long = 100, length: Long = 60) = buildExportPlan(
        timeline(
            track("t1", clip("caption-a", start, length, asset = null).copy(title = title), type = TrackType.TITLE),
            track("v1", clip("v", 0, 300)),
        ),
        listOf(asset),
        fps,
    )!!

    @Test
    fun `a plain title is one spec`() {
        val plan = planWith(TitleContent("Hello"))

        val specs = plan.videoClips.filter { it.titleKey != 0 }
        assertEquals(1, specs.size)
        assertEquals(100L, specs.single().startFrame)
        assertEquals(60L, specs.single().durationFrames)
        assertEquals(1, plan.titles.size)
    }

    @Test
    fun `a karaoke caption is one spec per highlighted word, back to back over the whole clip`() {
        val plan = planWith(CaptionStyle.KARAOKE.titleFor("Say it loud", words))

        val specs = plan.videoClips.filter { it.titleKey != 0 }

        assertEquals(listOf(100L, 110L, 120L), specs.map { it.startFrame })
        assertEquals(listOf(10L, 10L, 40L), specs.map { it.durationFrames })
        assertEquals(60L, specs.sumOf { it.durationFrames })
        // Each spec draws its own picture: the word that is lit moves along.
        assertEquals(listOf(0, 1, 2), specs.map { plan.titles.getValue(it.titleKey).look.activeWord })
        assertEquals(3, plan.titles.size)
        // The position keyframes, if any, count from the clip's own start whichever piece they sit in.
        assertTrue(specs.all { it.keyframeOriginFrame == 100L })
    }

    @Test
    fun `a typewriter caption changes picture as letters appear and ends on the full text`() {
        val plan = planWith(CaptionStyle.TYPEWRITER.titleFor("Say it loud", words), length = 60)

        val specs = plan.videoClips.filter { it.titleKey != 0 }

        assertTrue(specs.size > 3)
        assertEquals(60L, specs.sumOf { it.durationFrames })
        assertTrue(specs.zipWithNext().all { (a, b) -> a.startFrame + a.durationFrames == b.startFrame })
        assertEquals(TitleLook.ALL, plan.titles.getValue(specs.last().titleKey).look.visibleChars)
    }

    @Test
    fun `pictures that look the same share one image even when they come from different timings`() {
        val plan = planWith(CaptionStyle.WORD_POP.titleFor("Say it loud", words))

        val keys = plan.videoClips.filter { it.titleKey != 0 }.map { it.titleKey }

        assertEquals(keys.toSet().size, plan.titles.size)
        // The word-pop settle frames repeat nothing, so every spec has its own key here.
        assertTrue(plan.titles.values.all { it.words.all { w -> w.startFrame == 0L && w.endFrame == 0L } })
    }

    @Test
    fun `an animated title whose text no longer matches its words stays one spec`() {
        val stale = CaptionStyle.KARAOKE.titleFor("Say it loud", words).copy(text = "Completely different")

        val specs = planWith(stale).videoClips.filter { it.titleKey != 0 }

        assertEquals(1, specs.size)
        assertEquals(60L, specs.single().durationFrames)
    }

    @Test
    fun `an incoming fade covers the pieces it overlaps so it is not restarted halfway`() {
        val clip = RenderClip(
            clipId = "c",
            trackId = "t",
            kind = RenderKind.TITLE,
            layer = 0,
            lane = 0,
            assetId = null,
            title = TitleContent("Say it loud", words = words, animation = TitleAnimation.KARAOKE),
            startFrame = 100,
            durationFrames = 60,
            sourceInFrame = 0,
            transform = ClipTransform(),
            gainDb = 0.0,
            crossfadeInFrames = 12,
            crossfadeOutFrames = 0,
        )

        val parts = clip.titleParts(clip.title!!)

        // Pieces would start at 100, 110 and 120; the fade runs to frame 112, so the first two merge.
        assertEquals(listOf(100L to 20L, 120L to 40L), parts.map { it.start to it.duration })
        assertEquals(listOf(12L, 0L), parts.map { it.crossfadeIn })
        assertEquals(0, parts.first().content.look.activeWord)
        assertEquals(CaptionAnimator.lookAt(checkNotNull(clip.title), 40), parts.last().content.look)
    }
}
