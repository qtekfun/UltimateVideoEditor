package com.qtekfun.ultimatevideoeditor.ui.language

import java.util.Locale

/**
 * Pure rules about the languages the app offers. The list itself is `res/xml/locales_config.xml` (the same file the system reads
 * for its per-app language setting), so adding a language is a `values-xx/strings.xml` plus one line there; a unit test checks
 * that the two agree. A language is a bare language code such as "es": regional variants (es-MX) use the same translation.
 */
object AppLanguages {
    /**
     * The supported language that [tag] stands for ("es-MX" and "es_ES" give "es"), or null when it is empty, "system" or not
     * supported, which means "follow the system".
     */
    fun normalise(tag: String?, supported: List<String>): String? {
        val language = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT).orEmpty()
        return language.takeIf { it.isNotEmpty() && it in supported }
    }

    /** The name of a language in that language ("English", "Español"), whatever language the app is shown in. */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** The language the user picked in the app; null follows the system. Kept on the device only. */
interface LanguageStore {
    fun get(): String?

    fun set(tag: String?)
}
