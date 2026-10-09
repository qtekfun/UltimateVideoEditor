package com.qtekfun.ultimatevideoeditor.ui.editor.captions

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.ui.editor.labelText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.text.AnnotatedString
import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
            Text(stringResource(R.string.ed_2a_tool_captions), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.ed_s3_type_captions_or_import_a),
                style = MaterialTheme.typography.bodyMedium,
            )

            Section(stringResource(R.string.ed_s3_section_add_caption)) { DraftEditor(state, onIntent) }
            Section(stringResource(R.string.ed_s3_section_import)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !state.importing,
                    ) { Text(if (state.importing) stringResource(R.string.ed_2b_reading) else stringResource(R.string.ed_s3_choose_a_srt_or_vtt)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = state.importAtPlayhead, onCheckedChange = { onIntent(CaptionsIntent.SetImportAtPlayhead(it)) })
                    Text(stringResource(R.string.ed_s3_start_at_the_playhead_otherwise), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Section(stringResource(R.string.ed_s3_section_style)) { StylePicker(state, onIntent) }
            Section(stringResource(R.string.ed_s3_section_colours)) { ColorOptions(state, onIntent) }

            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (state.existingCaptions > 0) {
                    OutlinedButton(onClick = { onIntent(CaptionsIntent.ApplyToExisting) }) { Text(pluralStringResource(R.plurals.ed_s3_restyle_existing, state.existingCaptions, state.existingCaptions)) }
                }
                TextButton(onClick = { onIntent(CaptionsIntent.Close) }) { Text(stringResource(R.string.common_close)) }
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
        label = { Text(stringResource(R.string.ed_s3_caption_text)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Stepper(
        label = stringResource(R.string.ed_s3_starts),
        value = formatTimecode(state.draftStart, state.fps),
        onChange = { onIntent(CaptionsIntent.NudgeStart(it)) },
        second = second,
    )
    Stepper(
        label = stringResource(R.string.ed_s3_lasts),
        value = formatTimecode(state.draftLength, state.fps),
        onChange = { onIntent(CaptionsIntent.NudgeLength(it)) },
        second = second,
    )
    Button(onClick = { onIntent(CaptionsIntent.AddDraft) }, enabled = state.canAdd) { Text(stringResource(R.string.ed_s3_add_caption)) }
}

@Composable
private fun Stepper(label: String, value: String, second: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp))
        TextButton(onClick = { onChange(-second) }, modifier = Modifier.described(stringResource(R.string.ed_s3_one_second_earlier, label))) { Text("-1 s") }
        TextButton(onClick = { onChange(-1) }, modifier = Modifier.described(stringResource(R.string.ed_s3_one_frame_earlier, label))) { Text("-1 f") }
        Text(value, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onChange(1) }, modifier = Modifier.described(stringResource(R.string.ed_s3_one_frame_later, label))) { Text("+1 f") }
        TextButton(onClick = { onChange(second) }, modifier = Modifier.described(stringResource(R.string.ed_s3_one_second_later, label))) { Text("+1 s") }
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
            Text(style.labelText().asString(), style = MaterialTheme.typography.labelLarge)
            Text(describe(style), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An approximation of one frame of the style, built from the same colours and emphasis the renderer uses. */
@Composable
private fun sampleText(style: CaptionStyle): AnnotatedString {
    val say = stringResource(R.string.ed_s3_sample_say)
    val loud = stringResource(R.string.ed_s3_sample_loud)
    val now = stringResource(R.string.ed_s3_sample_now)
    val typed = stringResource(R.string.ed_s3_sample_typed)
    val untyped = stringResource(R.string.ed_s3_sample_untyped)
    val static = stringResource(R.string.ed_s3_sample_static)
    return buildSample(style, say, loud, now, typed, untyped, static)
}

private fun buildSample(style: CaptionStyle, say: String, loud: String, now: String, typed: String, untyped: String, static: String) = buildAnnotatedString {
    val base = SpanStyle(color = Color(style.colorArgb))
    val accent = SpanStyle(color = Color(style.highlightArgb), fontSize = 19.sp)
    when (style.animation) {
        TitleAnimation.KARAOKE -> {
            withStyle(base) { append(say) }
            withStyle(accent) { append(loud) }
        }
        TitleAnimation.POP_IN -> {
            withStyle(base) { append(say) }
            withStyle(accent) { append(now) }
        }
        TitleAnimation.TYPEWRITER -> {
            withStyle(base) { append(typed) }
            withStyle(SpanStyle(color = Color(style.colorArgb).copy(alpha = 0.25f))) { append(untyped) }
        }
        TitleAnimation.NONE -> withStyle(base) { append(static) }
    }
}

@Composable
private fun describe(style: CaptionStyle): String = stringResource(
    when {
        style.animation == TitleAnimation.KARAOKE -> R.string.ed_s3_desc_karaoke
        style.animation == TitleAnimation.POP_IN -> R.string.ed_s3_desc_pop_in
        style.animation == TitleAnimation.TYPEWRITER -> R.string.ed_s3_desc_typewriter
        style.entrance == CaptionEntrance.BOUNCE -> R.string.ed_s3_desc_bounce
        style.entrance == CaptionEntrance.SCALE_IN -> R.string.ed_s3_desc_scale_in
        else -> R.string.ed_s3_desc_static
    },
)

@Composable
private fun ColorOptions(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    val style = state.style
    ColorRow(stringResource(R.string.ed_s3_color_text), style.colorArgb, enabled = true) { onIntent(CaptionsIntent.SelectTextColor(it)) }
    if (style.usesHighlight) {
        ColorRow(stringResource(R.string.ed_s3_color_highlight), style.highlightArgb, enabled = true) { onIntent(CaptionsIntent.SelectHighlightColor(it)) }
    }
}

@Composable
private fun ColorRow(label: String, current: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
        for ((nameRes, argb) in PALETTE) {
            val name = stringResource(nameRes)
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
                    .described(stringResource(R.string.ed_s3_colour, label, name))
                    .clickable(enabled = enabled, role = Role.RadioButton) { onPick(argb) },
            )
        }
    }
}

private val PALETTE = listOf(
    R.string.ed_s3_col_white to 0xFFFFFFFF.toInt(),
    R.string.ed_s3_col_yellow to 0xFFFFE600.toInt(),
    R.string.ed_s3_col_cyan to 0xFF00E5FF.toInt(),
    R.string.ed_s3_col_green to 0xFF39FF14.toInt(),
    R.string.ed_s3_col_pink to 0xFFFF4FD8.toInt(),
    R.string.ed_s3_col_orange to 0xFFFF9100.toInt(),
    R.string.ed_s3_col_red to 0xFFFF3B30.toInt(),
)

private const val SAMPLE_BACKGROUND = 0xFF26303B

