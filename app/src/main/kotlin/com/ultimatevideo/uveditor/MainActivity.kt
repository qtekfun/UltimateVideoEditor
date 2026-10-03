package com.ultimatevideo.uveditor

import android.os.Bundle
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
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.data.AndroidMediaImporter
import com.ultimatevideo.uveditor.data.ContentResolverTransferIO
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.engine.NativeEngineClient
import com.ultimatevideo.uveditor.ui.editor.EditorScreen
import com.ultimatevideo.uveditor.ui.editor.EditorViewModel
import com.ultimatevideo.uveditor.ui.hub.HubIntent
import com.ultimatevideo.uveditor.ui.hub.HubScreen
import com.ultimatevideo.uveditor.ui.hub.HubViewModel
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme
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
        setContent {
            UVEditorTheme {
                val hubViewModel: HubViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { HubViewModel(NativeEngineClient(), repository) }
                    },
                )
                var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
                val projectId = openProjectId
                if (projectId == null) {
                    // Project timestamps and names may have changed while editing.
                    LaunchedEffect(Unit) { hubViewModel.onIntent(HubIntent.Refresh) }
                    HubScreen(hubViewModel, onOpenProject = { openProjectId = it })
                } else {
                    val owner = rememberScopedViewModelOwner(projectId)
                    val editorViewModel: EditorViewModel = viewModel(
                        viewModelStoreOwner = owner,
                        factory = viewModelFactory {
                            initializer { EditorViewModel(projectId, repository, mediaImporter) }
                        },
                    )
                    EditorScreen(editorViewModel, projectId, onClose = { openProjectId = null })
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
