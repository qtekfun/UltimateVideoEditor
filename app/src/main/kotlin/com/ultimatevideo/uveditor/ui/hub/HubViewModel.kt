package com.ultimatevideo.uveditor.ui.hub

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.engine.EngineClient
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.mvi.MviViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubViewModel(
    private val engine: EngineClient,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviViewModel<HubState, HubIntent, HubEffect>(HubState()) {

    init {
        onIntent(HubIntent.LoadEngineInfo)
    }

    override fun onIntent(intent: HubIntent) {
        when (intent) {
            HubIntent.LoadEngineInfo -> loadEngineInfo()
        }
    }

    private fun loadEngineInfo() {
        viewModelScope.launch {
            try {
                val version = withContext(workDispatcher) { engine.version() }
                reduce { copy(engineVersion = version, errorMessage = null) }
            } catch (e: EngineException) {
                reduce { copy(engineVersion = null, errorMessage = e.message) }
            }
        }
    }
}
