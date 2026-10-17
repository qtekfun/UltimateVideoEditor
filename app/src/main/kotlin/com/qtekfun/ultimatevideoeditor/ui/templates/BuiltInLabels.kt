package com.qtekfun.ultimatevideoeditor.ui.templates

import com.qtekfun.ultimatevideoeditor.domain.templates.PlaceholderKind
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.templates.Placeholder
import com.qtekfun.ultimatevideoeditor.domain.templates.ProjectTemplate
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * The words the screens show for the built-in templates. The domain objects keep their English names and sample text, because a
 * saved preset or template keeps the words it was made with; a built-in one is worded here, by id, in the language in use. Anything
 * that is not built in (the person's own presets and templates) is shown as it was written.
 */
/** The thing a slot of this kind takes, as it reads in "needs ...". */
internal fun PlaceholderKind.needRes(): Int = when (this) {
    PlaceholderKind.VIDEO -> R.string.ti_need_video
    PlaceholderKind.VIDEO_OR_PHOTO -> R.string.ti_need_video_or_photo
    PlaceholderKind.PHOTO -> R.string.ti_need_photo
    PlaceholderKind.AUDIO -> R.string.ti_need_audio
}

private val textTemplateNames = mapOf(
    "lower-third" to R.string.tt_lower_third,
    "pop-title" to R.string.tt_pop_title,
    "slide-headline" to R.string.tt_slide_headline,
    "subtitle-bar" to R.string.tt_subtitle_bar,
)

private val textTemplateSamples = mapOf(
    "lower-third" to R.string.tt_lower_third_text,
    "pop-title" to R.string.tt_pop_title_text,
    "slide-headline" to R.string.tt_slide_headline_text,
    "subtitle-bar" to R.string.tt_subtitle_bar_text,
)

internal fun TextTemplate.nameText(): UiText = textTemplateNames[id]?.takeIf { builtIn }?.let { UiText.res(it) } ?: UiText.Raw(name)

/** The sample text the template puts in its title when the person typed nothing. */
internal fun TextTemplate.sampleText(): UiText = textTemplateSamples[id]?.takeIf { builtIn }?.let { UiText.res(it) } ?: UiText.Raw(defaultText)

private val projectTemplateNames = mapOf(
    "builtin-vertical-montage" to (R.string.bt_vertical_montage to R.string.bt_vertical_montage_desc),
    "builtin-intro-outro" to (R.string.bt_intro_outro to R.string.bt_intro_outro_desc),
    "builtin-lower-thirds" to (R.string.bt_lower_thirds to R.string.bt_lower_thirds_desc),
)

internal fun ProjectTemplate.nameText(): UiText = projectTemplateNames[id]?.let { UiText.res(it.first) } ?: UiText.Raw(name)

internal fun ProjectTemplate.descriptionText(): UiText = projectTemplateNames[id]?.let { UiText.res(it.second) } ?: UiText.Raw(description)

/** The name of a slot of [template]: worded here for the built-in templates, as written for the person's own. */
internal fun Placeholder.nameText(template: ProjectTemplate): UiText {
    if (template.id !in projectTemplateNames) return UiText.Raw(name)
    val number = Regex("clip([0-9]+)").matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        number != null -> UiText.res(R.string.bt_slot_clip, number)
        id == "music" -> UiText.res(R.string.bt_slot_music)
        id == "intro" -> UiText.res(R.string.bt_slot_intro)
        id == "outro" -> UiText.res(R.string.bt_slot_outro)
        id == "main" || id == "clip" -> UiText.res(R.string.bt_slot_main)
        else -> UiText.Raw(name)
    }
}
