package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.RestyleCaptions
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleWord
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.errorOrFail
import com.ultimatevideo.uveditor.domain.f
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedCaptionStyleTest {

    private val fps = FrameRate(30, 1)
    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35))

    private fun caption(id: String, start: Long, len: Long, title: TitleContent): Clip =
        clip(id, start, len, asset = null).copy(title = title)

    private val karaokeTitle = CaptionStyle.KARAOKE.titleFor("Say it loud", words)

    @Test
    fun `the planner keeps each cue's words timed from the cue start`() {
        val clip = Clip("v", "a", FrameIndex(0), FrameIndex(0), FrameIndex(300))
        val cues = CaptionPlanner.plan(
            listOf(TranscriptWord("Hello", 0, 500), TranscriptWord("world", 600, 1_000), TranscriptWord("Next", 5_000, 5_400)),
            clip,
            fps,
        )

        val first = cues.first()
        assertEquals("Hello world", first.text)
        assertEquals(listOf(TitleWord("Hello", 0, 15), TitleWord("world", 18, 30)), first.words)
        // Every cue's words appear in its text in order, so the animation can place them.
        for (cue in cues) assertTrue(CaptionAnimator.wordRanges(cue.text, cue.words) != null)
        assertEquals(150L, cues[1].start.value)
        assertEquals(0L, cues[1].words.first().startFrame)
    }

    @Test
    fun `animated styles keep the words and static styles drop them`() {
        val cue = CaptionCue(FrameIndex(100), FrameIndex(140), "Say it loud", words)

        val animated = CaptionStyle.KARAOKE.clipsFor(listOf(cue), 1920) { "x" }.single()
        val plain = CaptionStyle.CLASSIC.clipsFor(listOf(cue), 1920) { "x" }.single()

        val animatedTitle = checkNotNull(animated.title)
        val plainTitle = checkNotNull(plain.title)
        assertEquals(words, animatedTitle.words)
        assertEquals(TitleAnimation.KARAOKE, animatedTitle.animation)
        assertEquals(CaptionStyle.KARAOKE.highlightArgb, animatedTitle.highlightArgb)
        assertTrue(plainTitle.words.isEmpty())
        assertEquals(TitleAnimation.NONE, plainTitle.animation)
        assertTrue(animated.id.startsWith(CAPTION_ID_PREFIX))
    }

    @Test
    fun `the new styles are listed after the original four`() {
        assertEquals(listOf("classic", "bold", "pop", "impact", "karaoke", "wordpop", "typewriter", "bounce"), CaptionStyle.ALL.map { it.id })
        assertEquals(TitleAnimation.POP_IN, CaptionStyle.WORD_POP.animation)
        assertEquals(TitleAnimation.TYPEWRITER, CaptionStyle.TYPEWRITER.animation)
        assertEquals(CaptionEntrance.BOUNCE, CaptionStyle.BOUNCE.entrance)
        assertEquals(CaptionStyle.CLASSIC, CaptionStyle.byId("nope"))
    }

    @Test
    fun `a bounce entrance is a short run of eased keyframes ending at the normal pose`() {
        val base = ClipTransform(positionY = 500.0)

        val keys = CaptionEntrance.BOUNCE.keyframes(base, 60)

        assertEquals(listOf(0L, 4L, 7L, 9L), keys.map { it.frame })
        assertTrue(keys.all { it.interpolation == Interpolation.EASE })
        assertEquals(0.5, keys.first().transform.scaleX, 1e-9)
        assertEquals(0.0, keys.first().transform.opacity, 1e-9)
        assertEquals(1.15, keys[1].transform.scaleX, 1e-9) // overshoot
        assertEquals(base, keys.last().transform)
        assertNull(Keyframes.problem(keys, 60))
        // Mid-way through the first leg the phrase is already growing from small and transparent.
        val early = Keyframes.evaluate(keys, 2, base)
        assertTrue(early.scaleX in 0.5..1.15 && early.opacity in 0.0..1.0)
    }

    @Test
    fun `an entrance fits a clip shorter than its steps and always settles`() {
        for (duration in 2L..12L) {
            for (entrance in listOf(CaptionEntrance.SCALE_IN, CaptionEntrance.BOUNCE)) {
                val keys = entrance.keyframes(ClipTransform(), duration)
                assertNull("$entrance over $duration frames", Keyframes.problem(keys, duration))
                assertEquals(ClipTransform(), keys.last().transform)
            }
        }
        assertTrue(CaptionEntrance.BOUNCE.keyframes(ClipTransform(), 1).isEmpty())
        assertTrue(CaptionEntrance.NONE.keyframes(ClipTransform(), 60).isEmpty())
    }

    @Test
    fun `generated bounce captions carry the entrance keyframes`() {
        val cue = CaptionCue(FrameIndex(0), FrameIndex(45), "Hi", listOf(TitleWord("Hi", 0, 10)))

        val clip = CaptionStyle.BOUNCE.clipsFor(listOf(cue), 1920) { "b" }.single()

        assertEquals(4, clip.keyframes.size)
        assertEquals(CaptionStyle.BOUNCE.transformFor(1920), clip.transform)
        assertTrue(CaptionStyle.CLASSIC.clipsFor(listOf(cue), 1920) { "b" }.single().keyframes.isEmpty())
    }

    @Test
    fun `colour overrides change the style without touching the others`() {
        val red = CaptionStyle.KARAOKE.withColors(text = 0xFFFF0000.toInt(), highlight = 0xFF00FF00.toInt())
        val title = red.titleFor("Say it loud", words)

        assertEquals(0xFFFF0000.toInt(), title.colorArgb)
        assertEquals(0xFF00FF00.toInt(), title.highlightArgb)
        assertEquals(CaptionStyle.KARAOKE.animation, red.animation)
        assertTrue(CaptionStyle.KARAOKE.usesHighlight && CaptionStyle.WORD_POP.usesHighlight)
        assertEquals(false, CaptionStyle.TYPEWRITER.usesHighlight)
    }

    @Test
    fun `splitting an animated caption gives each half the words in its own time`() {
        val tl = timeline(track("t1", caption("caption-a", 100, 60, karaokeTitle), type = TrackType.TITLE))

        val split = TimelineOps.split(tl, "t1", f(130), "caption-b").getOrFail()

        val left = split.track("t1")!!.clip("caption-a")!!.title!!
        val right = split.track("t1")!!.clip("caption-b")!!.title!!
        assertEquals(words, left.words)
        assertEquals(listOf(-30L, -20L, -10L), right.words.map { it.startFrame })
        // The frame the cut falls on is the right half's first frame, and both halves agree on the word.
        assertEquals(CaptionAnimator.lookAt(karaokeTitle, 30), CaptionAnimator.lookAt(right, 0))
        assertTrue(split.invariantViolations().isEmpty())
    }

    @Test
    fun `trimming the start of an animated caption moves its words and trimming the end does not`() {
        val tl = timeline(track("t1", caption("caption-a", 100, 60, karaokeTitle), type = TrackType.TITLE))

        val startTrimmed = TimelineOps.trim(tl, "caption-a", TrimEdge.START, f(112)).getOrFail().track("t1")!!.clip("caption-a")!!
        val endTrimmed = TimelineOps.trim(tl, "caption-a", TrimEdge.END, f(140)).getOrFail().track("t1")!!.clip("caption-a")!!

        assertEquals(listOf(-12L, -2L, 8L), startTrimmed.title!!.words.map { it.startFrame })
        assertEquals(words, endTrimmed.title!!.words)
    }

    @Test
    fun `editing the text of an animated caption respaces its words instead of leaving stale timing`() {
        val tl = timeline(track("t1", caption("caption-a", 0, 60, karaokeTitle), type = TrackType.TITLE))

        val edited = TimelineOps.setTitle(tl, "caption-a", karaokeTitle.copy(text = "Totally new words")).getOrFail()
            .track("t1")!!.clip("caption-a")!!.title!!

        assertEquals(listOf("Totally", "new", "words"), edited.words.map { it.text })
        assertEquals(60L, edited.words.last().endFrame)
        assertTrue(CaptionAnimator.isAnimated(edited))
        // A change that is not to the text (a colour) keeps the stored timing.
        val recoloured = TimelineOps.setTitle(tl, "caption-a", karaokeTitle.copy(colorArgb = 0xFF123456.toInt())).getOrFail()
            .track("t1")!!.clip("caption-a")!!.title!!
        assertEquals(words, recoloured.words)
    }

    @Test
    fun `restyle applies a style to every caption as one undo step and leaves other titles alone`() {
        val old = CaptionStyle.CLASSIC
        val first = caption("caption-a", 0, 40, old.titleFor("Say it loud")).copy(transform = old.transformFor(1920))
        val second = caption("caption-b", 60, 40, old.titleFor("Next one", emptyList()))
        val manual = caption("title-1", 120, 40, TitleContent("My title"))
        val tl = timeline(
            track("v1", clip("v", 0, 200)),
            track("t1", first, second, manual, type = TrackType.TITLE),
        )

        val history = EditHistory(tl).execute(RestyleCaptions(CaptionStyle.BOUNCE, 1920)).getOrFail()

        val restyled = history.timeline.track("t1")!!
        for (id in listOf("caption-a", "caption-b")) {
            val c = restyled.clip(id)!!
            assertEquals(CaptionStyle.BOUNCE.colorArgb, c.title!!.colorArgb)
            assertEquals(CaptionStyle.BOUNCE.transformFor(1920), c.transform)
            assertEquals(4, c.keyframes.size)
            // Position and length on the timeline stay.
            assertEquals(tl.track("t1")!!.clip(id)!!.timelineStart, c.timelineStart)
            assertEquals(tl.track("t1")!!.clip(id)!!.durationFrames, c.durationFrames)
        }
        assertEquals(manual, restyled.clip("title-1"))
        assertEquals(tl, history.undo().timeline)
        assertEquals(history.timeline, history.undo().redo().timeline)
    }

    @Test
    fun `restyling to an animated style gives captions without word timing evenly spaced words`() {
        val plain = caption("caption-a", 0, 60, CaptionStyle.CLASSIC.titleFor("Say it loud"))
        val tl = timeline(track("t1", plain, type = TrackType.TITLE))

        val result = RestyleCaptions(CaptionStyle.KARAOKE, 1080).apply(tl).getOrFail().track("t1")!!.clip("caption-a")!!.title!!

        assertEquals(TitleAnimation.KARAOKE, result.animation)
        assertEquals(listOf("Say", "it", "loud"), result.words.map { it.text })
        assertEquals(60L, result.words.last().endFrame)
        assertTrue(CaptionAnimator.isAnimated(result))
    }

    @Test
    fun `restyling keeps real word timing and drops it for a static style`() {
        val timed = caption("caption-a", 0, 60, karaokeTitle)
        val tl = timeline(track("t1", timed, type = TrackType.TITLE))

        val toPop = RestyleCaptions(CaptionStyle.WORD_POP, 1080).apply(tl).getOrFail().track("t1")!!.clip("caption-a")!!.title!!
        val toClassic = RestyleCaptions(CaptionStyle.CLASSIC, 1080).apply(tl).getOrFail().track("t1")!!.clip("caption-a")!!.title!!

        assertEquals(words, toPop.words)
        assertEquals(TitleAnimation.POP_IN, toPop.animation)
        assertTrue(toClassic.words.isEmpty())
        assertEquals(TitleAnimation.NONE, toClassic.animation)
    }

    @Test
    fun `restyling a timeline without captions fails and counts them`() {
        val tl: Timeline = timeline(track("v1", clip("v", 0, 100)), track("t1", caption("title-1", 0, 40, TitleContent("Hi")), type = TrackType.TITLE))

        assertTrue(RestyleCaptions(CaptionStyle.POP, 1080).apply(tl).errorOrFail() is EditError.InvalidClip)
        assertEquals(0, tl.captionCount())
        val withCaptions = timeline(track("t1", caption("caption-a", 0, 40, CaptionStyle.POP.titleFor("Hi")), type = TrackType.TITLE))
        assertEquals(1, withCaptions.captionCount())
    }
}
