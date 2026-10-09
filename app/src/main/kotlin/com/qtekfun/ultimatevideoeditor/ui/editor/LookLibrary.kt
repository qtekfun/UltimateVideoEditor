package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.rawOr
import com.qtekfun.ultimatevideoeditor.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.LookStore
import com.qtekfun.ultimatevideoeditor.domain.Effect
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
import com.qtekfun.ultimatevideoeditor.domain.GradeLook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A copied colour grade, ready to paste onto another clip. */
data class GradeClipboard(val values: List<Double>, val curves: GradeCurves)

data class LookLibraryState(
    val looks: List<GradeLook> = emptyList(),
    val clipboard: GradeClipboard? = null,
    val error: UiText? = null,
)

/**
 * The app-wide library of saved colour looks plus the copy/paste clipboard of the colour section. Applying a
 * look or pasting goes through `EditorIntent.ApplyGrade`; this class only keeps what can be applied.
 */
class LookLibraryViewModel(
    private val store: LookStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(LookLibraryState())
    val state: StateFlow<LookLibraryState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val looks = withContext(io) { store.list() }
            _state.update { it.copy(looks = looks) }
        }
    }

    /** Saves [grade] under [name]. A blank name is refused; a name already in use gets a number. */
    fun save(name: String, grade: Effect) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(error = UiText.res(R.string.ed_2b_look_needs_name)) }
            return
        }
        if (grade.type != EffectType.COLOR_GRADE) return
        val taken = _state.value.looks.map { it.name.lowercase() }.toSet()
        var unique = clean
        var n = 2
        while (unique.lowercase() in taken) unique = "$clean ${n++}"
        val look = GradeLook.of(idGenerator(), unique, grade)
        viewModelScope.launch {
            try {
                withContext(io) { store.save(look) }
                _state.update { it.copy(looks = (it.looks + look).sortedBy { l -> l.name.lowercase() }, error = null) }
            } catch (e: IOException) {
                _state.update { it.copy(error = rawOr(e.message, UiText.res(R.string.ed_2b_look_not_saved))) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(io) { store.delete(id) }
            _state.update { it.copy(looks = it.looks.filter { l -> l.id != id }) }
        }
    }

    /** Remembers [grade] for a later paste. */
    fun copy(grade: Effect) {
        if (grade.type != EffectType.COLOR_GRADE) return
        _state.update { it.copy(clipboard = GradeClipboard(grade.values, grade.curves ?: GradeCurves.IDENTITY)) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
