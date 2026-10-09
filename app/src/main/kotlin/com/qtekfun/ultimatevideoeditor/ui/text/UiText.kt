package com.qtekfun.ultimatevideoeditor.ui.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text for the user that is produced outside a composable: by view models, effects, pure classes that are unit tested on the
 * JVM, and by the export service's notifications. It holds a resource id and its arguments, never the translated words, so
 * the text is turned into a string only where it is shown ([resolve]) and always in the language of that moment: a state kept
 * in a view model stays right when the user changes the language, and the JVM tests need no Android.
 *
 * Arguments of a [Res] or [Plural] may be strings, numbers, or other [UiText]s (resolved first). Words that must not be
 * translated (a file name, a project name, a message that came from the system) are plain string arguments or a [Raw].
 * Never join translatable fragments with `+`: give the whole sentence to a string resource with positional arguments
 * (`%1$s`, `%2$d`), so a translation can reorder them.
 */
sealed interface UiText {
    /** A `<string>` resource. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A `<plurals>` resource; [quantity] picks the form and is also the first format argument unless [args] says otherwise. */
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any> = listOf(quantity)) : UiText

    /** Text that is data, not words of the app: shown as it is. */
    data class Raw(val value: String) : UiText

    /** [inner] with its first letter in capitals (a sentence made from the middle of another one, or from a system message). */
    data class Capitalised(val inner: UiText) : UiText

    /** Several texts in a row with [separator] (punctuation that is the same in every language, such as " · " or ". "); empty parts are skipped. */
    data class Joined(val parts: List<UiText>, val separator: String) : UiText

    companion object {
        val Empty: UiText = Raw("")

        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText =
            Plural(id, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

        fun raw(value: String): UiText = Raw(value)

        fun join(separator: String, parts: List<UiText>): UiText = Joined(parts, separator)

        fun join(separator: String, vararg parts: UiText): UiText = Joined(parts.toList(), separator)
    }
}

/** True when the text shows nothing at all (an empty [UiText.Raw], or a [UiText.Joined] of such). */
fun UiText.isEmpty(): Boolean = when (this) {
    is UiText.Raw -> value.isEmpty()
    is UiText.Joined -> parts.all { it.isEmpty() }
    is UiText.Capitalised -> inner.isEmpty()
    is UiText.Res, is UiText.Plural -> false
}

fun UiText.isNotEmpty(): Boolean = !isEmpty()

/** Where the words come from: Android resources on a device, the parsed `strings.xml` in the JVM tests. */
interface TextSource {
    fun string(@StringRes id: Int, args: Array<Any>): String

    fun plural(@PluralsRes id: Int, quantity: Int, args: Array<Any>): String
}

/** The words of this text from [source]. Nested texts in the arguments are resolved the same way. */
fun UiText.resolve(source: TextSource): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> source.string(id, resolvedArgs(args, source))
    is UiText.Plural -> source.plural(id, quantity, resolvedArgs(args, source))
    is UiText.Capitalised -> inner.resolve(source).replaceFirstChar { it.uppercase() }
    is UiText.Joined -> parts.filter { it.isNotEmpty() }.joinToString(separator) { it.resolve(source) }
}

private fun resolvedArgs(args: List<Any>, source: TextSource): Array<Any> =
    Array(args.size) { index -> (args[index] as? UiText)?.resolve(source) ?: args[index] }
