package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Gives the element a spoken description. [text] is resolved by the caller (`stringResource` cannot be called inside the semantics block). */
internal fun Modifier.described(text: String): Modifier = semantics { contentDescription = text }
