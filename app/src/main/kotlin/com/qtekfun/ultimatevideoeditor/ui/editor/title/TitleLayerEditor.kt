package com.qtekfun.ultimatevideoeditor.ui.editor.title

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
    val message: String? = null,
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
        Text("Layers", style = MaterialTheme.typography.titleSmall)
        AddLayerRow(title, assets, onAdd = { layer ->
            commit(TitleLayerEdit.add(title, layer))
            onIntent(EditorIntent.SelectTitleLayer(minOf(title.layers.size, com.qtekfun.ultimatevideoeditor.domain.TitleLayers.MAX_LAYERS - 1)))
        })
        // The list is top layer first, like the stacking order on screen.
        for (index in title.layers.indices.reversed()) {
            val layer = title.layers[index]
            LayerRow(
                label = layer.label,
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
                "Tap a layer to edit it, then drag, pinch or twist on the preview to move it.",
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
        TextButton(enabled = !full, onClick = { onAdd(TitleLayerEdit.newText()) }) { Text("+ Text") }
        Box {
            TextButton(enabled = !full, onClick = { shapeMenu = true }) { Text("+ Shape") }
            DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
                for ((kind, name) in listOf(ShapeKind.RECT to "Rectangle", ShapeKind.ROUNDED_RECT to "Rounded rectangle", ShapeKind.ELLIPSE to "Ellipse", ShapeKind.LINE to "Line")) {
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        shapeMenu = false
                        onAdd(TitleLayerEdit.newShape(kind))
                    })
                }
            }
        }
        Box {
            TextButton(enabled = !full, onClick = { stickerMenu = true }) { Text("+ Sticker") }
            DropdownMenu(expanded = stickerMenu, onDismissRequest = { stickerMenu = false }) {
                for (sticker in StickerIds.all) {
                    DropdownMenuItem(text = { Text(sticker.label) }, onClick = {
                        stickerMenu = false
                        onAdd(TitleLayerEdit.newImage(StillKind.STICKER, sticker.id))
                    })
                }
            }
        }
        val photos = assets.filter { it.isImage }
        Box {
            TextButton(enabled = !full && photos.isNotEmpty(), onClick = { photoMenu = true }) { Text("+ Photo") }
            DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                for ((n, photo) in photos.withIndex()) {
                    DropdownMenuItem(text = { Text("Photo ${n + 1}") }, onClick = {
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).semantics { contentDescription = "Layer $label${if (selected) ", selected" else ""}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = canMoveUp, onClick = onUp) { Text("Up") }
                TextButton(enabled = canMoveDown, onClick = onDown) { Text("Down") }
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(enabled = canRemove, onClick = onRemove) { Text("Remove") }
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
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onIntent(end) },
    )
    InspectorSlider("Size", layer.sizeFraction.toFloat().coerceIn(SIZE_MIN, SIZE_MAX), SIZE_MIN..SIZE_MAX, "${(layer.sizeFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(sizeFraction = it.toDouble())))
    }
    SwatchRow("Colour", layer.colorArgb) { commit(replace(layer.copy(colorArgb = it))) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Align", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
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
        FilterChip(selected = layer.bold, onClick = { commit(replace(layer.copy(bold = !layer.bold))) }, label = { Text("Bold") })
        FilterChip(selected = layer.italic, onClick = { commit(replace(layer.copy(italic = !layer.italic))) }, label = { Text("Italic") })
        FilterChip(
            selected = layer.border != null,
            onClick = { commit(replace(layer.copy(border = if (layer.border == null) LayerStroke(BLACK, LayerStroke.DEFAULT_WIDTH) else null))) },
            label = { Text("Border") },
        )
        FilterChip(
            selected = layer.shadow != null,
            onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
            label = { Text("Shadow") },
        )
        FilterChip(
            selected = layer.box != null,
            onClick = { commit(replace(layer.copy(box = if (layer.box == null) LayerBox.DEFAULT else null))) },
            label = { Text("Box") },
        )
    }
    InspectorSlider("Spacing", layer.letterSpacing.toFloat(), -0.1f..0.5f, "%.2f em".format(layer.letterSpacing), onIntent, end) {
        live(replace(layer.copy(letterSpacing = it.toDouble())))
    }
    InspectorSlider("Line height", layer.lineHeight.toFloat().coerceIn(0.8f, 2.0f), 0.8f..2.0f, "%.2f".format(layer.lineHeight), onIntent, end) {
        live(replace(layer.copy(lineHeight = it.toDouble())))
    }
    layer.border?.let { border ->
        SwatchRow("Border", border.colorArgb) { commit(replace(layer.copy(border = border.copy(colorArgb = it)))) }
        InspectorSlider("Border w.", border.widthFraction.toFloat(), 0.001f..0.02f, "%.1f%%".format(border.widthFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(border = border.copy(widthFraction = it.toDouble()))))
        }
    }
    layer.shadow?.let { shadow -> ShadowControls(shadow, onIntent, end) { live(replace(layer.copy(shadow = it))) } }
    layer.box?.let { box ->
        SwatchRow("Box", box.colorArgb) { commit(replace(layer.copy(box = box.copy(colorArgb = it)))) }
        InspectorSlider("Padding", box.paddingFraction.toFloat(), 0f..0.06f, "%.1f%%".format(box.paddingFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(box = box.copy(paddingFraction = it.toDouble()))))
        }
        InspectorSlider("Corners", box.cornerRadiusFraction.toFloat(), 0f..0.06f, "%.1f%%".format(box.cornerRadiusFraction * PERCENT), onIntent, end) {
            live(replace(layer.copy(box = box.copy(cornerRadiusFraction = it.toDouble()))))
        }
    }
    FontChooser(layer.fontId, tools) { commit(replace(layer.copy(fontId = it))) }
}

@Composable
private fun FontChooser(selected: String?, tools: TitleTools, onPick: (String?) -> Unit) {
    Text("Font", style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("System") })
        for (font in tools.fonts) {
            FilterChip(selected = selected == font.id, onClick = { onPick(font.id) }, label = { Text(font.family) })
        }
        TextButton(onClick = tools.onImportFont) { Text("Import font…") }
    }
    if (selected != null && tools.fonts.none { it.id == selected }) {
        Text(
            "This font is not on this device, so the default font is shown. Import the same font file to restore the look.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        "Fonts you import stay on this device. Make sure you may use them in your videos.",
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
        for ((kind, name) in listOf(ShapeKind.RECT to "Rectangle", ShapeKind.ROUNDED_RECT to "Rounded", ShapeKind.ELLIPSE to "Ellipse", ShapeKind.LINE to "Line")) {
            FilterChip(selected = layer.kind == kind, onClick = { commit(replace(layer.copy(kind = kind))) }, label = { Text(name) })
        }
    }
    InspectorSlider("Width", layer.widthFraction.toFloat().coerceIn(0.02f, 1.5f), 0.02f..1.5f, "${(layer.widthFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(widthFraction = it.toDouble())))
    }
    val heightRange = if (layer.kind == ShapeKind.LINE) 0.002f..0.05f else 0.02f..1.5f
    InspectorSlider(
        if (layer.kind == ShapeKind.LINE) "Thickness" else "Height",
        layer.heightFraction.toFloat().coerceIn(heightRange.start, heightRange.endInclusive),
        heightRange,
        "${(layer.heightFraction * PERCENT).roundToInt()}%",
        onIntent,
        end,
    ) { live(replace(layer.copy(heightFraction = it.toDouble()))) }
    if (layer.kind == ShapeKind.ROUNDED_RECT) {
        InspectorSlider("Corners", layer.cornerRadiusFraction.toFloat(), 0f..0.5f, "${(layer.cornerRadiusFraction * PERCENT).roundToInt()}%", onIntent, end) {
            live(replace(layer.copy(cornerRadiusFraction = it.toDouble())))
        }
    }
    SwatchRow("Fill", layer.fillArgb) { commit(replace(layer.copy(fillArgb = it))) }
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = layer.stroke != null,
            onClick = { commit(replace(layer.copy(stroke = if (layer.stroke == null) LayerStroke(WHITE, LayerStroke.DEFAULT_WIDTH) else null))) },
            label = { Text("Outline") },
        )
        FilterChip(
            selected = layer.shadow != null,
            onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
            label = { Text("Shadow") },
        )
    }
    layer.stroke?.let { stroke ->
        SwatchRow("Outline", stroke.colorArgb) { commit(replace(layer.copy(stroke = stroke.copy(colorArgb = it)))) }
        InspectorSlider("Outline w.", stroke.widthFraction.toFloat(), 0.001f..0.02f, "%.1f%%".format(stroke.widthFraction * PERCENT), onIntent, end) {
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
        Text("This photo is no longer in the project, so the layer is not drawn.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    InspectorSlider("Size", layer.sizeFraction.toFloat().coerceIn(0.05f, 1.5f), 0.05f..1.5f, "${(layer.sizeFraction * PERCENT).roundToInt()}%", onIntent, end) {
        live(replace(layer.copy(sizeFraction = it.toDouble())))
    }
    FilterChip(
        selected = layer.shadow != null,
        onClick = { commit(replace(layer.copy(shadow = if (layer.shadow == null) LayerShadow.DEFAULT else null))) },
        label = { Text("Shadow") },
    )
    layer.shadow?.let { shadow -> ShadowControls(shadow, onIntent, end) { live(replace(layer.copy(shadow = it))) } }
}

@Composable
private fun ShadowControls(shadow: LayerShadow, onIntent: (EditorIntent) -> Unit, end: EditorIntent, onChange: (LayerShadow) -> Unit) {
    InspectorSlider("Shadow blur", shadow.blurFraction.toFloat(), 0f..0.03f, "%.1f%%".format(shadow.blurFraction * PERCENT), onIntent, end) {
        onChange(shadow.copy(blurFraction = it.toDouble()))
    }
    InspectorSlider("Shadow x", shadow.dxFraction.toFloat(), -0.03f..0.03f, "%.1f%%".format(shadow.dxFraction * PERCENT), onIntent, end) {
        onChange(shadow.copy(dxFraction = it.toDouble()))
    }
    InspectorSlider("Shadow y", shadow.dyFraction.toFloat(), -0.03f..0.03f, "%.1f%%".format(shadow.dyFraction * PERCENT), onIntent, end) {
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
    Text("Placement in the title", style = MaterialTheme.typography.labelMedium)
    InspectorSlider("Across", p.offsetX.toFloat().coerceIn(-1f, 1f), -1f..1f, "${(p.offsetX * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(offsetX = it.toDouble())) }
    InspectorSlider("Down", p.offsetY.toFloat().coerceIn(-1f, 1f), -1f..1f, "${(p.offsetY * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(offsetY = it.toDouble())) }
    InspectorSlider("Scale", p.scale.toFloat().coerceIn(0.1f, 4f), 0.1f..4f, "${(p.scale * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(scale = it.toDouble())) }
    InspectorSlider("Rotation", p.rotationDegrees.toFloat().coerceIn(-180f, 180f), -180f..180f, "${p.rotationDegrees.roundToInt()}°", onIntent, end) { change(p.copy(rotationDegrees = it.toDouble())) }
    InspectorSlider("Opacity", p.opacity.toFloat(), 0f..1f, "${(p.opacity * PERCENT).roundToInt()}%", onIntent, end) { change(p.copy(opacity = it.toDouble())) }
}

@Composable
private fun MotionSection(intro: MotionPreset, outro: MotionPreset, onIntro: (MotionPreset) -> Unit, onOutro: (MotionPreset) -> Unit, apply: () -> Unit) {
    Text("Animation", style = MaterialTheme.typography.titleSmall)
    Text("In", style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in MotionPreset.entries) FilterChip(selected = intro == preset, onClick = { onIntro(preset) }, label = { Text(preset.label) })
    }
    Text("Out", style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in MotionPreset.entries) FilterChip(selected = outro == preset, onClick = { onOutro(preset) }, label = { Text(preset.label) })
    }
    TextButton(onClick = apply) { Text("Apply animation (replaces keyframes)") }
}

@Composable
private fun PresetSection(title: TitleContent, clipSeconds: Double, intro: MotionPreset, outro: MotionPreset, tools: TitleTools) {
    var name by remember { mutableStateOf("") }
    Text("Presets", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            // The preset keeps the in and out animation chosen above, not the clip's own keyframes.
            tools.onSavePreset(name, title, intro, outro, clipSeconds.coerceAtLeast(TextTemplate.MIN_SECONDS))
            name = ""
        }) { Text("Save") }
    }
    for (preset in tools.presets) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { tools.onExportPreset(preset) }) { Text("Export") }
                TextButton(onClick = { tools.onDeletePreset(preset) }) { Text("Delete") }
            }
        }
    }
    TextButton(onClick = tools.onImportPreset) { Text("Import a .uvtitle file…") }
    tools.message?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = tools.onClearMessage) { Text("OK") }
        }
    }
}

@Composable
private fun SwatchRow(label: String, selectedArgb: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LABEL_WIDTH))
        for (swatch in SWATCHES) {
            val selected = selectedArgb == swatch.toArgb()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(swatch.toArgb()) }
                    .semantics { contentDescription = "$label colour ${if (selected) "selected" else ""}" },
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
