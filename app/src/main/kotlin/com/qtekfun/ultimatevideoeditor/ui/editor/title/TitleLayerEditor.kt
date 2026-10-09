package com.qtekfun.ultimatevideoeditor.ui.editor.title

import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.ui.editor.labelText
import com.qtekfun.ultimatevideoeditor.ui.editor.labelRes
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.FontEntry
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.LayerBox
import com.qtekfun.ultimatevideoeditor.domain.LayerPlacement
import com.qtekfun.ultimatevideoeditor.domain.LayerShadow
import com.qtekfun.ultimatevideoeditor.domain.LayerStroke
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import com.qtekfun.ultimatevideoeditor.engine.still.StickerIds
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.InspectorSlider
import kotlin.math.roundToInt

/** What the title editor needs from the outside: the font and preset libraries and what to do with them. */
data class TitleTools(
    val fonts: List<FontEntry> = emptyList(),
    val presets: List<TextTemplate> = emptyList(),
    val message: UiText? = null,
    val onImportFont: () -> Unit = {},
    val onSavePreset: (name: String, content: TitleContent, intro: MotionPreset, outro: MotionPreset, seconds: Double) -> Unit = { _, _, _, _, _ -> },
    val onImportPreset: () -> Unit = {},
    val onExportPreset: (TextTemplate) -> Unit = {},
    val onDeletePreset: (TextTemplate) -> Unit = {},
    val onClearMessage: () -> Unit = {},
) {
    companion object {
        val NONE = TitleTools()
    }
}

/**
 * The editor of a multilayer title, shown in the inspector instead of the single-text controls. A list
 * of layers (top first) with add, reorder, copy and remove, the controls of the selected layer
 * (text, shape or picture, plus its placement), the in and out animation, and the preset library.
 * Every edit goes through `UpdateTitle`/`EndTitleEdit`, so each is one undo step; the selected layer
 * can also be dragged, pinched and twisted on the preview.
 */
@Composable
fun TitleLayerEditor(
    title: TitleContent,
    clipId: String,
    selectedLayer: Int?,
    assets: List<MediaAssetDto>,
    clipSeconds: Double,
    tools: TitleTools,
    onIntent: (EditorIntent) -> Unit,
) {
    val live: (TitleContent) -> Unit = { onIntent(EditorIntent.UpdateTitle(it)) }
    val commit: (TitleContent) -> Unit = {
        onIntent(EditorIntent.UpdateTitle(it))
        onIntent(EditorIntent.EndTitleEdit(commit = true))
    }
    val end = EditorIntent.EndTitleEdit(commit = true)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ed_s3_layers), style = MaterialTheme.typography.titleSmall)
        AddLayerRow(title, assets, onAdd = { layer ->
            commit(TitleLayerEdit.add(title, layer))
            onIntent(EditorIntent.SelectTitleLayer(minOf(title.layers.size, com.qtekfun.ultimatevideoeditor.domain.TitleLayers.MAX_LAYERS - 1)))
        })
        // The list is top layer first, like the stacking order on screen.
        for (index in title.layers.indices.reversed()) {
            val layer = title.layers[index]
            LayerRow(
                label = layer.labelText().asString(),
                selected = index == selectedLayer,
                canMoveUp = index < title.layers.lastIndex,
                canMoveDown = index > 0,
                canRemove = title.layers.size > 1,
                onSelect = { onIntent(EditorIntent.SelectTitleLayer(if (index == selectedLayer) -1 else index)) },
                onUp = {
                    commit(TitleLayerEdit.move(title, index, 1))
                    onIntent(EditorIntent.SelectTitleLayer(index + 1))
                },
                onDown = {
                    commit(TitleLayerEdit.move(title, index, -1))
                    onIntent(EditorIntent.SelectTitleLayer(index - 1))
                },
                onCopy = {
                    commit(TitleLayerEdit.duplicate(title, index))
                    onIntent(EditorIntent.SelectTitleLayer(index + 1))
                },
                onRemove = {
                    commit(TitleLayerEdit.remove(title, index))
                    onIntent(EditorIntent.SelectTitleLayer(-1))
                },
            )
        }

        val selectedIndex = selectedLayer?.takeIf { it in title.layers.indices }
        if (selectedIndex != null) {
            val layer = title.layers[selectedIndex]
            val replace: (TitleLayer) -> TitleContent = { TitleLayerEdit.replace(title, selectedIndex, it) }
            when (layer) {
                is TextLayer -> TextLayerControls(layer, tools, clipId, selectedIndex, replace, live, commit, end, onIntent)
                is ShapeLayer -> ShapeLayerControls(layer, replace, live, commit, end, onIntent)
                is ImageLayer -> ImageLayerControls(layer, assets, replace, live, commit, end, onIntent)
            }
            PlacementControls(layer, replace, live, end, onIntent)
        } else {
            Text(
                stringResource(R.string.ed_s3_tap_a_layer_to_edit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var intro by remember(clipId) { mutableStateOf(MotionPreset.NONE) }
        var outro by remember(clipId) { mutableStateOf(MotionPreset.NONE) }
        MotionSection(intro, outro, onIntro = { intro = it }, onOutro = { outro = it }) {
            onIntent(EditorIntent.ApplyTitleMotion(intro, outro))
        }
        PresetSection(title, clipSeconds, intro, outro, tools)
    }
}

@Composable
private fun AddLayerRow(title: TitleContent, assets: List<MediaAssetDto>, onAdd: (TitleLayer) -> Unit) {
    var shapeMenu by remember { mutableStateOf(false) }
    var stickerMenu by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    val full = title.layers.size >= com.qtekfun.ultimatevideoeditor.domain.TitleLayers.MAX_LAYERS
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = !full, onClick = { onAdd(TitleLayerEdit.newText()) }) { Text(stringResource(R.string.ed_s3_text)) }
        Box {
            TextButton(enabled = !full, onClick = { shapeMenu = true }) { Text(stringResource(R.string.ed_s3_shape)) }
            DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
                for (kind in ShapeKind.entries) {
                    DropdownMenuItem(text = { Text(stringResource(kind.labelRes())) }, onClick = {
                        shapeMenu = false
                        onAdd(TitleLayerEdit.newShape(kind))
                    })
                }
            }
        }
        Box {
            TextButton(enabled = !full, onClick = { stickerMenu = true }) { Text(stringResource(R.string.ed_s3_sticker)) }
            DropdownMenu(expanded = stickerMenu, onDismissRequest = { stickerMenu = false }) {
                for (sticker in StickerIds.all) {
                    DropdownMenuItem(text = { Text(sticker.labelText().asString()) }, onClick = {
                        stickerMenu = false
                        onAdd(TitleLayerEdit.newImage(StillKind.STICKER, sticker.id))
                    })
                }
            }
        }
        val photos = assets.filter { it.isImage }
        Box {
            TextButton(enabled = !full && photos.isNotEmpty(), onClick = { photoMenu = true }) { Text(stringResource(R.string.ed_s3_photo)) }
            DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                for ((n, photo) in photos.withIndex()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ed_s3_photo_2, n + 1)) }, onClick = {
                        photoMenu = false
                        onAdd(TitleLayerEdit.newImage(StillKind.PHOTO, photo.id))
                    })
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    label: String,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onSelect: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).described(stringResource(if (selected) R.string.ed_s3_layer_row_selected else R.string.ed_s3_layer_row, label)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = canMoveUp, onClick = onUp) { Text(stringResource(R.string.ed_2b_up)) }
                TextButton(enabled = canMoveDown, onClick = onDown) { Text(stringResource(R.string.ed_2b_down)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.common_copy)) }
                TextButton(enabled = canRemove, onClick = onRemove) { Text(stringResource(R.string.ed_2b_remove)) }
            }
        }
    }
}

// region text

@Composable
private fun TextLayerControls(
    layer: TextLayer,
    tools: TitleTools,
    clipId: String,
    index: Int,
    replace: (TitleLayer) -> TitleContent,
    live: (TitleContent) -> Unit,
    commit: (TitleContent) -> Unit,
    end: EditorIntent,
    onIntent: (EditorIntent) -> Unit,
) {
    var text by remember(clipId, index) { mutableStateOf(layer.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            live(replace(layer.copy(text = it)))
        },
        label = { Text(stringResource(R.string.ed_s3_text_2)) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onIntent(end) },
    )
    InspectorSlider(stringResource(R.string.ed_s3_f_size), layer.sizeFraction.toFloat().coerceIn(SIZE_MIN, SIZE_MAX), SIZE_MIN..SIZE_MAX, "${(layer.sizeFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(sizeFraction = it.toDouble())))
    }
    SwatchRow(stringResource(R.string.ed_2b_colour), layer.colorArgb) { commit(replace(layer.copy(colorArgb = it))) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_2b_align), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
        for (alignment in TitleAlignment.entries) {
            TextButton(onClick = { commit(replace(layer.copy(alignment = alignment))) }) {
                Text(
                    alignment.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = if (alignment == layer.alignment) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = layer.bold, onClick = { commit(replace(layer.copy(bold = !layer.bold))) }, label = { Text(stringResource(R.string.ed_2b_bold)) })
        FilterChip(selected = layer.italic, onClick = { commit(replace(layer.copy(italic = !layer.italic))) }, label = { Text(stringResource(R.string.ed_s3_italic)) })
        FilterChip(
            selected = layer.border != null,
            onClick = { commit(replace(layer.copy(border = if (layer.border == null) LayerStroke(BLACK, LayerStroke.DEFAULT_WIDTH) else null))) },
            label = { Text(stringResource(R.string.ed_s3_border)) },
        )
        FilterChip(
            selected = layer.shadow != null,
            onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
            label = { Text(stringResource(R.string.ed_s3_shadow)) },
        )
        FilterChip(
            selected = layer.box != null,
            onClick = { commit(replace(layer.copy(box = if (layer.box == null) LayerBox.DEFAULT else null))) },
            label = { Text(stringResource(R.string.ed_s3_box)) },
        )
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_spacing), layer.letterSpacing.toFloat(), -0.1f..0.5f, "%.2f em".format(layer.letterSpacing), onIntent, end) {
        live(replace(layer.copy(letterSpacing = it.toDouble())))
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_line_height), layer.lineHeight.toFloat().coerceIn(0.8f, 2.0f), 0.8f..2.0f, "%.2f".format(layer.lineHeight), onIntent, end) {
        live(replace(layer.copy(lineHeight = it.toDouble())))
    }
    layer.border?.let { border ->
        SwatchRow(stringResource(R.string.ed_s3_f_border), border.colorArgb) { commit(replace(layer.copy(border = border.copy(colorArgb = it)))) }
        InspectorSlider(stringResource(R.string.ed_s3_f_border_w), border.widthFraction.toFloat(), 0.001f..0.02f, "%.1f%%".format(border.widthFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(border = border.copy(widthFraction = it.toDouble()))))
        }
    }
    layer.shadow?.let { shadow -> ShadowControls(shadow, onIntent, end) { live(replace(layer.copy(shadow = it))) } }
    layer.box?.let { box ->
        SwatchRow(stringResource(R.string.ed_s3_f_box), box.colorArgb) { commit(replace(layer.copy(box = box.copy(colorArgb = it)))) }
        InspectorSlider(stringResource(R.string.ed_2a_padding), box.paddingFraction.toFloat(), 0f..0.06f, "%.1f%%".format(box.paddingFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(box = box.copy(paddingFraction = it.toDouble()))))
        }
        InspectorSlider(stringResource(R.string.ed_s3_f_corners), box.cornerRadiusFraction.toFloat(), 0f..0.06f, "%.1f%%".format(box.cornerRadiusFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(box = box.copy(cornerRadiusFraction = it.toDouble()))))
        }
    }
    FontChooser(layer.fontId, tools) { commit(replace(layer.copy(fontId = it))) }
}

@Composable
private fun FontChooser(selected: String?, tools: TitleTools, onPick: (String?) -> Unit) {
    Text(stringResource(R.string.ed_s3_font), style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text(stringResource(R.string.ed_s3_system)) })
        for (font in tools.fonts) {
            FilterChip(selected = selected == font.id, onClick = { onPick(font.id) }, label = { Text(font.family) })
        }
        TextButton(onClick = tools.onImportFont) { Text(stringResource(R.string.ed_s3_import_font)) }
    }
    if (selected != null && tools.fonts.none { it.id == selected }) {
        Text(
            stringResource(R.string.ed_s3_this_font_is_not_on),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        stringResource(R.string.ed_s3_fonts_you_import_stay_on),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// endregion

// region shape and picture

@Composable
private fun ShapeLayerControls(
    layer: ShapeLayer,
    replace: (TitleLayer) -> TitleContent,
    live: (TitleContent) -> Unit,
    commit: (TitleContent) -> Unit,
    end: EditorIntent,
    onIntent: (EditorIntent) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in ShapeKind.entries) {
            val name = stringResource(if (kind == ShapeKind.ROUNDED_RECT) R.string.ed_s3_shape_rounded else kind.labelRes())
            FilterChip(selected = layer.kind == kind, onClick = { commit(replace(layer.copy(kind = kind))) }, label = { Text(name) })
        }
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_width), layer.widthFraction.toFloat().coerceIn(0.02f, 1.5f), 0.02f..1.5f, "${(layer.widthFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(widthFraction = it.toDouble())))
    }
    val heightRange = if (layer.kind == ShapeKind.LINE) 0.002f..0.05f else 0.02f..1.5f
    InspectorSlider(
        stringResource(if (layer.kind == ShapeKind.LINE) R.string.ed_s3_f_thickness else R.string.ed_s3_f_height),
        layer.heightFraction.toFloat().coerceIn(heightRange.start, heightRange.endInclusive),
        heightRange,
        "${(layer.heightFraction * PERCENT).roundToInt()}%",
        onIntent,
        end,
    ) { live(replace(layer.copy(heightFraction = it.toDouble()))) }
    if (layer.kind == ShapeKind.ROUNDED_RECT) {
        InspectorSlider(stringResource(R.string.ed_s3_f_corners), layer.cornerRadiusFraction.toFloat(), 0f..0.5f, "${(layer.cornerRadiusFraction * PERCENT).roundToInt()}%", onIntent, end) {
            live(replace(layer.copy(cornerRadiusFraction = it.toDouble())))
        }
    }
    SwatchRow(stringResource(R.string.ed_s3_f_fill), layer.fillArgb) { commit(replace(layer.copy(fillArgb = it))) }
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = layer.stroke != null,
            onClick = { commit(replace(layer.copy(stroke = if (layer.stroke == null) LayerStroke(WHITE, LayerStroke.DEFAULT_WIDTH) else null))) },
            label = { Text(stringResource(R.string.ed_s3_outline)) },
        )
        FilterChip(
            selected = layer.shadow != null,
            onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
            label = { Text(stringResource(R.string.ed_s3_shadow)) },
        )
    }
    layer.stroke?.let { stroke ->
        SwatchRow(stringResource(R.string.ed_s3_f_outline), stroke.colorArgb) { commit(replace(layer.copy(stroke = stroke.copy(colorArgb = it)))) }
        InspectorSlider(stringResource(R.string.ed_s3_f_outline_w), stroke.widthFraction.toFloat(), 0.001f..0.02f, "%.1f%%".format(stroke.widthFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(stroke = stroke.copy(widthFraction = it.toDouble()))))
        }
    }
    layer.shadow?.let { shadow -> ShadowControls(shadow, onIntent, end) { live(replace(layer.copy(shadow = it))) } }
}

@Composable
private fun ImageLayerControls(
    layer: ImageLayer,
    assets: List<MediaAssetDto>,
    replace: (TitleLayer) -> TitleContent,
    live: (TitleContent) -> Unit,
    commit: (TitleContent) -> Unit,
    end: EditorIntent,
    onIntent: (EditorIntent) -> Unit,
) {
    if (layer.kind == StillKind.PHOTO && assets.none { it.id == layer.id }) {
        Text(stringResource(R.string.ed_s3_this_photo_is_no_longer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_size), layer.sizeFraction.toFloat().coerceIn(0.05f, 1.5f), 0.05f..1.5f, "${(layer.sizeFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(sizeFraction = it.toDouble())))
    }
    FilterChip(
        selected = layer.shadow != null,
        onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
        label = { Text(stringResource(R.string.ed_s3_shadow)) },
    )
    layer.shadow?.let { shadow -> ShadowControls(shadow, onIntent, end) { live(replace(layer.copy(shadow = it))) } }
}

@Composable
private fun ShadowControls(shadow: LayerShadow, onIntent: (EditorIntent) -> Unit, end: EditorIntent, onChange: (LayerShadow) -> Unit) {
    InspectorSlider(stringResource(R.string.ed_s3_f_shadow_blur), shadow.blurFraction.toFloat(), 0f..0.03f, "%.1f%%".format(shadow.blurFraction * PERCENT), onIntent, end) {
        onChange(shadow.copy(blurFraction = it.toDouble()))
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_shadow_x), shadow.dxFraction.toFloat(), -0.03f..0.03f, "%.1f%%".format(shadow.dxFraction * PERCENT), onIntent, end) {
        onChange(shadow.copy(dxFraction = it.toDouble()))
    }
    InspectorSlider(stringResource(R.string.ed_s3_f_shadow_y), shadow.dyFraction.toFloat(), -0.03f..0.03f, "%.1f%%".format(shadow.dyFraction * PERCENT), onIntent, end) {
        onChange(shadow.copy(dyFraction = it.toDouble()))
    }
}

// endregion

@Composable
private fun PlacementControls(
    layer: TitleLayer,
    replace: (TitleLayer) -> TitleContent,
    live: (TitleContent) -> Unit,
    end: EditorIntent,
    onIntent: (EditorIntent) -> Unit,
) {
    val p = layer.placement
    val change: (LayerPlacement) -> Unit = { live(replace(layer.withPlacement(it))) }
    Text(stringResource(R.string.ed_s3_placement_in_the_title), style = MaterialTheme.typography.labelMedium)
    InspectorSlider(stringResource(R.string.ed_s3_f_across), p.offsetX.toFloat().coerceIn(-1f, 1f), -1f..1f, "${(p.offsetX * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(offsetX = it.toDouble())) }
    InspectorSlider(stringResource(R.string.ed_s3_f_down), p.offsetY.toFloat().coerceIn(-1f, 1f), -1f..1f, "${(p.offsetY * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(offsetY = it.toDouble())) }
    InspectorSlider(stringResource(R.string.ed_2b_scale), p.scale.toFloat().coerceIn(0.1f, 4f), 0.1f..4f, "${(p.scale * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(scale = it.toDouble())) }
    InspectorSlider(stringResource(R.string.ed_2b_rotation), p.rotationDegrees.toFloat().coerceIn(-180f, 180f), -180f..180f, "${p.rotationDegrees.roundToInt()}°", onIntent, end) { change(p.copy(rotationDegrees = it.toDouble())) }
    InspectorSlider(stringResource(R.string.ed_2b_opacity), p.opacity.toFloat(), 0f..1f, "${(p.opacity * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(opacity = it.toDouble())) }
}

@Composable
private fun MotionSection(intro: MotionPreset, outro: MotionPreset, onIntro: (MotionPreset) -> Unit, onOutro: (MotionPreset) -> Unit, apply: () -> Unit) {
    Text(stringResource(R.string.ed_2b_animation), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.ed_s3_in), style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in MotionPreset.entries) FilterChip(selected = intro == preset, onClick = { onIntro(preset) }, label = { Text(stringResource(preset.labelRes())) })
    }
    Text(stringResource(R.string.ed_s3_out), style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in MotionPreset.entries) FilterChip(selected = outro == preset, onClick = { onOutro(preset) }, label = { Text(stringResource(preset.labelRes())) })
    }
    TextButton(onClick = apply) { Text(stringResource(R.string.ed_s3_apply_animation_replaces_keyframes)) }
}

@Composable
private fun PresetSection(title: TitleContent, clipSeconds: Double, intro: MotionPreset, outro: MotionPreset, tools: TitleTools) {
    var name by remember { mutableStateOf("") }
    Text(stringResource(R.string.ed_2a_presets), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.ed_s3_preset_name)) }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            // The preset keeps the in and out animation chosen above, not the clip's own keyframes.
            tools.onSavePreset(name, title, intro, outro, clipSeconds.coerceAtLeast(TextTemplate.MIN_SECONDS))
            name = ""
        }) { Text(stringResource(R.string.ed_2b_save)) }
    }
    for (preset in tools.presets) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { tools.onExportPreset(preset) }) { Text(stringResource(R.string.notif_channel_name)) }
                TextButton(onClick = { tools.onDeletePreset(preset) }) { Text(stringResource(R.string.common_delete)) }
            }
        }
    }
    TextButton(onClick = tools.onImportPreset) { Text(stringResource(R.string.ed_s3_import_a_uvtitle_file)) }
    tools.message?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = tools.onClearMessage) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

@Composable
private fun SwatchRow(label: String, selectedArgb: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
        val description = stringResource(R.string.ed_s3_swatch, label)
        val descriptionSelected = stringResource(R.string.ed_s3_swatch_selected, label)
        for (swatch in SWATCHES) {
            val selected = selectedArgb == swatch.toArgb()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(swatch.toArgb()) }
                    .described(if (selected) descriptionSelected else description),
            )
        }
    }
}

private val LABEL_WIDTH = 76.dp
private const val PERCENT = 100.0
private const val SIZE_MIN = 0.02f
private const val SIZE_MAX = 0.3f
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private val SWATCHES = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFFFD60A),
    Color(0xFFFFB300),
    Color(0xFFFF453A),
    Color(0xFFFF2D95),
    Color(0xFF32D74B),
    Color(0xFF0A84FF),
    Color(0xFF1E88E5),
    Color(0xFF111418),
    Color(0xFF000000),
)
