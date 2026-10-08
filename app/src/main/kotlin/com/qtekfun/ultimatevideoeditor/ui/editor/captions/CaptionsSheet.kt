package com.qtekfun.ultimatevideoeditor.ui.editor.captions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.qtekfun.ultimatevideoeditor.ui.editor.formatTimecode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.TitleAnimation
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionEntrance
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle

/** Shows the captions sheet while [CaptionsViewModel] has it open, and passes its results on. */
@Composable
fun CaptionsHost(
    viewModel: CaptionsViewModel,
    onClips: (List<Clip>, Boolean) -> Unit,
    onRestyle: (CaptionStyle, Int) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptionsEffect.ClipsReady -> onClips(effect.clips, effect.intoExistingTrack)
                is CaptionsEffect.Restyle -> onRestyle(effect.style, effect.canvasHeight)
                is CaptionsEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }
    if (state.isOpen) CaptionsSheet(state, viewModel::onIntent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptionsSheet(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    // The system file picker: the app never asks for storage permission, it only receives the file the user picks.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIntent(CaptionsIntent.ImportFile(uri.toString()))
    }
    ModalBottomSheet(
        onDismissRequest = { onIntent(CaptionsIntent.Close) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Captions", style = MaterialTheme.typography.titleLarge)
            Text(
                "Type captions or import a .srt / .vtt file. Everything stays on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("Add a caption") { DraftEditor(state, onIntent) }
            Section("Import subtitles") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !state.importing,
                    ) { Text(if (state.importing) "Reading…" else "Choose a .srt or .vtt file") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = state.importAtPlayhead, onCheckedChange = { onIntent(CaptionsIntent.SetImportAtPlayhead(it)) })
                    Text("Start at the playhead (otherwise at the start of the project)", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Section("Style") { StylePicker(state, onIntent) }
            Section("Colours") { ColorOptions(state, onIntent) }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (state.existingCaptions > 0) {
                    OutlinedButton(onClick = { onIntent(CaptionsIntent.ApplyToExisting) }) { Text("Restyle ${state.existingCaptions} existing") }
                }
                TextButton(onClick = { onIntent(CaptionsIntent.Close) }) { Text("Close") }
            }
        }
    }
}

/** The caption being typed: its text, and a start and length stepped by a frame or a second. */
@Composable
private fun DraftEditor(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    val second = ((state.fps.num + state.fps.den / 2) / state.fps.den).coerceAtLeast(1).toLong()
    OutlinedTextField(
        value = state.draftText,
        onValueChange = { onIntent(CaptionsIntent.SetDraftText(it)) },
        label = { Text("Caption text") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Stepper(
        label = "Starts",
        value = formatTimecode(state.draftStart, state.fps),
        onChange = { onIntent(CaptionsIntent.NudgeStart(it)) },
        second = second,
    )
    Stepper(
        label = "Lasts",
        value = formatTimecode(state.draftLength, state.fps),
        onChange = { onIntent(CaptionsIntent.NudgeLength(it)) },
        second = second,
    )
    Button(onClick = { onIntent(CaptionsIntent.AddDraft) }, enabled = state.canAdd) { Text("Add caption") }
}

@Composable
private fun Stepper(label: String, value: String, second: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp))
        TextButton(onClick = { onChange(-second) }, modifier = Modifier.semantics { contentDescription = "$label one second earlier" }) { Text("-1 s") }
        TextButton(onClick = { onChange(-1) }, modifier = Modifier.semantics { contentDescription = "$label one frame earlier" }) { Text("-1 f") }
        Text(value, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onChange(1) }, modifier = Modifier.semantics { contentDescription = "$label one frame later" }) { Text("+1 f") }
        TextButton(onClick = { onChange(second) }, modifier = Modifier.semantics { contentDescription = "$label one second later" }) { Text("+1 s") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** A row of style cards, each a small sample of the look; scrolls sideways when they do not fit. */
@Composable
private fun StylePicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        for (style in CaptionStyle.ALL) {
            val selected = state.styleId == style.id
            // The card shows the colours that would be used, so a colour pick is visible at once.
            val shown = if (selected) state.style else style
            StyleCard(shown, selected, enabled = true) { onIntent(CaptionsIntent.SelectStyle(style.id)) }
        }
    }
}

@Composable
private fun StyleCard(style: CaptionStyle, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(shape)
            .border(border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(64.dp).background(Color(SAMPLE_BACKGROUND)),
        ) {
            Text(sampleText(style), fontSize = 16.sp, fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal)
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(style.label, style = MaterialTheme.typography.labelLarge)
            Text(describe(style), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An approximation of one frame of the style, built from the same colours and emphasis the renderer uses. */
private fun sampleText(style: CaptionStyle) = buildAnnotatedString {
    val base = SpanStyle(color = Color(style.colorArgb))
    val accent = SpanStyle(color = Color(style.highlightArgb), fontSize = 19.sp)
    when (style.animation) {
        TitleAnimation.KARAOKE -> {
            withStyle(base) { append("Say it ") }
            withStyle(accent) { append("loud") }
        }
        TitleAnimation.POP_IN -> {
            withStyle(base) { append("Say it ") }
            withStyle(accent) { append("now") }
        }
        TitleAnimation.TYPEWRITER -> {
            withStyle(base) { append("Say it lo") }
            withStyle(SpanStyle(color = Color(style.colorArgb).copy(alpha = 0.25f))) { append("ud") }
        }
        TitleAnimation.NONE -> withStyle(base) { append("Say it loud") }
    }
}

private fun describe(style: CaptionStyle): String = when {
    style.animation == TitleAnimation.KARAOKE -> "Word lights up"
    style.animation == TitleAnimation.POP_IN -> "Words pop in"
    style.animation == TitleAnimation.TYPEWRITER -> "Types in"
    style.entrance == CaptionEntrance.BOUNCE -> "Bounces in"
    style.entrance == CaptionEntrance.SCALE_IN -> "Scales in"
    else -> "Static"
}

@Composable
private fun ColorOptions(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    val style = state.style
    ColorRow("Text", style.colorArgb, enabled = true) { onIntent(CaptionsIntent.SelectTextColor(it)) }
    if (style.usesHighlight) {
        ColorRow("Highlight", style.highlightArgb, enabled = true) { onIntent(CaptionsIntent.SelectHighlightColor(it)) }
    }
}

@Composable
private fun ColorRow(label: String, current: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
        for ((name, argb) in PALETTE) {
            val picked = argb == current
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        if (picked) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        CircleShape,
                    )
                    .semantics { contentDescription = "$label colour $name" }
                    .clickable(enabled = enabled, role = Role.RadioButton) { onPick(argb) },
            )
        }
    }
}

private val PALETTE = listOf(
    "white" to 0xFFFFFFFF.toInt(),
    "yellow" to 0xFFFFE600.toInt(),
    "cyan" to 0xFF00E5FF.toInt(),
    "green" to 0xFF39FF14.toInt(),
    "pink" to 0xFFFF4FD8.toInt(),
    "orange" to 0xFFFF9100.toInt(),
    "red" to 0xFFFF3B30.toInt(),
)

private const val SAMPLE_BACKGROUND = 0xFF26303B

