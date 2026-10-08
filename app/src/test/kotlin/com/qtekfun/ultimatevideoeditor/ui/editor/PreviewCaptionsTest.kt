package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLook
import com.qtekfun.ultimatevideoeditor.domain.TitleWord
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCaptionsTest {

    private val fps = FrameRate(30, 1)
    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35))

    private fun requestsAt(title: TitleContent, frame: Long) = previewRequestsAt(
        timeline(track("t1", clip("caption-a", 100, 60, asset = null).copy(title = title), type = TrackType.TITLE)),
        emptyList(),
        fps,
        FrameIndex(frame),
        { 0 },
    )

    @Test
    fun `the layer carries the look of the frame being shown`() {
        val title = CaptionStyle.KARAOKE.titleFor("Say it loud", words)

        assertEquals(0, requestsAt(title, 105).single().title!!.look.activeWord)
        assertEquals(1, requestsAt(title, 115).single().title!!.look.activeWord)
        assertEquals(2, requestsAt(title, 150).single().title!!.look.activeWord)
        // Nothing shows outside the clip.
        assertTrue(requestsAt(title, 99).isEmpty())
        assertTrue(requestsAt(title, 160).isEmpty())
    }

    @Test
    fun `a picture is keyed by what is drawn, so frames of one look share it`() {
        val title = CaptionStyle.KARAOKE.titleFor("Say it loud", words)

        val a = requestsAt(title, 111).single().title!!
        val b = requestsAt(title, 118).single().title!!

        assertEquals(a, b)
    }

    @Test
    fun `a plain title is passed through untouched`() {
        val plain = TitleContent("Hello")

        val layer = requestsAt(plain, 120).single()

        assertEquals(plain, layer.title)
        assertEquals(TitleLook.FULL, layer.title!!.look)
    }

    @Test
    fun `word pop hides the words that have not been spoken yet`() {
        val title = CaptionStyle.WORD_POP.titleFor("Say it loud", words)

        assertEquals(1, requestsAt(title, 101).single().title!!.look.visibleWords)
        assertEquals(2, requestsAt(title, 112).single().title!!.look.visibleWords)
        assertEquals(TitleLook.ALL, requestsAt(title, 130).single().title!!.look.visibleWords)
    }
}
