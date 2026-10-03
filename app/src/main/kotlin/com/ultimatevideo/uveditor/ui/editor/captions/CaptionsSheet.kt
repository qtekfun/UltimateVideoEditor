package com.ultimatevideo.uveditor.ui.editor.captions

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.captions.CaptionEntrance
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.engine.captions.CaptionLanguage

/** Shows the captions sheet while [CaptionsViewModel] has a target, and passes its results on. */
@Composable
fun CaptionsHost(
    viewModel: CaptionsViewModel,
    onClips: (List<Clip>) -> Unit,
    onRestyle: (CaptionStyle, Int) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptionsEffect.ClipsReady -> onClips(effect.clips)
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
            Text(if (state.restyleOnly) "Caption style" else "Auto captions", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.restyleOnly) {
                    "Pick a look and apply it to the ${state.existingCaptions} captions on the timeline."
                } else {
                    "Listens to the selected clip on this device and adds the words as editable title clips on a new track."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!state.restyleOnly) {
                Section("Spoken language") { LanguagePicker(state, onIntent) }
                Section("Speech model") { ModelPicker(state, onIntent) }
            }
            Section("Style") { StylePicker(state, onIntent) }
            Section("Colours") { ColorOptions(state, onIntent) }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.busy) {
                val label = if (state.phase == CaptionPhase.DOWNLOADING) "Downloading the model" else "Listening to the clip"
                Text("$label ${state.progress}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (state.busy) {
                    OutlinedButton(onClick = { onIntent(CaptionsIntent.Cancel) }) { Text("Cancel") }
                } else {
                    if (state.restyleOnly) {
                        Button(onClick = { onIntent(CaptionsIntent.ApplyToExisting) }) { Text("Apply to ${state.existingCaptions} captions") }
                    } else {
                        Button(onClick = { onIntent(CaptionsIntent.Generate) }) {
                            Text(if (state.selectedModel?.installed == true) "Generate captions" else "Download and generate")
                        }
                        if (state.existingCaptions > 0) {
                            OutlinedButton(onClick = { onIntent(CaptionsIntent.ApplyToExisting) }) { Text("Restyle ${state.existingCaptions} existing") }
                        }
                    }
                    TextButton(onClick = { onIntent(CaptionsIntent.Close) }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun LanguagePicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = CaptionLanguage.ALL.firstOrNull { it.code == state.languageCode } ?: CaptionLanguage.ALL.first()
    OutlinedButton(onClick = { open = true }, enabled = !state.busy) { Text(current.label) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (language in CaptionLanguage.ALL) {
            DropdownMenuItem(
                text = { Text(language.label) },
                onClick = {
                    open = false
                    onIntent(CaptionsIntent.SelectLanguage(language.code))
                },
            )
        }
    }
}

@Composable
private fun ModelPicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    for (option in state.models) {
        val model = option.model
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = state.modelId == model.id, enabled = !state.busy, role = Role.RadioButton) {
                    onIntent(CaptionsIntent.SelectModel(model.id))
                },
        ) {
            RadioButton(selected = state.modelId == model.id, onClick = null, enabled = !state.busy)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text("${model.label} · ${megabytes(model.sizeBytes)} MB", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (option.installed) "Downloaded, works offline" else "${model.description} (downloaded once)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (option.installed) {
                TextButton(onClick = { onIntent(CaptionsIntent.DeleteModel(model.id)) }, enabled = !state.busy) { Text("Remove") }
            }
        }
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
            StyleCard(shown, selected, enabled = !state.busy) { onIntent(CaptionsIntent.SelectStyle(style.id)) }
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
    ColorRow("Text", style.colorArgb, enabled = !state.busy) { onIntent(CaptionsIntent.SelectTextColor(it)) }
    if (style.usesHighlight) {
        ColorRow("Highlight", style.highlightArgb, enabled = !state.busy) { onIntent(CaptionsIntent.SelectHighlightColor(it)) }
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

private fun megabytes(bytes: Long) = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
