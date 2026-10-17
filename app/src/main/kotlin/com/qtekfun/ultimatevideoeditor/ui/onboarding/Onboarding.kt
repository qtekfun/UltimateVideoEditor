package com.qtekfun.ultimatevideoeditor.ui.onboarding

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
        const val KEY_SEEN = "tipsSeen" // i18n-ok: a preference key
    }
}

/** One dismissible tip card. */
data class Tip(val title: UiText, val body: UiText)

/** The three first-run tips, in order: they point at the key actions (import, split, export, layout, help). */
object Tips {
    val all: List<Tip> = listOf(
        Tip(
            title = UiText.res(R.string.ed_s3_tip1_title),
            body = UiText.res(R.string.ed_s3_tip1_body),
        ),
        Tip(
            title = UiText.res(R.string.ed_s3_tip2_title),
            body = UiText.res(R.string.ed_s3_tip2_body),
        ),
        Tip(
            title = UiText.res(R.string.ed_s3_tip3_title),
            body = UiText.res(R.string.ed_s3_tip3_body),
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
