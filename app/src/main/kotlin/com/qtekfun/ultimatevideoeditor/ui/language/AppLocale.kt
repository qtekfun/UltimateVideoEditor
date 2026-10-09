package com.qtekfun.ultimatevideoeditor.ui.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.qtekfun.ultimatevideoeditor.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** The picked language in a private preferences file of the app. */
class PreferencesLanguageStore(context: Context) : LanguageStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    override fun set(tag: String?) {
        prefs.edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.apply()
    }

    companion object {
        const val FILE = "language"
        const val KEY = "tag"
    }
}

/**
 * Applies the app language. From Android 13 the system owns it (Settings > Apps > ultimateVE > Language, declared by
 * `android:localeConfig`) and this class only reads and writes that setting through [LocaleManager]; on Android 12 and 12L, which
 * have no such setting, the choice lives in [PreferencesLanguageStore] and [wrap] gives the activity, the application and the export
 * service a context in that locale. Either way the preference is kept equal to what is in force, so the About screen can show it.
 */
object AppLocale {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** A context whose resources use the picked language; the base context itself when the system applies it or none is picked. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppLanguages.normalise(PreferencesLanguageStore(base).get(), supported(base)) ?: return base
        val locale = Locale.forLanguageTag(tag)
        // Dates and numbers formatted with the default locale follow the choice too.
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /**
     * [base] with the language in force right now: for text produced later by code that outlives an activity (a view model), where
     * the context it was started with may hold the language of an earlier moment (Android 12 and 12L apply a change by recreating the
     * activity, not the application).
     */
    fun localized(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppLanguages.normalise(PreferencesLanguageStore(base).get(), supported(base))
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(if (tag == null) Resources.getSystem().configuration.locales else LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(configuration)
    }

    /** The languages the app is translated into, from `res/xml/locales_config.xml`. */
    fun supported(context: Context): List<String> {
        val parser = context.resources.getXml(R.xml.locales_config)
        val tags = ArrayList<String>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return tags
    }

    /** The language in force as the user chose it: null follows the system. */
    fun chosen(context: Context): String? {
        val supported = supported(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val applied = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (applied.isEmpty) null else AppLanguages.normalise(applied[0].toLanguageTag(), supported)
        }
        return AppLanguages.normalise(PreferencesLanguageStore(context).get(), supported)
    }

    /** Makes the stored preference equal to the system's per-app setting (changed in system Settings since the last start). */
    fun syncPreference(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val store = PreferencesLanguageStore(context)
        val system = chosen(context)
        if (store.get() != system) store.set(system)
    }

    /** Switches to [tag] (null: follow the system). The activity is recreated so every screen reads the new language. */
    fun select(activity: Activity, tag: String?) {
        val language = AppLanguages.normalise(tag, supported(activity))
        PreferencesLanguageStore(activity).set(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system restarts the activity itself when the setting changes.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        } else {
            activity.recreate()
        }
    }
}
