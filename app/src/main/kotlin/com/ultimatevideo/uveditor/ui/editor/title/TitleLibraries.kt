package com.ultimatevideo.uveditor.ui.editor.title

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.FontEntry
import com.ultimatevideo.uveditor.data.FontException
import com.ultimatevideo.uveditor.data.FontMetaParser
import com.ultimatevideo.uveditor.data.FontRegistry
import com.ultimatevideo.uveditor.data.PresetFormatException
import com.ultimatevideo.uveditor.data.TitlePresetCodec
import com.ultimatevideo.uveditor.data.TitlePresetStore
import com.ultimatevideo.uveditor.domain.MotionPreset
import com.ultimatevideo.uveditor.domain.TextTemplate
import com.ultimatevideo.uveditor.domain.TitleContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Reads a picked file; refuses anything over [maxBytes]. Throws [IOException] when it cannot be read. */
fun interface BytesReader {
    fun read(uri: String, maxBytes: Int): ByteArray
}

/** Writes text to a file the user chose. Throws [IOException] when it cannot be written. */
fun interface TextWriter {
    fun write(uri: String, text: String)
}

class ContentResolverBytesReader(private val context: Context) : BytesReader {
    override fun read(uri: String, maxBytes: Int): ByteArray {
        val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("The file cannot be opened")
        return input.use { stream ->
            val bytes = stream.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("The file is too large")
            bytes
        }
    }
}

class ContentResolverTextWriter(private val context: Context) : TextWriter {
    override fun write(uri: String, text: String) {
        val output = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("The file cannot be opened")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

data class TitleLibraryState(
    val fonts: List<FontEntry> = emptyList(),
    val presets: List<TextTemplate> = emptyList(),
    val busy: Boolean = false,
    /** A short result or error for the user (shown once, then cleared). */
    val message: String? = null,
) {
    val fontIds: Set<String> get() = fonts.mapTo(LinkedHashSet()) { it.id }

    fun familyOf(id: String): String? = fonts.firstOrNull { it.id == id }?.family
}

/**
 * The app-wide libraries the title editor uses: imported fonts and saved title presets. Everything
 * stays on the device; files come only from what the user picks. File work runs on [io].
 */
class TitleLibraryViewModel(
    private val fontRegistry: FontRegistry,
    private val presetStore: TitlePresetStore,
    private val reader: BytesReader,
    private val writer: TextWriter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(TitleLibraryState())
    val state: StateFlow<TitleLibraryState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (fonts, presets) = withContext(io) { fontRegistry.list() to presetStore.list() }
            _state.update { it.copy(fonts = fonts, presets = presets) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Imports the font file at [uri]; [onImported] gets the entry (new or already there). */
    fun importFont(uri: String, onImported: (FontEntry) -> Unit = {}) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val entry = withContext(io) { fontRegistry.import(reader.read(uri, FontMetaParser.MAX_BYTES)) }
                val fonts = withContext(io) { fontRegistry.list() }
                _state.update { it.copy(busy = false, fonts = fonts, message = "Font ${entry.family} imported. Check that its licence allows your use.") }
                onImported(entry)
            } catch (e: FontException) {
                _state.update { it.copy(busy = false, message = e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = "The font could not be read: ${e.message}") }
            }
        }
    }

    fun removeFont(id: String) {
        viewModelScope.launch {
            val fonts = withContext(io) {
                fontRegistry.remove(id)
                fontRegistry.list()
            }
            _state.update { it.copy(fonts = fonts) }
        }
    }

    /** Saves [content] as a preset named [name] with the animation [intro]/[outro]. */
    fun savePreset(name: String, content: TitleContent, seconds: Double, intro: MotionPreset, outro: MotionPreset) {
        val families = _state.value
        viewModelScope.launch {
            try {
                val saved = withContext(io) {
                    presetStore.saveFrom(name, content, seconds, intro, outro, com.ultimatevideo.uveditor.domain.TitleMotion.DEFAULT_EDGE_SECONDS, families::familyOf)
                }
                val presets = withContext(io) { presetStore.list() }
                _state.update { it.copy(presets = presets, message = "Saved the preset “${saved.name}”") }
            } catch (e: PresetFormatException) {
                _state.update { it.copy(message = e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(message = "The preset could not be saved: ${e.message}") }
            }
        }
    }

    fun importPreset(uri: String) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val text = withContext(io) { reader.read(uri, TitlePresetCodec.MAX_BYTES).toString(Charsets.UTF_8) }
                val imported = withContext(io) { presetStore.import(text) }
                val presets = withContext(io) { presetStore.list() }
                val missing = imported.fonts.filter { it.id !in _state.value.fontIds }.map { it.family }
                val note = if (missing.isEmpty()) "" else ". Missing fonts (default used): ${missing.joinToString()}"
                _state.update { it.copy(busy = false, presets = presets, message = "Imported the preset “${imported.name}”$note") }
            } catch (e: PresetFormatException) {
                _state.update { it.copy(busy = false, message = e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = "The preset could not be read: ${e.message}") }
            }
        }
    }

    /** Writes preset [id] as a `.uvtitle` file at [uri]. */
    fun exportPreset(id: String, uri: String) {
        viewModelScope.launch {
            try {
                val text = withContext(io) { presetStore.exportText(id) }
                if (text == null) {
                    _state.update { it.copy(message = "That preset is no longer there") }
                    return@launch
                }
                withContext(io) { writer.write(uri, text) }
                _state.update { it.copy(message = "Preset exported") }
            } catch (e: IOException) {
                _state.update { it.copy(message = "The preset could not be written: ${e.message}") }
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            val presets = withContext(io) {
                presetStore.delete(id)
                presetStore.list()
            }
            _state.update { it.copy(presets = presets) }
        }
    }
}
