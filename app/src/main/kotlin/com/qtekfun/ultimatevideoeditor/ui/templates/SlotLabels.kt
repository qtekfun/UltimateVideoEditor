package com.qtekfun.ultimatevideoeditor.ui.templates

/** "1 slot", "5 slots": the count noun agrees with the number. */
internal fun slotCountLabel(count: Int): String = if (count == 1) "1 slot" else "$count slots"
