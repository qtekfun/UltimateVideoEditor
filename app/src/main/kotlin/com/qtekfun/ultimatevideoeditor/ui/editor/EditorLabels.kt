package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.BlendMode
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.FadeShape
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.PoseParams
import com.qtekfun.ultimatevideoeditor.domain.StabCrop
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.TransitionDirection
import com.qtekfun.ultimatevideoeditor.domain.TransitionType
import com.qtekfun.ultimatevideoeditor.domain.VoicePreset
import com.qtekfun.ultimatevideoeditor.domain.AudioRole

/*
 * The words of the domain's enums and parameter names in the language in use. The domain keeps its English names (they also appear
 * in validation messages and logs); the screens show these. Every `when` is exhaustive, so a new value fails to compile until it
 * has a text.
 */

@StringRes
fun TransitionType.labelRes(): Int = when (this) {
    TransitionType.CROSSFADE -> R.string.ed_2b_l_crossfade
    TransitionType.SLIDE -> R.string.ed_2b_l_slide
    TransitionType.PUSH -> R.string.ed_2b_l_push
    TransitionType.ZOOM -> R.string.ed_2a_zoom
    TransitionType.SPIN -> R.string.ed_2b_l_spin
    TransitionType.GLITCH -> R.string.ed_2b_l_glitch
    TransitionType.WIPE -> R.string.ed_2b_l_wipe
    TransitionType.WHIP_PAN -> R.string.ed_2b_l_whip_pan
    TransitionType.LIGHT_LEAK -> R.string.ed_2b_l_light_leak
}

@StringRes
fun TransitionDirection.labelRes(): Int = when (this) {
    TransitionDirection.LEFT -> R.string.ed_2a_dock_left
    TransitionDirection.RIGHT -> R.string.ed_2a_dock_right
    TransitionDirection.UP -> R.string.ed_2b_up
    TransitionDirection.DOWN -> R.string.ed_2b_down
}

@StringRes
fun FadeShape.labelRes(): Int = when (this) {
    FadeShape.EQUAL_POWER -> R.string.ed_2b_l_equal_power
    FadeShape.LINEAR -> R.string.ed_2b_l_linear
    FadeShape.LOGARITHMIC -> R.string.ed_2b_l_logarithmic
}

@StringRes
fun VoicePreset.labelRes(): Int = when (this) {
    VoicePreset.PITCH_FORMANT -> R.string.ed_2b_l_pitch_and_formant
    VoicePreset.CHIPMUNK -> R.string.ed_2b_l_chipmunk
    VoicePreset.DEEP -> R.string.ed_2b_l_deep
    VoicePreset.ROBOT -> R.string.ed_2b_l_robot
    VoicePreset.WHISPER -> R.string.ed_2b_l_whisper
    VoicePreset.RADIO -> R.string.ed_2b_l_radio
    VoicePreset.ECHO -> R.string.ed_2b_l_echo
    VoicePreset.REVERB -> R.string.ed_2b_l_reverb
    VoicePreset.MEGAPHONE -> R.string.ed_2b_l_megaphone
}

@StringRes
fun StabCrop.labelRes(): Int = when (this) {
    StabCrop.TIGHT -> R.string.ed_2b_l_tight
    StabCrop.MEDIUM -> R.string.ed_2a_lane_medium
    StabCrop.FULL -> R.string.ed_2b_l_full
}

@StringRes
fun EffectType.labelRes(): Int = when (this) {
    EffectType.BRIGHTNESS -> R.string.ed_2b_l_brightness
    EffectType.CONTRAST -> R.string.ed_2b_l_contrast
    EffectType.SATURATION -> R.string.ed_2b_l_saturation
    EffectType.EXPOSURE -> R.string.ed_2b_l_exposure
    EffectType.TEMPERATURE -> R.string.ed_2b_l_temperature
    EffectType.TINT -> R.string.ed_2b_l_tint
    EffectType.BLUR -> R.string.ed_2b_l_blur
    EffectType.SHARPEN -> R.string.ed_2b_l_sharpen
    EffectType.VIGNETTE -> R.string.ed_2b_l_vignette
    EffectType.GRAYSCALE -> R.string.ed_2b_l_grayscale
    EffectType.SEPIA -> R.string.ed_2b_l_sepia
    EffectType.CHROMA_KEY -> R.string.ed_2b_l_chroma_key
    EffectType.LUT -> R.string.ed_2b_l_lut
    EffectType.COLOR_GRADE -> R.string.ed_2b_l_colour_grade
    EffectType.DENOISE -> R.string.ed_2b_l_noise_reduction
    EffectType.DEFLICKER -> R.string.ed_2b_l_flicker_removal
    EffectType.QUALIFIER -> R.string.ed_2b_l_hsl_qualifier
}

@StringRes
fun PoseParams.labelRes(): Int = when (this) {
    PoseParams.POSITION_X -> R.string.ed_2b_position_x
    PoseParams.POSITION_Y -> R.string.ed_2b_position_y
    PoseParams.SCALE_X -> R.string.ed_2b_l_scale_x
    PoseParams.SCALE_Y -> R.string.ed_2b_l_scale_y
    PoseParams.ROTATION -> R.string.ed_2b_rotation
    PoseParams.OPACITY -> R.string.ed_2b_opacity
}

@StringRes
fun Interpolation.labelRes(): Int = when (this) {
    Interpolation.LINEAR -> R.string.ed_2b_l_linear
    Interpolation.EASE -> R.string.ed_2b_ease
    Interpolation.HOLD -> R.string.ed_2b_l_hold
    Interpolation.BEZIER -> R.string.ed_2b_l_bezier
}

@StringRes
fun AudioRole.labelRes(): Int = when (this) {
    AudioRole.NORMAL -> R.string.ed_2b_l_normal
    AudioRole.VOICE -> R.string.ed_2b_l_voice
    AudioRole.MUSIC -> R.string.ed_2b_l_music
}

@StringRes
fun SpeedRampShape.labelRes(): Int = when (this) {
    SpeedRampShape.NONE -> R.string.ed_2b_l_none
    SpeedRampShape.EASE_IN -> R.string.ed_2b_l_ease_in
    SpeedRampShape.EASE_OUT -> R.string.ed_2b_l_ease_out
    SpeedRampShape.EASE_IN_SMOOTH -> R.string.ed_2b_l_ease_in_round
    SpeedRampShape.EASE_OUT_SMOOTH -> R.string.ed_2b_l_ease_out_round
    SpeedRampShape.BELL -> R.string.ed_2b_l_bell
    SpeedRampShape.MONTAGE -> R.string.ed_2b_l_montage
    SpeedRampShape.HERO -> R.string.ed_2b_l_hero
    SpeedRampShape.BULLET -> R.string.ed_2b_l_bullet_time
}

/** The resource of a parameter name of the domain, or null for a name this table does not know. */
@StringRes
fun effectParamNameRes(name: String): Int? = when (name) {
        "Amount" -> R.string.ed_2b_l_amount
        "Stops" -> R.string.ed_2b_l_stops
        "Warmth" -> R.string.ed_2b_l_warmth
        "Radius" -> R.string.ed_2b_l_radius
        "Softness" -> R.string.ed_2b_l_softness
        "Intensity" -> R.string.ed_2b_l_intensity
        "Strength" -> R.string.ed_2b_strength
        "Temporal" -> R.string.ed_2b_l_temporal
        "Contrast" -> R.string.ed_2b_l_contrast
        "Pivot" -> R.string.ed_2b_l_pivot
        "Saturation" -> R.string.ed_2b_l_saturation
        "Vibrance" -> R.string.ed_2b_l_vibrance
        "Temperature" -> R.string.ed_2b_l_temperature
        "Tint" -> R.string.ed_2b_l_tint
        "Key red" -> R.string.ed_2b_l_key_red
        "Key green" -> R.string.ed_2b_l_key_green
        "Key blue" -> R.string.ed_2b_l_key_blue
        "Similarity" -> R.string.ed_2b_l_similarity
        "Smoothness" -> R.string.ed_2b_l_smoothness
        "Spill" -> R.string.ed_2b_l_spill
        "LUT" -> R.string.ed_2b_l_lut
        "Lift red" -> R.string.ed_2b_l_lift_red
        "Lift green" -> R.string.ed_2b_l_lift_green
        "Lift blue" -> R.string.ed_2b_l_lift_blue
        "Lift master" -> R.string.ed_2b_l_lift_master
        "Gamma red" -> R.string.ed_2b_l_gamma_red
        "Gamma green" -> R.string.ed_2b_l_gamma_green
        "Gamma blue" -> R.string.ed_2b_l_gamma_blue
        "Gamma master" -> R.string.ed_2b_l_gamma_master
        "Gain red" -> R.string.ed_2b_l_gain_red
        "Gain green" -> R.string.ed_2b_l_gain_green
        "Gain blue" -> R.string.ed_2b_l_gain_blue
        "Gain master" -> R.string.ed_2b_l_gain_master
        "Offset red" -> R.string.ed_2b_l_offset_red
        "Offset green" -> R.string.ed_2b_l_offset_green
        "Offset blue" -> R.string.ed_2b_l_offset_blue
        "Hue" -> R.string.ed_2b_l_hue
        "Hue width" -> R.string.ed_2b_l_hue_width
        "Hue softness" -> R.string.ed_2b_l_hue_softness
        "Saturation min" -> R.string.ed_2b_l_saturation_min
        "Saturation max" -> R.string.ed_2b_l_saturation_max
        "Saturation softness" -> R.string.ed_2b_l_saturation_softness
        "Luma min" -> R.string.ed_2b_l_luma_min
        "Luma max" -> R.string.ed_2b_l_luma_max
        "Luma softness" -> R.string.ed_2b_l_luma_softness
        "Invert" -> R.string.ed_2b_invert
        "Show matte" -> R.string.ed_2b_show_matte
        "Hue shift" -> R.string.ed_2b_l_hue_shift
        "Saturation gain" -> R.string.ed_2b_l_saturation_gain
        "Lightness" -> R.string.ed_2b_l_lightness
        else -> null
    }

/** The resource of a parameter name of the domain, or null for a name this table does not know. */
@StringRes
fun voiceSliderNameRes(name: String): Int? = when (name) {
        "Pitch" -> R.string.ed_2b_l_pitch
        "Formant" -> R.string.ed_2b_l_formant
        "Metal" -> R.string.ed_2b_l_metal
        "Buzz" -> R.string.ed_2b_l_buzz
        "Amount" -> R.string.ed_2b_l_amount
        "Drive" -> R.string.ed_2b_l_drive
        "Narrow" -> R.string.ed_2b_l_narrow
        "Delay" -> R.string.ed_2b_l_delay
        "Repeats" -> R.string.ed_2b_l_repeats
        "Mix" -> R.string.ed_2b_l_mix
        "Size" -> R.string.hub_sort_size
        "Damping" -> R.string.ed_2b_l_damping
        "Tone" -> R.string.ed_2b_l_tone
        else -> null
    }

@Composable
fun effectParamName(name: String): String = effectParamNameRes(name)?.let { stringResource(it) } ?: name

@Composable
fun voiceSliderName(name: String): String = voiceSliderNameRes(name)?.let { stringResource(it) } ?: name

/**
 * The name of an animatable parameter of [clip] for the keyframe lanes: the pose parameters by their own names, the others as
 * "effect parameter" ("Brightness amount"), "voice slider" and the audio parameters.
 */
@Composable
fun paramLabel(clip: Clip, id: String, fallback: String): String {
    PoseParams.byId(id)?.let { return stringResource(it.labelRes()) }
    ParamIds.parseFx(id)?.let { (effectId, index) ->
        val effect = clip.fx.effect(effectId) ?: return fallback
        val param = effect.type.params.getOrNull(index) ?: return fallback
        return stringResource(R.string.ed_2b_param_of, stringResource(effect.type.labelRes()), effectParamName(param.name).lowercase())
    }
    ParamIds.parseVoice(id)?.let { index ->
        val voice = clip.audio.voice ?: return fallback
        val slider = voice.preset.sliders.getOrNull(index) ?: return fallback
        return stringResource(R.string.ed_2b_param_of, stringResource(voice.preset.labelRes()), voiceSliderName(slider.name).lowercase())
    }
    ParamIds.parseEqBand(id)?.let { return stringResource(R.string.ed_2b_eq_band_gain, it + 1) }
    return when (id) {
        ParamIds.GAIN_DB -> stringResource(R.string.ed_2b_volume)
        ParamIds.PAN -> stringResource(R.string.ed_2b_pan)
        else -> fallback
    }
}

@StringRes
fun BlendMode.labelRes(): Int = when (this) {
    BlendMode.NORMAL -> R.string.ed_2b_l_normal
    BlendMode.ADD -> R.string.ed_2b_add
    BlendMode.MULTIPLY -> R.string.ed_2b_blend_multiply
    BlendMode.SCREEN -> R.string.ed_2b_blend_screen
    BlendMode.OVERLAY -> R.string.ed_2b_blend_overlay
}

/** The kind of a track as a word inside a sentence ("a video track"), lower case. */
@StringRes
fun TrackType.nameRes(): Int = when (this) {
    TrackType.VIDEO -> R.string.ed_vm_track_type_video
    TrackType.AUDIO -> R.string.ed_vm_track_type_audio
    TrackType.TITLE -> R.string.ed_vm_track_type_title
}
