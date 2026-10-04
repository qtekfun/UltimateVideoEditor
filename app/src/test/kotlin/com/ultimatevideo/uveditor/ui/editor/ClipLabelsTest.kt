package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TitleContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipLabelsTest {

    private fun title(text: String) =
        Clip("t", null, FrameIndex(0), FrameIndex(0), FrameIndex(30), title = TitleContent(text))

    private fun sticker(assetId: String?) =
        Clip("s", assetId, FrameIndex(0), FrameIndex(0), FrameIndex(30), still = StillKind.STICKER)

    @Test
    fun `a title shows its text in capitals`() {
        assertEquals("LOWER THIRD", ClipLabels.of(title("Lower third")))
    }

    @Test
    fun `only the first non blank line of a title is used`() {
        assertEquals("FIRST LINE", ClipLabels.of(title("\n  First line\nSecond")))
    }

    @Test
    fun `accents are dropped and unsupported characters skipped`() {
        assertEquals("CAFE NINO", ClipLabels.clean("Café  niño!!"))
        assertEquals("A-B 2", ClipLabels.clean("a-b ★ 2"))
    }

    @Test
    fun `a label is cut at 24 characters without a trailing space`() {
        val label = ClipLabels.clean("This is a really long caption that goes on")
        assertEquals(24, label.length + if (label.endsWith(" ")) 1 else 0)
        assertEquals("THIS IS A REALLY LONG CA", ClipLabels.clean("This is a really long caption that goes on"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAA", ClipLabels.clean("A".repeat(40)))
    }

    @Test
    fun `a title with nothing drawable falls back to TEXT`() {
        assertEquals("TEXT", ClipLabels.of(title("★★★")))
        assertEquals("TEXT", ClipLabels.of(title("")))
    }

    @Test
    fun `a sticker shows its name and an unknown sticker a generic one`() {
        assertEquals("HEART", ClipLabels.of(sticker("shape:heart")))
        assertEquals("FIRE", ClipLabels.of(sticker("emoji:🔥")))
        assertEquals("STICKER", ClipLabels.of(sticker("shape:nope")))
        assertEquals("STICKER", ClipLabels.of(sticker(null)))
    }

    @Test
    fun `media and photo clips get no label`() {
        assertNull(ClipLabels.of(Clip("m", "a", FrameIndex(0), FrameIndex(0), FrameIndex(30))))
        assertNull(ClipLabels.of(Clip("p", "a", FrameIndex(0), FrameIndex(0), FrameIndex(30), still = StillKind.PHOTO)))
    }
}
