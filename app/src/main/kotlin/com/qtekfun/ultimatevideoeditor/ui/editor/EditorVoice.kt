package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.CoroutineScope
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.voice.AndroidVoiceRecorder
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverController
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoicePlayback
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import java.io.File
import kotlinx.coroutines.launch

/** The voice-over controller and the fallback to the phone's own recorder. */
internal class VoiceSupport(val voice: VoiceOverController, val useSystemRecorder: () -> Unit)

@Composable
internal fun rememberVoiceSupport(
    context: Context,
    projectId: String,
    folderScope: CoroutineScope,
    holder: State<EditorState>,
    onIntent: (EditorIntent) -> Unit,
): VoiceSupport {
    // The voice-over: the microphone permission is asked for the first time the button is pressed, never at start. The take is recorded
    // to the project's own folder, and a permission that is refused leaves the system's recorder as a way to the same result.
    val voiceHolder = remember { arrayOfNulls<VoiceOverController>(1) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> voiceHolder[0]?.onPermissionResult(granted) }
    val voice = remember(projectId) {
        VoiceOverController(
            scope = folderScope,
            recorder = AndroidVoiceRecorder(directory = { File(context.filesDir, "projects/$projectId/voice") }),
            playback = object : VoicePlayback {
                override val isPlaying: Boolean get() = holder.value.isPlaying

                override fun playheadFrame(): Long = holder.value.playhead.value

                override fun endFrame(): Long = holder.value.timeline.tracks.maxOfOrNull { it.end.value } ?: 0

                override fun seek(frame: Long) = onIntent(EditorIntent.SetPlayhead(frame))

                override fun play() {
                    if (!holder.value.isPlaying) onIntent(EditorIntent.TogglePlay)
                }

                override fun pause() {
                    if (holder.value.isPlaying) onIntent(EditorIntent.TogglePlay)
                }
            },
            hasPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            requestPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onTake = { onIntent(EditorIntent.PlaceVoiceOver(it.uri, it.startFrame, it.trimMs)) },
            now = android.os.SystemClock::elapsedRealtime,
        ).also { voiceHolder[0] = it }
    }
    LaunchedEffect(voice) { snapshotFlow { holder.value.isPlaying }.collect { voice.onPlaybackChanged(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice.onLeaving() }
    DisposableEffect(voice) { onDispose { voice.onLeaving() } }
    val systemRecorder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) onIntent(EditorIntent.ImportMedia(listOf(uri.toString())))
    }
    val useSystemRecorder = {
        try {
            systemRecorder.launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
        } catch (e: ActivityNotFoundException) {
            onIntent(EditorIntent.ReportText(UiText.res(R.string.ed_vo_no_recorder)))
        }
    }
    return VoiceSupport(voice, useSystemRecorder)
}
