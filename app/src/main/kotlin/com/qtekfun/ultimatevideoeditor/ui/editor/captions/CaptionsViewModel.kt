package com.qtekfun.ultimatevideoeditor.ui.editor.captions

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.readAtMost
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionAnimator
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionCue
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle
import com.qtekfun.ultimatevideoeditor.domain.captions.Subtitles
import com.qtekfun.ultimatevideoeditor.domain.captions.clipsFor
import com.qtekfun.ultimatevideoeditor.mvi.MviViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Reads the bytes of a file the user picked; [ContentResolverSubtitleSource] on the device, a fake in tests. */
fun interface SubtitleSource {
    /** @throws IOException when the file cannot be read or is not a plausible subtitle file. */
    suspend fun read(uri: String): ByteArray
}

class ContentResolverSubtitleSource(private val resolver: ContentResolver) : SubtitleSource {
    override suspend fun read(uri: String): ByteArray {
        val input = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("The file could not be opened")
        input.use { stream ->
            val bytes = stream.readAtMost((Subtitles.MAX_BYTES + 1).toInt())
            if (bytes.size > Subtitles.MAX_BYTES) throw IOException("This file is too large to be a subtitle file")
            return bytes
        }
    }
}

/**
 * Drives the "Captions" sheet: typed captions with a start and a length, subtitle files (`.srt`,
 * `.vtt`) read on the device, and the look of every caption. Everything is local: there is no
 * recognition and no network. Nothing touches the timeline from here; the caption clips are handed
 * to the editor as [CaptionsEffect.ClipsReady], so each action arrives as one ordinary undoable edit.
 */
class CaptionsViewModel(
    private val source: SubtitleSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().take(ID_LENGTH) },
) : MviViewModel<CaptionsState, CaptionsIntent, CaptionsEffect>(CaptionsState()) {

    override fun onIntent(intent: CaptionsIntent) {
        when (intent) {
            is CaptionsIntent.Open -> open(intent)
            CaptionsIntent.Close -> reduce { copy(isOpen = false, error = null) }
            // A new style brings its own colours, so the overrides of the old one are dropped.
            is CaptionsIntent.SelectStyle -> reduce { copy(styleId = CaptionStyle.byId(intent.id).id, textColor = null, highlightColor = null, error = null) }
            is CaptionsIntent.SelectTextColor -> reduce { copy(textColor = intent.argb) }
            is CaptionsIntent.SelectHighlightColor -> reduce { copy(highlightColor = intent.argb) }
            CaptionsIntent.ApplyToExisting -> applyToExisting()
            is CaptionsIntent.SetDraftText -> reduce { copy(draftText = intent.text.take(MAX_TEXT), error = null) }
            is CaptionsIntent.NudgeStart -> reduce { copy(draftStart = (draftStart + intent.deltaFrames).coerceAtLeast(0)) }
            is CaptionsIntent.NudgeLength -> reduce { copy(draftLength = (draftLength + intent.deltaFrames).coerceAtLeast(1)) }
            CaptionsIntent.AddDraft -> addDraft()
            is CaptionsIntent.SetImportAtPlayhead -> reduce { copy(importAtPlayhead = intent.enabled) }
            is CaptionsIntent.ImportFile -> importFile(intent.uri)
        }
    }

    private fun open(intent: CaptionsIntent.Open) {
        val framesPerSecond = ((intent.fps.num + intent.fps.den / 2) / intent.fps.den).coerceAtLeast(1)
        reduce {
            copy(
                isOpen = true,
                fps = intent.fps,
                canvasHeight = intent.canvasHeight,
                existingCaptions = intent.existingCaptions,
                playhead = intent.playhead,
                draftStart = intent.playhead,
                draftLength = framesPerSecond * DEFAULT_SECONDS,
                error = null,
            )
        }
    }

    private fun applyToExisting() {
        val current = state.value
        if (current.existingCaptions == 0) return
        emit(CaptionsEffect.Restyle(current.style, current.canvasHeight))
        reduce { copy(isOpen = false, error = null) }
    }

    private fun addDraft() {
        val current = state.value
        if (!current.canAdd) return
        val text = current.draftText.trim()
        val cue = CaptionCue(
            FrameIndex(current.draftStart),
            FrameIndex(current.draftStart + current.draftLength),
            text,
            CaptionAnimator.synthesizeWords(text, current.draftLength),
        )
        val clips = current.style.clipsFor(listOf(cue), current.canvasHeight, idGenerator)
        emit(CaptionsEffect.ClipsReady(clips, intoExistingTrack = true))
        // The next caption starts where this one ends, so typing a run of captions needs no timing work.
        reduce { copy(draftText = "", draftStart = draftStart + draftLength, existingCaptions = existingCaptions + 1, error = null) }
    }

    private fun importFile(uri: String) {
        val current = state.value
        if (current.importing) return
        reduce { copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                val bytes = withContext(ioDispatcher) { source.read(uri) }
                val file = Subtitles.parse(bytes)
                if (file.cues.isEmpty()) {
                    reduce { copy(importing = false, error = "No subtitles were found in this file (it must be .srt or .vtt)") }
                    return@launch
                }
                val offset = FrameIndex(if (current.importAtPlayhead) current.playhead else 0)
                val cues = Subtitles.toCues(file, current.fps, offset)
                val clips = current.style.clipsFor(cues, current.canvasHeight, idGenerator)
                emit(CaptionsEffect.ClipsReady(clips, intoExistingTrack = false))
                val skipped = if (file.skipped > 0) " (${file.skipped} unreadable blocks skipped)" else ""
                emit(CaptionsEffect.ShowMessage("Added ${clips.size} captions$skipped"))
                reduce { copy(importing = false, isOpen = false, existingCaptions = existingCaptions + clips.size) }
            } catch (e: IOException) {
                reduce { copy(importing = false, error = e.message ?: "The file could not be read") }
            }
        }
    }

    private companion object {
        const val ID_LENGTH = 8
        const val MAX_TEXT = 200
        const val DEFAULT_SECONDS = 2L
    }
}
