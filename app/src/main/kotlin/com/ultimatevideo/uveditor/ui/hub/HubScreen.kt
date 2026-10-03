package com.ultimatevideo.uveditor.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HubScreen(viewModel: HubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HubContent(state)
}

@Composable
internal fun HubContent(state: HubState) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "ultimateVE", style = MaterialTheme.typography.headlineLarge)
            val status = state.errorMessage ?: state.engineVersion?.let { "Engine v$it" } ?: "Loading engine…"
            Text(text = status, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
