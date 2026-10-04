package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.still.StickerIds
import com.ultimatevideo.uveditor.engine.timeline.SnapshotLabel
import java.text.BreakIterator
import java.util.Locale

/**
 * What the native timeline writes on a clip block: the name of a media clip, a title's text, a sticker's name. The canvas
 * draws it from bitmaps made with the system font, so any text works (accents, other scripts, symbols, emoji); it is
 * only tidied up: control characters and runs of blanks become one space, and it is cut, on a character boundary, to
 * [SnapshotLabel.MAX_CHARS] characters and [SnapshotLabel.MAX_BYTES] bytes of UTF-8.
 */
internal object ClipLabels {

    /**
     * The label for [clip], or null when its block needs none. [assetName] is the display name of the clip's media
     * (a file name); without one a media clip shows only its picture or waveform.
     */
    fun of(clip: Clip, assetName: String? = null): String? {
        clip.title?.let { title ->
            val text = clean(title.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            return text.ifEmpty { "Text" }
        }
        if (clip.still == StillKind.STICKER) {
            val name = clip.assetId?.let { id -> (StickerIds.shapes + StickerIds.emoji).firstOrNull { it.id == id }?.label }
            return clean(name.orEmpty()).ifEmpty { "Sticker" }
        }
        return assetName?.let { clean(withoutExtension(it)) }?.takeIf { it.isNotEmpty() }
    }

    /** A file name without its extension (`.mp4`), when what follows the last dot looks like one. */
    fun withoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot - 1 in 1..5 && name.substring(dot + 1).all { it.isLetterOrDigit() }) name.substring(0, dot) else name
    }

    /** [text] as the canvas draws it: tidied and cut as described above, with an ellipsis where it was cut. */
    fun clean(text: String): String {
        val tidy = StringBuilder()
        var lastBlank = true
        for (ch in text) {
            val blank = ch.isWhitespace() || ch.isISOControl()
            when {
                blank -> if (!lastBlank) tidy.append(' ')
                else -> tidy.append(ch)
            }
            lastBlank = blank
        }
        val trimmed = tidy.toString().trimEnd()
        if (trimmed.isEmpty()) return ""
        val clusters = clustersOf(trimmed)
        if (fits(trimmed, clusters.size)) return trimmed
        // Cut at a cluster boundary and mark it, keeping the ellipsis inside the limits.
        var keep = minOf(clusters.size, SnapshotLabel.MAX_CHARS - 1)
        while (keep > 0 && !fits(clusters.take(keep).joinToString("") + ELLIPSIS, keep + 1)) keep--
        return (clusters.take(keep).joinToString("").trimEnd() + ELLIPSIS)
    }

    private fun fits(text: String, characters: Int) =
        characters <= SnapshotLabel.MAX_CHARS && SnapshotLabel.utf8Length(text) <= SnapshotLabel.MAX_BYTES

    /** The user-perceived characters of [text]: an emoji with a skin tone or a joined family stays whole. */
    private fun clustersOf(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }

    private const val ELLIPSIS = "…"
}
