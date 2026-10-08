package com.qtekfun.ultimatevideoeditor.data

import android.content.Context

/** The choices of the New project sheet that are worth remembering for next time. Plain values: no UI types. */
data class SavedProjectChoices(
    val aspectId: String,
    val tierId: String,
    val customWidth: Int,
    val customHeight: Int,
    val customShortSide: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val colorSpaceId: String,
)

/** Remembers the last New project choices on this device (never leaves it). */
interface NewProjectDefaults {
    fun load(): SavedProjectChoices?

    fun save(choices: SavedProjectChoices)
}

class PreferencesNewProjectDefaults(context: Context) : NewProjectDefaults {
    private val prefs = context.getSharedPreferences("new_project", Context.MODE_PRIVATE)

    override fun load(): SavedProjectChoices? {
        val aspect = prefs.getString(KEY_ASPECT, null) ?: return null
        val tier = prefs.getString(KEY_TIER, null) ?: return null
        val colorSpace = prefs.getString(KEY_COLOR, null) ?: return null
        val fpsNum = prefs.getInt(KEY_FPS_NUM, 0)
        val fpsDen = prefs.getInt(KEY_FPS_DEN, 0)
        if (fpsNum <= 0 || fpsDen <= 0) return null
        return SavedProjectChoices(
            aspectId = aspect,
            tierId = tier,
            customWidth = prefs.getInt(KEY_CUSTOM_W, 0),
            customHeight = prefs.getInt(KEY_CUSTOM_H, 0),
            customShortSide = prefs.getInt(KEY_CUSTOM_SHORT, 0),
            fpsNum = fpsNum,
            fpsDen = fpsDen,
            colorSpaceId = colorSpace,
        )
    }

    override fun save(choices: SavedProjectChoices) {
        prefs.edit()
            .putString(KEY_ASPECT, choices.aspectId)
            .putString(KEY_TIER, choices.tierId)
            .putInt(KEY_CUSTOM_W, choices.customWidth)
            .putInt(KEY_CUSTOM_H, choices.customHeight)
            .putInt(KEY_CUSTOM_SHORT, choices.customShortSide)
            .putInt(KEY_FPS_NUM, choices.fpsNum)
            .putInt(KEY_FPS_DEN, choices.fpsDen)
            .putString(KEY_COLOR, choices.colorSpaceId)
            .apply()
    }

    private companion object {
        const val KEY_ASPECT = "aspect"
        const val KEY_TIER = "tier"
        const val KEY_CUSTOM_W = "customWidth"
        const val KEY_CUSTOM_H = "customHeight"
        const val KEY_CUSTOM_SHORT = "customShortSide"
        const val KEY_FPS_NUM = "fpsNum"
        const val KEY_FPS_DEN = "fpsDen"
        const val KEY_COLOR = "colorSpace"
    }
}
