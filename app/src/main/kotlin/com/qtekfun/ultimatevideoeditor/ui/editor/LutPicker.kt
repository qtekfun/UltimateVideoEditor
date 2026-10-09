package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.LutInfo
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.domain.FilterLook
import com.qtekfun.ultimatevideoeditor.domain.FilterPack
import com.qtekfun.ultimatevideoeditor.domain.LutParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Library key to display name for the LUT rows of the effects section; empty where no library is provided. */
internal val LocalLutNames = staticCompositionLocalOf<Map<Int, String>> { emptyMap() }

/** Reads a picked file: its display name and its text. Throws [IOException] when it cannot be read. */
fun interface LutFileReader {
    fun read(uri: String): Pair<String, String>
}

data class LutLibraryState(
    val luts: List<LutInfo> = emptyList(),
    val importing: Boolean = false,
    val error: UiText? = null,
) {
    val names: Map<Int, String> get() = luts.associate { it.key to it.name }
}

/** The app-wide LUT library: lists the stored LUTs and imports `.cube` files into it. */
class LutLibraryViewModel(
    private val store: LutStore,
    private val reader: LutFileReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(LutLibraryState())
    val state: StateFlow<LutLibraryState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val luts = withContext(io) { store.list() }
            _state.update { it.copy(luts = luts) }
        }
    }

    /** Imports the file at [uri]; [onImported] gets the library entry (new or already there). */
    fun import(uri: String, onImported: (LutInfo) -> Unit) {
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                val info = withContext(io) {
                    val (name, text) = reader.read(uri)
                    store.import(name, text)
                }
                _state.update { it.copy(importing = false, luts = withContextList(info, it.luts)) }
                onImported(info)
            } catch (e: LutParseException) {
                _state.update { it.copy(importing = false, error = UiText.res(R.string.ed_2b_lut_not_usable, e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(importing = false, error = UiText.res(R.string.ed_2b_lut_not_read, e.message.orEmpty())) }
            }
        }
    }

    /**
     * Bakes the built-in filter [id] into the library (a no-op when it is already there) and hands over its entry,
     * so it behaves like any imported LUT from then on. Nothing is downloaded: the cube is generated here.
     */
    fun installFilter(id: String, onInstalled: (LutInfo) -> Unit) {
        val look = FilterPack.find(id) ?: return
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                val info = withContext(io) { store.import(look.name, FilterPack.cube(look)) }
                _state.update { it.copy(importing = false, luts = withContextList(info, it.luts)) }
                onInstalled(info)
            } catch (e: IOException) {
                _state.update { it.copy(importing = false, error = UiText.res(R.string.ed_2b_filter_not_saved, e.message.orEmpty())) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun withContextList(info: LutInfo, current: List<LutInfo>): List<LutInfo> =
        (current.filter { it.key != info.key } + info).sortedBy { it.name.lowercase() }
}

/**
 * The built-in filters first (each with a swatch showing what it does to a few reference colours), then the LUTs
 * the user imported. Picking either adds it to the selected clip; "Import" brings in a new `.cube` file.
 */
@Composable
internal fun LutPickerDialog(
    state: LutLibraryState,
    onPick: (Int) -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    filters: List<FilterLook> = FilterPack.looks,
    onPickFilter: (String) -> Unit = {},
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri.toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_2b_filters_and_luts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (filters.isNotEmpty()) {
                        item(key = "filters-header") { Text(stringResource(R.string.ed_2b_filters), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp)) }
                        items(filters, key = { "filter-${it.id}" }) { look ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.importing) { onPickFilter(look.id) }
                                    .padding(vertical = 6.dp),
                            ) {
                                FilterSwatch(look)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(look.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(look.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "luts-header") { Text(stringResource(R.string.ed_2b_your_luts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    if (state.luts.isEmpty()) {
                        item(key = "luts-empty") { Text(stringResource(R.string.ed_2b_no_imported_luts_yet_import), style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(state.luts, key = { it.key }) { lut ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(lut.key) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(lut.name, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.ed_2b_point_cube, lut.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    stringResource(R.string.ed_2b_a_lut_is_applied_to),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                Text(if (state.importing) stringResource(R.string.ed_2b_working) else stringResource(R.string.ed_2b_import_cube))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** Reference colours the swatch shows: skin, sky, foliage, red, a mid grey and a light grey. */
private val SWATCH_COLOURS = listOf(
    Triple(0.85, 0.65, 0.55),
    Triple(0.35, 0.55, 0.85),
    Triple(0.3, 0.6, 0.3),
    Triple(0.85, 0.2, 0.2),
    Triple(0.5, 0.5, 0.5),
    Triple(0.8, 0.8, 0.8),
)

/** A strip of [SWATCH_COLOURS] as the look renders them, a quick read of its colour and contrast. */
@Composable
private fun FilterSwatch(look: FilterLook) {
    Canvas(modifier = Modifier.size(width = 84.dp, height = 28.dp)) {
        val cell = size.width / SWATCH_COLOURS.size
        SWATCH_COLOURS.forEachIndexed { i, (r, g, b) ->
            val (lr, lg, lb) = look.params.apply(r, g, b)
            drawRect(Color(lr.toFloat(), lg.toFloat(), lb.toFloat()), topLeft = Offset(i * cell, 0f), size = Size(cell + 1f, size.height))
        }
    }
}
