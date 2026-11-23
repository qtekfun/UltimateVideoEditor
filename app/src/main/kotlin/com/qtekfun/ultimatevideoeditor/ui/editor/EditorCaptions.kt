package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.CoroutineScope
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.domain.captions.captionCount
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsHost
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.ContentResolverSubtitleSource
import kotlinx.coroutines.launch

/** The captions sheet host; returns what opens it from the tool row. */
@Composable
internal fun rememberOpenCaptions(
    projectId: String,
    context: Context,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    holder: State<EditorState>,
    onIntent: (EditorIntent) -> Unit,
): () -> Unit {
    val captionsViewModel: CaptionsViewModel = viewModel(
        key = "captions-$projectId",
        factory = viewModelFactory {
            initializer { CaptionsViewModel(ContentResolverSubtitleSource(context.applicationContext.contentResolver)) }
        },
    )
    CaptionsHost(
        captionsViewModel,
        onClips = { clips, intoExistingTrack -> onIntent(EditorIntent.AddCaptionClips(clips, intoExistingTrack)) },
        onRestyle = { style, canvasHeight -> onIntent(EditorIntent.RestyleCaptions(style, canvasHeight)) },
        onMessage = { text ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(text) }
        },
    )
    val openCaptions: () -> Unit = {
        val live = holder.value
        captionsViewModel.onIntent(
            CaptionsIntent.Open(live.fps, live.canvasHeight, live.playhead.value, live.timeline.captionCount()),
        )
    }
    return openCaptions
}
