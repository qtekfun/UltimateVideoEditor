package com.ultimatevideo.uveditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.engine.NativeEngineClient
import com.ultimatevideo.uveditor.ui.hub.HubScreen
import com.ultimatevideo.uveditor.ui.hub.HubViewModel
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UVEditorTheme {
                val hubViewModel: HubViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { HubViewModel(NativeEngineClient()) }
                    },
                )
                HubScreen(hubViewModel)
            }
        }
    }
}
