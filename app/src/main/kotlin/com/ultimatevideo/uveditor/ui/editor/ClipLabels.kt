package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.still.StickerIds
import com.ultimatevideo.uveditor.engine.timeline.SnapshotLabel
import java.text.Normalizer
import java.util.Locale

/**
 * What the native timeline writes on a clip block: a title's text or a sticker's name. The canvas has a tiny
 * 3x5 pixel font, so the text is reduced to capital ASCII letters, digits, spaces and '-' (accents are dropped,
 * other characters are skipped) and cut to [SnapshotLabel.MAX_CHARS].
 */
internal object ClipLabels {

    private val combiningMarks = Regex("\\p{M}+")

    /** The label for [clip], or null when its block needs none (media clips show their picture or waveform). */
    fun of(clip: Clip): String? {
        clip.title?.let { title ->
            val text = clean(title.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            return text.ifEmpty { "TEXT" }
        }
        if (clip.still == StillKind.STICKER) {
            val name = clip.assetId?.let { id -> (StickerIds.shapes + StickerIds.emoji).firstOrNull { it.id == id }?.label }
            return clean(name.orEmpty()).ifEmpty { "STICKER" }
        }
        return null
    }

    /** [text] as the canvas can draw it: upper case, accents removed, only A-Z 0-9 space and '-', at most 24 characters. */
    fun clean(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "")
        val out = StringBuilder()
        for (ch in plain.uppercase(Locale.ROOT)) {
            val drawable = ch in 'A'..'Z' || ch in '0'..'9' || ch == '-'
            when {
                drawable -> out.append(ch)
                ch.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            }
            if (out.length >= SnapshotLabel.MAX_CHARS) break
        }
        return out.toString().trimEnd()
    }
}
