package com.qtekfun.ultimatevideoeditor.ui.onboarding

import android.content.Context

/** Remembers whether the first-run tips were shown. Local preferences only. */
interface OnboardingStore {
    fun seen(): Boolean

    fun markSeen()

    /** Shows the tips again the next time the hub opens (About -> Show tips again). */
    fun reset()
}

class PreferencesOnboardingStore(context: Context) : OnboardingStore {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    override fun seen(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    override fun markSeen() {
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }

    override fun reset() {
        prefs.edit().putBoolean(KEY_SEEN, false).apply()
    }

    private companion object {
        const val KEY_SEEN = "tipsSeen"
    }
}

/** One dismissible tip card. */
data class Tip(val title: String, val body: String)

/** The three first-run tips, in order: they point at the key actions (import, split, export, layout, help). */
object Tips {
    val all: List<Tip> = listOf(
        Tip(
            title = "Bring in your clips",
            body = "Create a project, then import clips with + or drag them from the media tray onto the timeline. " +
                "Your files are never copied: the project only remembers where they are.",
        ),
        Tip(
            title = "Cut and arrange",
            body = "Tap a clip to select it, move the playhead and use Split (the scissors). The bottom track is the " +
                "guide and closes gaps by itself; tracks above it are free. The layout button lets you resize " +
                "the preview, timeline and panels.",
        ),
        Tip(
            title = "Export and get help",
            body = "Tap the arrow at the top right to export an MP4. Long-press any icon to see its name, or tap the ? at the top of the editor for a guide to every symbol. " +
                "About in the hub menu has the guide, privacy and these tips again.",
        ),
    )
}

/** Which tip is on screen; pure so it can be tested. */
data class OnboardingState(val index: Int = 0, val count: Int = Tips.all.size, val finished: Boolean = false) {
    val isLast: Boolean get() = index == count - 1

    fun next(): OnboardingState = if (isLast) copy(finished = true) else copy(index = index + 1)

    fun previous(): OnboardingState = copy(index = (index - 1).coerceAtLeast(0))

    /** Skip and Done both end the tour; either way the tips are not shown again. */
    fun dismiss(): OnboardingState = copy(finished = true)
}
