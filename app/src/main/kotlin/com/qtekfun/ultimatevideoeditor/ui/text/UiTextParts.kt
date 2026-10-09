package com.qtekfun.ultimatevideoeditor.ui.text

import com.qtekfun.ultimatevideoeditor.R

/** "a.mov, b.mov, c.mov and 2 more": the first [shown] names, then how many were left out (when [andMore] is set). */
fun namedList(names: List<String>, shown: Int = 3, andMore: Boolean = true): UiText {
    val first = names.take(shown).joinToString()
    return if (andMore && names.size > shown) UiText.res(R.string.list_and_more, first, names.size - shown) else UiText.Raw(first)
}

/** A sentence made from text that is not ours (an error message from the system): its first letter in capitals and a full stop at the end. */
fun sentenceOf(raw: String): UiText {
    val trimmed = raw.trim()
    val ended = if (trimmed.endsWith(".") || trimmed.endsWith(")") || trimmed.endsWith("!")) trimmed else "$trimmed."
    return UiText.Capitalised(UiText.Raw(ended))
}

/** A message that came from the system, shown as it is, or [fallback] when there is none. */
fun rawOr(message: String?, fallback: UiText): UiText = message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: fallback
