package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.annotation.StringRes
import com.qtekfun.ultimatevideoeditor.data.ColorSpaceNames
import java.util.Locale

/** A picture size in pixels. [label] is the exact-pixels caption, e.g. "1920 × 1080". */
data class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    /** The reduced aspect ratio, e.g. "16:9" or "9:16". */
    val aspectLabel: String get() = aspectLabelOf(width, height)
}

/** A titled run of presets that share an aspect ratio, shown together in the editor's canvas dialog. */
data class ResolutionGroup(@StringRes val titleRes: Int, val presets: List<ResolutionPreset>)

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** "16:9" for 1920x1080; sizes such as 1080x1350 reduce to "4:5". */
fun aspectLabelOf(width: Int, height: Int): String {
    require(width > 0 && height > 0) { "invalid size ${width}x$height" }
    val divisor = gcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

/** A shape of picture. [custom] means the user types the exact pixels instead. */
data class AspectPreset(val id: String, @StringRes val labelRes: Int, val ratioWidth: Int, val ratioHeight: Int, val custom: Boolean = false)

/** The size of the short side of the picture: 1080p means 1920x1080 landscape and 1080x1920 vertical. */
data class ResolutionTier(val id: String, @StringRes val labelRes: Int, val shortSide: Int, val custom: Boolean = false)

data class FpsPreset(val label: String, val num: Int, val den: Int)

data class ColorSpacePreset(val label: String, val id: String)

/** How the project gets its format: chosen below, or copied from a clip the user picks. */
enum class StartMode { BLANK, MATCH_FIRST_CLIP }

/** One tap that fills the selectors. [matchFirstClip] switches to the "Match first clip" start mode instead of a fixed format. */
data class QuickPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val aspect: AspectPreset,
    val tier: ResolutionTier,
    val fps: FpsPreset,
    val colorSpace: ColorSpacePreset,
    val matchFirstClip: Boolean = false,
)

/** Sizes of the pictures the selectors describe. */
object ProjectSizing {
    const val MIN_SIDE = 128
    const val MAX_SIDE = 8192

    /**
     * The picture whose short side is [shortSide] and whose shape is ratioWidth:ratioHeight, with both sides
     * rounded to an even number of pixels (video encoders need that): 1080 and 16:9 give 1920x1080, 1080 and
     * 4:5 give 1080x1350.
     */
    fun sizeFor(aspect: AspectPreset, shortSide: Int): Pair<Int, Int> {
        val w = aspect.ratioWidth.toLong()
        val h = aspect.ratioHeight.toLong()
        val side = shortSide.toLong()
        return when {
            w == h -> shortSide to shortSide
            w > h -> evenDivision(side * w, h).toInt() to shortSide
            else -> shortSide to evenDivision(side * h, w).toInt()
        }
    }

    /** [numerator] / [denominator] rounded to the nearest even whole number. */
    private fun evenDivision(numerator: Long, denominator: Long): Long = (numerator + denominator) / (2 * denominator) * 2

    /** Why a typed size cannot be used, or null if it is fine. */
    fun problemWith(width: Int, height: Int): UiText? = when {
        width <= 0 || height <= 0 -> UiText.res(R.string.hub_size_enter)
        width % 2 != 0 || height % 2 != 0 -> UiText.res(R.string.hub_size_even)
        width !in MIN_SIDE..MAX_SIDE || height !in MIN_SIDE..MAX_SIDE -> UiText.res(R.string.hub_size_range, MIN_SIDE, MAX_SIDE)
        else -> null
    }

    /** Rounds a measured size to even numbers (a clip with an odd side still makes a valid project). */
    fun toEven(value: Int): Int = (value + 1) / 2 * 2
}

object ProjectPresets {
    val aspects = listOf(
        AspectPreset("16:9", R.string.hub_aspect_16_9, 16, 9),
        AspectPreset("9:16", R.string.hub_aspect_9_16, 9, 16),
        AspectPreset("1:1", R.string.hub_aspect_1_1, 1, 1),
        AspectPreset("4:5", R.string.hub_aspect_4_5, 4, 5),
        AspectPreset("4:3", R.string.hub_aspect_4_3, 4, 3),
        AspectPreset("21:9", R.string.hub_aspect_21_9, 21, 9),
        AspectPreset("custom", R.string.hub_aspect_custom, 0, 0, custom = true),
    )

    val tiers = listOf(
        ResolutionTier("720p", R.string.hub_tier_720, 720),
        ResolutionTier("1080p", R.string.hub_tier_1080, 1080),
        ResolutionTier("1440p", R.string.hub_tier_1440, 1440),
        ResolutionTier("2160p", R.string.hub_tier_2160, 2160),
        ResolutionTier("custom", R.string.hub_tier_custom, 0, custom = true),
    )

    val fps = listOf(
        FpsPreset("23.976", 24000, 1001),
        FpsPreset("24", 24, 1),
        FpsPreset("25", 25, 1),
        FpsPreset("29.97", 30000, 1001),
        FpsPreset("30", 30, 1),
        FpsPreset("50", 50, 1),
        FpsPreset("59.94", 60000, 1001),
        FpsPreset("60", 60, 1),
        FpsPreset("120", 120, 1),
    )

    val colorSpaces = listOf(
        ColorSpacePreset("SDR Rec.709", ColorSpaceNames.SDR),
        ColorSpacePreset("HDR Rec.2020 HLG", ColorSpaceNames.HLG),
    )

    /**
     * Fixed sizes by shape, for the editor's "change canvas" dialog (the New project sheet uses the
     * selectors above instead).
     */
    val resolutions = listOf(
        ResolutionPreset("720p", 1280, 720),
        ResolutionPreset("1080p", 1920, 1080),
        ResolutionPreset("1440p", 2560, 1440),
        ResolutionPreset("4K", 3840, 2160),
        ResolutionPreset("1080×1920 (9:16)", 1080, 1920),
        ResolutionPreset("1080×1080 (1:1)", 1080, 1080),
        ResolutionPreset("720×1280 (9:16)", 720, 1280),
        ResolutionPreset("2160×3840 (9:16)", 2160, 3840),
        ResolutionPreset("1080×1350 (4:5)", 1080, 1350),
    )

    val resolutionGroups = listOf(
        ResolutionGroup(R.string.hub_group_landscape, resolutions.filter { it.aspectLabel == "16:9" }),
        ResolutionGroup(R.string.hub_group_vertical, resolutions.filter { it.aspectLabel == "9:16" }),
        ResolutionGroup(R.string.hub_group_square, resolutions.filter { it.aspectLabel == "1:1" }),
        ResolutionGroup(R.string.hub_group_portrait, resolutions.filter { it.aspectLabel == "4:5" }),
    )

    val defaultAspect = aspects[0]
    val defaultTier = tiers[1]
    val defaultFps = fps[4]
    val defaultColorSpace = colorSpaces[0]

    val customAspect = aspects.last()
    val customTier = tiers.last()

    private fun aspect(id: String) = aspects.first { it.id == id }
    private fun tier(id: String) = tiers.first { it.id == id }
    private fun fps(label: String) = fps.first { it.label == label }

    /** One-tap starting points, in the order shown. */
    val quick = listOf(
        QuickPreset("yt-1080", R.string.hub_quick_yt_1080, aspect("16:9"), tier("1080p"), fps("30"), defaultColorSpace),
        QuickPreset("yt-4k", R.string.hub_quick_yt_4k, aspect("16:9"), tier("2160p"), fps("30"), defaultColorSpace),
        QuickPreset("vertical", R.string.hub_quick_vertical, aspect("9:16"), tier("1080p"), fps("30"), defaultColorSpace),
        QuickPreset("insta-45", R.string.hub_quick_insta_45, aspect("4:5"), tier("1080p"), fps("30"), defaultColorSpace),
        QuickPreset("square", R.string.hub_quick_square, aspect("1:1"), tier("1080p"), fps("30"), defaultColorSpace),
        QuickPreset("cinema", R.string.hub_quick_cinema, aspect("16:9"), tier("1080p"), fps("24"), defaultColorSpace),
        QuickPreset("match", R.string.hub_quick_match, defaultAspect, defaultTier, defaultFps, defaultColorSpace, matchFirstClip = true),
    )

    fun aspectById(id: String): AspectPreset? = aspects.firstOrNull { it.id == id }

    fun tierById(id: String): ResolutionTier? = tiers.firstOrNull { it.id == id }

    /** The listed rate equal to [num]/[den], or a one-off preset for a rate that is not listed. */
    fun fpsFor(num: Int, den: Int): FpsPreset =
        fps.firstOrNull { it.num.toLong() * den == num.toLong() * it.den } ?: FpsPreset(formatFps(num, den), num, den)

    fun colorSpaceFor(id: String?): ColorSpacePreset = when (id) {
        ColorSpaceNames.HLG, ColorSpaceNames.PQ -> colorSpaces[1]
        else -> colorSpaces[0]
    }
}

/** "1920 × 1080". */
fun sizeLabel(width: Int, height: Int): String = "$width × $height"

/** Human-readable frame rate from a rational, e.g. 30000/1001 -> "29.97". */
fun formatFps(num: Int, den: Int): String {
    if (den == 1) return num.toString()
    val rounded = String.format(Locale.ROOT, "%.3f", num.toDouble() / den)
    return rounded.trimEnd('0').trimEnd('.')
}

/** Short colour name for cards and summaries. */
fun colorSpaceShortName(id: String): String = when (id) {
    ColorSpaceNames.SDR -> "SDR"
    ColorSpaceNames.HLG -> "HLG"
    ColorSpaceNames.PQ -> "PQ"
    else -> id
}

/** The short side as shown on a card: "1080p", "4K" or the pixels for odd sizes. */
fun resolutionShortName(width: Int, height: Int): String {
    val short = minOf(width, height)
    return when {
        short == 2160 -> "4K"
        short % 360 == 0 || short == 1080 || short == 1440 -> "${short}p"
        else -> sizeLabel(width, height)
    }
}
