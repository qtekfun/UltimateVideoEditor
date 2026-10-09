package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.ui.text.resolve
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** The snackbar of a saved picture: its text and the two actions it offers. */
class SavedFrameVisuals(val saved: SavedFrame, override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

/** "Saved to Pictures/ultimateVE/name.jpg" followed by the notes, in the language in use. */
internal fun savedMessage(saved: SavedFrame): UiText =
    UiText.join(" ", listOf(UiText.res(R.string.ed_s3_frame_saved, "${saved.folder}/${saved.fileName}")) + saved.notes)

/** A [SnackbarHost] that draws a saved picture's snackbar with Share and Open, and every other one as usual. */
@Composable
fun FrameSnackbarHost(state: SnackbarHostState, onShare: () -> Unit, onOpen: () -> Unit) {
    SnackbarHost(state) { data ->
        val visuals = data.visuals
        if (visuals is SavedFrameVisuals) {
            Snackbar(
                action = {
                    Row {
                        TextButton(onClick = { data.dismiss(); onShare() }) { Text(stringResource(R.string.common_share)) }
                        TextButton(onClick = { data.dismiss(); onOpen() }) { Text(stringResource(R.string.common_open)) }
                    }
                },
                dismissAction = { TextButton(onClick = { data.dismiss() }) { Text(stringResource(R.string.common_close)) } },
            ) { Text(visuals.message) }
        } else {
            Snackbar(snackbarData = data)
        }
    }
}

/** Turns the view model's effects into snackbars and the share and viewer intents. */
@Composable
fun StillFrameEffects(viewModel: StillFrameViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                StillFrameEffect.Started -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(UiText.res(R.string.ed_s3_frame_saving).resolve(context), duration = SnackbarDuration.Indefinite)
                }
                is StillFrameEffect.Saved -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(SavedFrameVisuals(effect.frame, savedMessage(effect.frame).resolve(context)))
                }
                is StillFrameEffect.Message -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(effect.text.resolve(context), duration = SnackbarDuration.Long)
                }
                is StillFrameEffect.ShareFile -> shareSavedFrame(context, effect.uri)
                is StillFrameEffect.OpenFile -> openSavedFrame(context, effect.uri)
            }
        }
    }
}

/** Opens the system share sheet for a saved picture. */
fun shareSavedFrame(context: Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Opens a saved picture in the system's image viewer. */
fun openSavedFrame(context: Context, uri: String) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        // No viewer installed: the picture is saved anyway and can be shared.
        shareSavedFrame(context, uri)
    }
}
