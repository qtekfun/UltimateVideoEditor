package com.qtekfun.ultimatevideoeditor.data

import java.util.Locale

/** Rules for project names: they are unique ignoring case and surrounding whitespace. */
object ProjectNames {
    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun isTaken(name: String, existing: Collection<String>): Boolean {
        val wanted = key(name)
        return existing.any { key(it) == wanted }
    }

    /**
     * Returns [base] if it is free, otherwise the first free `format(base, n)` for n = 2, 3, ...
     * [base] is shortened so the result never exceeds [maxLength].
     */
    fun unique(
        base: String,
        existing: Collection<String>,
        maxLength: Int,
        format: (base: String, n: Int) -> String,
    ): String {
        val trimmed = base.trim()
        if (!isTaken(trimmed, existing)) return trimmed
        var n = 2
        while (true) {
            val suffixLength = format("", n).length
            val candidate = format(trimmed.take((maxLength - suffixLength).coerceAtLeast(1)), n)
            if (!isTaken(candidate, existing)) return candidate
            n++
        }
    }
}
