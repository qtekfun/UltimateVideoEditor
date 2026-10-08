package com.qtekfun.ultimatevideoeditor.engine.title

import android.graphics.Typeface
import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves imported fonts from a [FontRegistry]. A font that is not (or no longer) imported, or whose
 * file the platform cannot load, falls back to the system font, so a title never fails to draw because
 * of a font. Loaded typefaces are cached by id; a font that was missing is looked up again next time,
 * so importing it later takes effect without restarting.
 */
class RegistryFontResolver(private val registry: FontRegistry) : FontResolver {
    private val loaded = ConcurrentHashMap<String, Typeface>()

    override fun typeface(fontId: String?, bold: Boolean, italic: Boolean): Typeface {
        val base = fontId?.let(::load) ?: Typeface.DEFAULT
        return Typeface.create(base, FontResolver.styleOf(bold, italic))
    }

    private fun load(id: String): Typeface? {
        loaded[id]?.let { return it }
        val file = registry.file(id) ?: return null
        val face = try {
            Typeface.createFromFile(file)
        } catch (e: RuntimeException) {
            // The platform rejects files its text stack cannot read.
            return null
        }
        loaded[id] = face
        return face
    }

    /** Forgets cached typefaces (after a font was removed). */
    fun clear() = loaded.clear()
}
