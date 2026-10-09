package com.qtekfun.ultimatevideoeditor.ui.text

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.export.BundleJobText
import com.qtekfun.ultimatevideoeditor.ui.language.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UiTextTest {
    @Test
    fun `a resource is resolved with its arguments and nested texts are resolved first`() {
        val text = UiText.res(R.string.bundle_saved_line, "Holiday.uvbundle", UiText.join(", ", UiText.Raw("7.4 GB"), UiText.plural(R.plurals.count_media_files, 14)))

        assertEquals("Backup saved: Holiday.uvbundle (7.4 GB, 14 media files)", text.english())
    }

    @Test
    fun `plurals follow the quantity and the quantity is the first argument unless others are given`() {
        assertEquals("1 media file", UiText.plural(R.plurals.count_media_files, 1).english())
        assertEquals("0 media files", UiText.plural(R.plurals.count_media_files, 0).english())
        assertEquals("2 media files", UiText.plural(R.plurals.count_media_files, 2).english())
        assertEquals("Cannot export: the media for 1 clip is missing (a). Relink it in the editor first.", UiText.plural(R.plurals.export_missing_media, 1, 1, "a").english())
    }

    @Test
    fun `a joined text skips the parts that show nothing`() {
        val joined = UiText.join(" · ", UiText.Empty, UiText.Raw("a"), UiText.join(", ", UiText.Empty), UiText.Raw("b"))

        assertEquals("a · b", joined.english())
        assertTrue(UiText.join(", ", UiText.Empty, UiText.Raw("")).isEmpty())
        assertFalse(UiText.join(", ", UiText.Empty, UiText.res(R.string.common_ok)).isEmpty())
    }

    @Test
    fun `capitalised changes only the first letter`() {
        assertEquals("The storage is full", UiText.Capitalised(UiText.Raw("the storage is full")).english())
        assertEquals("", UiText.Capitalised(UiText.Empty).english())
        assertTrue(UiText.Capitalised(UiText.Empty).isEmpty())
    }

    @Test
    fun `a name list shows three names and counts the rest`() {
        assertEquals("a, b", namedList(listOf("a", "b")).english())
        assertEquals("a, b, c and 2 more", namedList(listOf("a", "b", "c", "d", "e")).english())
        assertEquals("a, b, c", namedList(listOf("a", "b", "c", "d", "e"), andMore = false).english())
    }

    @Test
    fun `a sentence from a system message gets a capital and a full stop`() {
        assertEquals("Connection reset.", sentenceOf("connection reset").english())
        assertEquals("Done (twice)", sentenceOf("done (twice)").english())
        assertEquals("Stop!", sentenceOf("stop!").english())
    }

    @Test
    fun `sizes and times follow the language in use`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("7,4 GB", BundleJobText.bytes((7.4 * 1024 * 1024 * 1024).toLong()))
            Locale.setDefault(Locale.US)
            assertEquals("7.4 GB", BundleJobText.bytes((7.4 * 1024 * 1024 * 1024).toLong()))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the Spanish file reads naturally for a few real messages`() {
        val spanish = FileStrings(StringsXml.parse(StringsXml.values("-es")), Locale.forLanguageTag("es"), spanishQuantity)

        assertEquals("Copia de seguridad guardada: a.uvbundle (1 archivo multimedia)", UiText.res(R.string.bundle_saved_line, "a.uvbundle", UiText.plural(R.plurals.count_media_files, 1)).resolve(spanish))
        assertEquals("3 archivos multimedia", UiText.plural(R.plurals.count_media_files, 3).resolve(spanish))
        assertEquals("100 %", UiText.res(R.string.percent_value, 100).resolve(spanish))
        assertEquals("hace 2 días", UiText.plural(R.plurals.hub_days_ago, 2).resolve(spanish))
        assertEquals("1 proyecto", UiText.plural(R.plurals.hub_project_count, 1).resolve(spanish))
    }

    @Test
    fun `a language code is matched by its language only`() {
        val supported = listOf("en", "es")

        assertEquals("es", AppLanguages.normalise("es", supported))
        assertEquals("es", AppLanguages.normalise("es-MX", supported))
        assertEquals("es", AppLanguages.normalise("es_ES", supported))
        assertEquals("es", AppLanguages.normalise(" ES ", supported))
        assertNull(AppLanguages.normalise("fr", supported))
        assertNull(AppLanguages.normalise("", supported))
        assertNull(AppLanguages.normalise(null, supported))
        assertNull(AppLanguages.normalise("system", supported))
    }

    @Test
    fun `a language is named in itself`() {
        assertEquals("English", AppLanguages.nativeName("en"))
        assertEquals("Español", AppLanguages.nativeName("es"))
    }

    /** The CLDR plural rule of Spanish: "one" for 1, "many" for whole millions, "other" for the rest. */
    private val spanishQuantity: (Int) -> String = { n -> if (n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other" }
}
