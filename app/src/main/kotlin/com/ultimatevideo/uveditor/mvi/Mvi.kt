package com.ultimatevideo.uveditor.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Immutable snapshot of everything a screen renders. */
interface UiState

/** User or system action fed into a ViewModel. */
interface UiIntent

/** One-shot event (toast, navigation) that must not be replayed on re-collection. */
interface UiEffect

/**
 * Base for MVI screens: [state] is the single source of truth, [effects] carries one-shot
 * events, and every change to state goes through a pure reducer passed to [reduce].
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    abstract fun onIntent(intent: I)

    protected fun reduce(reducer: S.() -> S) {
        _state.update { it.reducer() }
    }

    protected fun emit(effect: E) {
        _effects.trySend(effect)
    }
}
