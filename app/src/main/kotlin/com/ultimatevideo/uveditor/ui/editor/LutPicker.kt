package com.ultimatevideo.uveditor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.LutInfo
import com.ultimatevideo.uveditor.data.LutStore
import com.ultimatevideo.uveditor.domain.LutParseException
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
    val error: String? = null,
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
                _state.update { it.copy(importing = false, error = "This is not a usable .cube LUT: ${e.message}") }
            } catch (e: IOException) {
                _state.update { it.copy(importing = false, error = "The LUT file could not be read: ${e.message}") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun withContextList(info: LutInfo, current: List<LutInfo>): List<LutInfo> =
        (current.filter { it.key != info.key } + info).sortedBy { it.name.lowercase() }
}

/** Lists the LUT library; picking one adds it to the selected clip, "Import" brings in a new `.cube` file. */
@Composable
internal fun LutPickerDialog(
    state: LutLibraryState,
    onPick: (Int) -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri.toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LUT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.luts.isEmpty()) {
                    Text("No LUTs yet. Import a 3D .cube file (17, 33 or 65 points).", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(state.luts, key = { it.key }) { lut ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(lut.key) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(lut.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${lut.size}-point cube", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "A LUT is applied to the clip in the project's colour space (Rec.709 in an SDR project, the HLG signal in an HLG project).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                Text(if (state.importing) "Importing…" else "Import .cube…")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
