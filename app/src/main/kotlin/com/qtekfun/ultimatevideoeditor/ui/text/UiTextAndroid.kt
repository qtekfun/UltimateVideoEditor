package com.qtekfun.ultimatevideoeditor.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Android resources as a [TextSource]; formatting uses the locale of those resources, so numbers follow the app language. */
class ResourcesTextSource(private val resources: Resources) : TextSource {
    override fun string(id: Int, args: Array<Any>): String = resources.getString(id, *args)

    override fun plural(id: Int, quantity: Int, args: Array<Any>): String = resources.getQuantityString(id, quantity, *args)
}

/** The words of this text in the language of [context] (the service and the activity both apply the app language to theirs). */
fun UiText.resolve(context: Context): String = resolve(ResourcesTextSource(context.resources))

/** The words of this text in the language in use; recomposes when the configuration (and so the language) changes. */
@Composable
fun UiText.asString(): String {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(this, configuration) { resolve(context) }
}
