package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * An [EditorHost] without a ViewModel, so a feature handler can be tested on its own: real state and undo history, effects collected
 * in [effects], autosave requests counted in [saves].
 */
internal class FakeEditorHost(timeline: Timeline = Timeline(), override val scope: CoroutineScope) : EditorHost {
    private val _state = MutableStateFlow(EditorState(isLoading = false, timeline = timeline))
    override val state: StateFlow<EditorState> = _state
    override var history = EditHistory(timeline)
        private set
    override val clipKeys = KeyRegistry()
    override val assetKeys = KeyRegistry()
    private var nextId = 0
    override val idGenerator: () -> String = { "id${nextId++}" }

    val effects = mutableListOf<EditorEffect>()
    var saves = 0
        private set

    override fun reduce(reducer: EditorState.() -> EditorState) = _state.update { it.reducer() }

    override fun emit(effect: EditorEffect) {
        effects += effect
    }

    override fun execute(command: EditCommand): Boolean = when (val result = history.execute(command)) {
        is EditResult.Success -> {
            history = result.value
            _state.update { it.copy(timeline = history.timeline, canUndo = history.canUndo, canRedo = history.canRedo) }
            scheduleSave()
            true
        }
        is EditResult.Failure -> {
            emit(EditorEffect.ShowMessage(describe(result.error)))
            false
        }
    }

    override fun scheduleSave() {
        saves++
    }
}
