package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import androidx.annotation.StringRes
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleLayer
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle
import com.qtekfun.ultimatevideoeditor.domain.templates.PlaceholderKind
import com.qtekfun.ultimatevideoeditor.engine.preview.ScopeMode
import com.qtekfun.ultimatevideoeditor.engine.still.StickerInfo
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/*
 * The words of the media, caption, title and scope enums of the lower layers in the language in use. Those layers keep their
 * English `label`s (logs, validation messages); the screens show these. Every `when` over an enum is exhaustive, so a new value
 * fails to compile until it has a text.
 */

@StringRes
fun ScopeMode.labelRes(): Int = when (this) {
    ScopeMode.WAVEFORM -> R.string.ed_s3_scope_waveform
    ScopeMode.PARADE -> R.string.ed_s3_scope_parade
    ScopeMode.VECTORSCOPE -> R.string.ed_s3_scope_vectorscope
    ScopeMode.HISTOGRAM -> R.string.ed_s3_scope_histogram
}

@StringRes
fun PlaceholderKind.labelRes(): Int = when (this) {
    PlaceholderKind.VIDEO -> R.string.ed_s3_kind_video
    PlaceholderKind.VIDEO_OR_PHOTO -> R.string.ed_s3_kind_video_or_photo
    PlaceholderKind.PHOTO -> R.string.ed_s3_kind_photo
    PlaceholderKind.AUDIO -> R.string.ed_s3_kind_audio
}

@StringRes
fun MotionPreset.labelRes(): Int = when (this) {
    MotionPreset.NONE -> R.string.ed_s3_motion_none
    MotionPreset.FADE -> R.string.ed_s3_motion_fade
    MotionPreset.SLIDE_LEFT -> R.string.ed_s3_motion_slide_left
    MotionPreset.SLIDE_RIGHT -> R.string.ed_s3_motion_slide_right
    MotionPreset.SLIDE_UP -> R.string.ed_s3_motion_slide_up
    MotionPreset.SLIDE_DOWN -> R.string.ed_s3_motion_slide_down
    MotionPreset.POP -> R.string.ed_s3_motion_pop
}

@StringRes
fun ShapeKind.labelRes(): Int = when (this) {
    ShapeKind.RECT -> R.string.ed_s3_shape_rectangle
    ShapeKind.ROUNDED_RECT -> R.string.ed_s3_shape_rounded_rectangle
    ShapeKind.ELLIPSE -> R.string.ed_s3_shape_ellipse
    ShapeKind.LINE -> R.string.ed_s3_shape_line
}

/** The caption styles are ordinary data with an id; an id this build does not know shows its English label. */
fun CaptionStyle.labelText(): UiText = when (id) {
    "classic" -> UiText.res(R.string.ed_s3_style_classic)
    "bold" -> UiText.res(R.string.ed_s3_style_bold)
    "pop" -> UiText.res(R.string.ed_s3_style_pop)
    "impact" -> UiText.res(R.string.ed_s3_style_impact)
    "karaoke" -> UiText.res(R.string.ed_s3_style_karaoke)
    "wordpop" -> UiText.res(R.string.ed_s3_style_wordpop)
    "typewriter" -> UiText.res(R.string.ed_s3_style_typewriter)
    "bounce" -> UiText.res(R.string.ed_s3_style_bounce)
    else -> UiText.Raw(label)
}

/** The name of a built-in sticker; an id this build does not know shows its English label. */
fun StickerInfo.labelText(): UiText = when (id) {
    "shape:heart" -> UiText.res(R.string.ed_s3_sticker_heart)
    "shape:star", "emoji:⭐" -> UiText.res(R.string.ed_s3_sticker_star)
    "shape:arrow" -> UiText.res(R.string.ed_s3_sticker_arrow)
    "shape:check" -> UiText.res(R.string.ed_s3_sticker_check)
    "shape:burst" -> UiText.res(R.string.ed_s3_sticker_burst)
    "shape:bubble" -> UiText.res(R.string.ed_s3_sticker_speech)
    "shape:ring" -> UiText.res(R.string.ed_s3_sticker_ring)
    "shape:exclaim" -> UiText.res(R.string.ed_s3_sticker_alert)
    "emoji:🔥" -> UiText.res(R.string.ed_s3_sticker_fire)
    "emoji:❤️" -> UiText.res(R.string.ed_s3_sticker_red_heart)
    "emoji:👍" -> UiText.res(R.string.ed_s3_sticker_thumbs_up)
    "emoji:😂" -> UiText.res(R.string.ed_s3_sticker_laughing)
    "emoji:🎉" -> UiText.res(R.string.ed_s3_sticker_party)
    "emoji:💯" -> UiText.res(R.string.ed_s3_sticker_hundred)
    "emoji:😍" -> UiText.res(R.string.ed_s3_sticker_heart_eyes)
    else -> UiText.Raw(label)
}

@StringRes
fun AssetKind.labelRes(): Int = when (this) {
    AssetKind.VIDEO -> R.string.ed_s3_kind_video
    AssetKind.PHOTO -> R.string.ed_s3_kind_photo
    AssetKind.AUDIO -> R.string.ed_s3_kind_audio
}

private const val LAYER_LABEL_CHARS = 24

/** A short name for the layer list ("Text: Hello", "Rounded rectangle", "Sticker"). */
fun TitleLayer.labelText(): UiText = when (this) {
    is TextLayer -> {
        val line = text.lineSequence().firstOrNull().orEmpty().take(LAYER_LABEL_CHARS)
        if (line.isBlank()) UiText.res(R.string.ed_s3_layer_text_empty) else UiText.res(R.string.ed_s3_layer_text, line)
    }
    is ShapeLayer -> UiText.res(kind.labelRes())
    is ImageLayer -> UiText.res(if (kind == StillKind.PHOTO) R.string.ed_s3_kind_photo else R.string.ed_s3_sticker_singular)
}
