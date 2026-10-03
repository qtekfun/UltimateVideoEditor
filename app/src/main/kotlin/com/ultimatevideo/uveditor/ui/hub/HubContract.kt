package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

data class HubState(
    val engineVersion: String? = null,
    val errorMessage: String? = null,
) : UiState

sealed interface HubIntent : UiIntent {
    data object LoadEngineInfo : HubIntent
}

sealed interface HubEffect : UiEffect
