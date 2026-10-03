package com.ultimatevideo.uveditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.data.ContentResolverTransferIO
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.engine.NativeEngineClient
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
        setContent {
            UVEditorTheme {
                val hubViewModel: HubViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { HubViewModel(NativeEngineClient(), repository) }
                    },
                )
                // The editor screen arrives in a later phase; opening a project is a no-op for now.
                HubScreen(hubViewModel, onOpenProject = {})
            }
        }
    }
}
