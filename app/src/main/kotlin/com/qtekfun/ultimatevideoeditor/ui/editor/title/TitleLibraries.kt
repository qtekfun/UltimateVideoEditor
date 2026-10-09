package com.qtekfun.ultimatevideoeditor.ui.editor.title

import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import com.qtekfun.ultimatevideoeditor.ui.text.UiTextIOException
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.FontEntry
import com.qtekfun.ultimatevideoeditor.data.FontException
import com.qtekfun.ultimatevideoeditor.data.FontMetaParser
import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.PresetFormatException
import com.qtekfun.ultimatevideoeditor.data.TitlePresetCodec
import com.qtekfun.ultimatevideoeditor.data.TitlePresetStore
import com.qtekfun.ultimatevideoeditor.data.readAtMost
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
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
        val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw UiTextIOException(UiText.res(R.string.ed_s3_tl_cannot_open), "The file cannot be opened") // i18n-ok: log text
        return input.use { stream ->
            val bytes = stream.readAtMost(maxBytes + 1)
            if (bytes.size > maxBytes) throw UiTextIOException(UiText.res(R.string.ed_s3_tl_too_large), "The file is too large") // i18n-ok: log text
            bytes
        }
    }
}

class ContentResolverTextWriter(private val context: Context) : TextWriter {
    override fun write(uri: String, text: String) {
        val output = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw UiTextIOException(UiText.res(R.string.ed_s3_tl_cannot_open), "The file cannot be opened") // i18n-ok: log text
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

data class TitleLibraryState(
    val fonts: List<FontEntry> = emptyList(),
    val presets: List<TextTemplate> = emptyList(),
    val busy: Boolean = false,
    /** A short result or error for the user (shown once, then cleared). */
    val message: UiText? = null,
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
                _state.update { it.copy(busy = false, fonts = fonts, message = UiText.res(R.string.ed_s3_font_imported, entry.family)) }
                onImported(entry)
            } catch (e: FontException) {
                _state.update { it.copy(busy = false, message = UiText.Raw(e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = UiText.res(R.string.ed_s3_font_unreadable, reasonOf(e, UiText.Empty))) }
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
                    presetStore.saveFrom(name, content, seconds, intro, outro, com.qtekfun.ultimatevideoeditor.domain.TitleMotion.DEFAULT_EDGE_SECONDS, families::familyOf)
                }
                val presets = withContext(io) { presetStore.list() }
                _state.update { it.copy(presets = presets, message = UiText.res(R.string.ed_s3_preset_saved, saved.name)) }
            } catch (e: PresetFormatException) {
                _state.update { it.copy(message = UiText.Raw(e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_preset_save_failed, reasonOf(e, UiText.Empty))) }
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
                val message = if (missing.isEmpty()) UiText.res(R.string.ed_s3_preset_imported, imported.name)
                else UiText.res(R.string.ed_s3_preset_imported_missing_fonts, imported.name, missing.joinToString())
                _state.update { it.copy(busy = false, presets = presets, message = message) }
            } catch (e: PresetFormatException) {
                _state.update { it.copy(busy = false, message = UiText.Raw(e.message.orEmpty())) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, message = UiText.res(R.string.ed_s3_preset_unreadable, reasonOf(e, UiText.Empty))) }
            }
        }
    }

    /** Writes preset [id] as a `.uvtitle` file at [uri]. */
    fun exportPreset(id: String, uri: String) {
        viewModelScope.launch {
            try {
                val text = withContext(io) { presetStore.exportText(id) }
                if (text == null) {
                    _state.update { it.copy(message = UiText.res(R.string.ed_s3_preset_gone)) }
                    return@launch
                }
                withContext(io) { writer.write(uri, text) }
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_preset_exported)) }
            } catch (e: IOException) {
                _state.update { it.copy(message = UiText.res(R.string.ed_s3_preset_write_failed, reasonOf(e, UiText.Empty))) }
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
