package com.ultimatevideo.uveditor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.data.AndroidClipPeeker
import com.ultimatevideo.uveditor.data.AndroidMediaImporter
import com.ultimatevideo.uveditor.data.AndroidProjectThumbnails
import com.ultimatevideo.uveditor.data.PreferencesNewProjectDefaults
import com.ultimatevideo.uveditor.data.AndroidPersistedUris
import com.ultimatevideo.uveditor.data.ContentResolverTransferIO
import com.ultimatevideo.uveditor.data.PreferencesSessionStore
import com.ultimatevideo.uveditor.data.ProjectDirMediaCaches
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.trimPersistedUris
import com.ultimatevideo.uveditor.engine.NativeEngineClient
import com.ultimatevideo.uveditor.engine.timeline.WaveformBeatSource
import com.ultimatevideo.uveditor.engine.timeline.WaveformCache
import com.ultimatevideo.uveditor.ui.editor.EditorScreen
import com.ultimatevideo.uveditor.ui.editor.EditorViewModel
import com.ultimatevideo.uveditor.ui.hub.HubIntent
import com.ultimatevideo.uveditor.ui.hub.HubScreen
import com.ultimatevideo.uveditor.ui.hub.HubViewModel
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ultimatevideo.uveditor.engine.stabilise.ContentResolverFdOpener
import com.ultimatevideo.uveditor.engine.stabilise.FileStabiliser
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = ProjectRepository(
            rootDir = File(filesDir, "projects"),
            transferIO = ContentResolverTransferIO(applicationContext.contentResolver),
        )
        val mediaImporter = AndroidMediaImporter(applicationContext)
        val session = PreferencesSessionStore(applicationContext)
        val newProjectDefaults = PreferencesNewProjectDefaults(applicationContext)
        val clipPeeker = AndroidClipPeeker(applicationContext)
        val projectThumbnails = AndroidProjectThumbnails(applicationContext)
        // Android drops the oldest persisted file permissions past its limit, which would leave old projects
        // with missing media: give back the ones no project uses before that can happen.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                trimPersistedUris(AndroidPersistedUris(applicationContext), repository.referencedMediaUris()) { Log.i("MediaPermissions", it) }
            }
        }
        setContent {
            UVEditorTheme {
                val hubViewModel: HubViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HubViewModel(
                                NativeEngineClient(),
                                repository,
                                session = session,
                                defaults = newProjectDefaults,
                                peeker = clipPeeker,
                            )
                        }
                    },
                )
                var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
                val projectId = openProjectId
                if (projectId == null) {
                    // Project timestamps and names may have changed while editing.
                    LaunchedEffect(Unit) { hubViewModel.onIntent(HubIntent.Refresh) }
                    HubScreen(
                        hubViewModel,
                        onOpenProject = {
                            session.markOpen(it)
                            openProjectId = it
                        },
                        thumbnails = projectThumbnails,
                    )
                } else {
                    val owner = rememberScopedViewModelOwner(projectId)
                    val editorViewModel: EditorViewModel = viewModel(
                        viewModelStoreOwner = owner,
                        factory = viewModelFactory {
                            initializer {
                                EditorViewModel(
                                    projectId,
                                    repository,
                                    mediaImporter,
                                    mediaCaches = ProjectDirMediaCaches(File(filesDir, "projects/$projectId")),
                                    beatSource = WaveformBeatSource(WaveformCache(File(filesDir, "projects/$projectId"))),
                                    stabiliser = FileStabiliser(File(filesDir, "projects/$projectId/stab"), ContentResolverFdOpener(contentResolver)),
                                )
                            }
                        },
                    )
                    EditorScreen(
                        editorViewModel,
                        projectId,
                        onClose = {
                            // Leaving on purpose: the next start has nothing to offer to reopen.
                            session.markClosed()
                            openProjectId = null
                        },
                    )
                }
            }
        }
    }
}

/**
 * A ViewModel owner that lives only while the editor for [key] is on screen, so reopening a
 * project always starts a fresh session from disk instead of reusing a stale one.
 */
@Composable
private fun rememberScopedViewModelOwner(key: String): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
