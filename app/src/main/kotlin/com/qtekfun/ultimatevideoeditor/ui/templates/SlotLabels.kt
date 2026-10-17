package com.qtekfun.ultimatevideoeditor.ui.templates

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R

/** "1 slot", "5 slots": the count noun agrees with the number. */
internal fun slotCountLabel(count: Int): UiText = UiText.plural(R.plurals.ed_s3_slots, count)
