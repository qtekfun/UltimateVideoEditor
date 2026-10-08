package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.engine.timeline.SnapshotLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipLabelsTest {

    private fun title(text: String) =
        Clip("t", null, FrameIndex(0), FrameIndex(0), FrameIndex(30), title = TitleContent(text))

    private fun sticker(assetId: String?) =
        Clip("s", assetId, FrameIndex(0), FrameIndex(0), FrameIndex(30), still = StillKind.STICKER)

    @Test
    fun `a title shows its text as typed`() {
        assertEquals("Lower third", ClipLabels.of(title("Lower third")))
    }

    @Test
    fun `only the first non blank line of a title is used`() {
        assertEquals("First line", ClipLabels.of(title("\n  First line\nSecond")))
    }

    @Test
    fun `accents symbols and other scripts are kept`() {
        assertEquals("Café niño ★", ClipLabels.clean("Café  niño ★"))
        assertEquals("日本語のタイトル", ClipLabels.clean("日本語のタイトル"))
        assertEquals("Fire 🔥 and ☕", ClipLabels.clean("Fire 🔥 and ☕"))
    }

    @Test
    fun `blanks and control characters collapse to single spaces`() {
        assertEquals("a b c", ClipLabels.clean("  a \t b\u0000\u0007 c  "))
        assertEquals("", ClipLabels.clean(" \n\t "))
    }

    @Test
    fun `a long label is cut at 32 characters and marked`() {
        val cut = ClipLabels.clean("This is a really long caption that goes on and on")
        assertEquals("This is a really long caption t…", cut)
        assertEquals(SnapshotLabel.MAX_CHARS, cut.length)
        assertEquals("A".repeat(31) + "…", ClipLabels.clean("A".repeat(40)))
        assertEquals("A".repeat(32), ClipLabels.clean("A".repeat(32))) // exactly the limit is not cut
    }

    @Test
    fun `a cut never splits an emoji and always fits the wire limit`() {
        // A family emoji is one character joined from several code points; 20 of them is far beyond 96 bytes.
        val family = "👨‍👩‍👧"
        val cut = ClipLabels.clean(family.repeat(20))
        assertTrue(cut.endsWith("…"))
        assertTrue(SnapshotLabel.utf8Length(cut) <= SnapshotLabel.MAX_BYTES)
        val body = cut.removeSuffix("…")
        assertEquals(family.repeat(body.length / family.length), body) // whole emoji only
        SnapshotLabel(1, cut) // accepted by the snapshot
        // Three-byte characters: 32 of them are exactly 96 bytes.
        SnapshotLabel(1, ClipLabels.clean("語".repeat(40)))
    }

    @Test
    fun `a title with nothing in it falls back to Text`() {
        assertEquals("Text", ClipLabels.of(title("")))
        assertEquals("Text", ClipLabels.of(title("  \n ")))
    }

    @Test
    fun `a sticker shows its name and an unknown sticker a generic one`() {
        assertEquals("Heart", ClipLabels.of(sticker("shape:heart")))
        assertEquals("Fire", ClipLabels.of(sticker("emoji:🔥")))
        assertEquals("Sticker", ClipLabels.of(sticker("shape:nope")))
        assertEquals("Sticker", ClipLabels.of(sticker(null)))
    }

    @Test
    fun `a media clip shows the name of its file without the extension`() {
        val media = Clip("m", "a", FrameIndex(0), FrameIndex(0), FrameIndex(30))
        assertEquals("Holiday clip", ClipLabels.of(media, "Holiday clip.mp4"))
        assertEquals("v.2024.final", ClipLabels.of(media, "v.2024.final.mov"))
        assertEquals("no extension", ClipLabels.of(media, "no extension"))
        assertEquals("a.very-long-extension", ClipLabels.of(media, "a.very-long-extension"))
        assertNull(ClipLabels.of(media, null))
        assertNull(ClipLabels.of(media, "  "))
        assertNull(ClipLabels.of(media)) // no name known: only the picture or the waveform
    }

    @Test
    fun `a photo shows its name too`() {
        val photo = Clip("p", "a", FrameIndex(0), FrameIndex(0), FrameIndex(30), still = StillKind.PHOTO)
        assertEquals("Beach", ClipLabels.of(photo, "Beach.jpg"))
        assertNull(ClipLabels.of(photo))
    }
}
