package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.ui.editor.labelRes
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.folders.DocumentKey
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.badgeLabel
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.proxyStatusOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.StickerChooser
import com.qtekfun.ultimatevideoeditor.ui.editor.TextTemplateChooser
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton
import com.qtekfun.ultimatevideoeditor.ui.editor.formatTimecode

private const val THUMBNAIL_PX = 256
private const val CARRY_ALPHA = 0.45f
private const val HINT_PREFS = "uveditor_tray"
private const val HINT_KEY = "drag_hint_seen"

/**
 * The media tray: the project's media, stickers, text templates and audio in one place. It is a bottom
 * panel on phones ([bottomPanel], with snap heights) and a side panel on wide windows. Tapping an asset (or its "+") adds
 * it at the playhead; holding it for 300 ms picks it up and it follows the finger onto the timeline (see `TrayDrag.kt`)
 * or, within the tray, to a new place in the order. Files dragged in from other apps land in the tray.
 */
@Composable
internal fun MediaTray(
    state: TrayState,
    onState: (TrayState) -> Unit,
    assets: List<MediaAssetDto>,
    items: List<TrayItem>,
    isImporting: Boolean,
    bottomPanel: Boolean,
    onImport: () -> Unit,
    onAdd: (String) -> Unit,
    onAddMany: (List<String>) -> Unit,
    drag: TrayDragController,
    onExternalFiles: (List<String>) -> Unit,
    onPickSticker: (String) -> Unit,
    onApplyTemplate: (templateId: String, text: String) -> Unit,
    folders: LinkedFoldersController,
    inProject: Set<DocumentKey>,
    onLinkFolder: () -> Unit,
    onAddFiles: (List<String>) -> Unit,
    onPreview: (SourceItem) -> Unit,
    tapPreviews: Boolean,
    modifier: Modifier = Modifier,
    userPresets: List<TextTemplate> = emptyList(),
    onApplyPreset: (TextTemplate, text: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val externalTarget = remember(onExternalFiles) { externalFilesTarget(context, onExternalFiles) }
    Surface(
        // While a tile is carried the bottom tray fades, so the timeline above it reads as the place to drop.
        modifier = modifier
            .graphicsLayer { alpha = if (bottomPanel && drag.isCarrying) CARRY_ALPHA else 1f }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> kindsOfMimes(event.mimeTypes().toList()).isNotEmpty() },
                target = externalTarget,
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // A bottom panel is mounted with wrapContentHeight: filling the height here would make it take
        // everything the editor column has left and squeeze the preview and the timeline to nothing.
        Column(if (bottomPanel) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            // The bottom body is a fraction of the window with a floor for the picture and the timeline (TraySizing); a window
            // too short for it shows the header only.
            val windowHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
            val fontScale = LocalDensity.current.fontScale
            TrayHeader(state, onState, bottomPanel, canExpand = !bottomPanel || TraySizing.canExpand(windowHeightDp, fontScale))
            val bodyDp = if (bottomPanel) TraySizing.bodyHeightDp(windowHeightDp, fontScale, state.height) else 0f
            val collapsed = bottomPanel && (state.height == TrayHeight.COLLAPSED || bodyDp <= 0f)
            if (!collapsed) {
                val bodyModifier = if (bottomPanel) Modifier.height(bodyDp.dp) else Modifier.weight(1f)
                Box(bodyModifier.fillMaxWidth()) {
                    when (state.tab) {
                        TrayTab.MEDIA, TrayTab.AUDIO -> AssetBody(
                            state, onState, items, isImporting, onImport, onAdd, onAddMany, drag,
                            onPreview = { item: TrayItem -> onPreview(SourceItem(item.asset.uri, item.name, item.kind)) }.takeIf { tapPreviews },
                        )
                        TrayTab.FOLDERS -> FoldersTab(folders, inProject, state.layout, drag, onLinkFolder, onAddFiles, onPreview.takeIf { tapPreviews })
                        TrayTab.STICKERS -> Column(Modifier.verticalScroll(rememberScrollState())) { StickerChooser(onPickSticker) }
                        TrayTab.TEMPLATES -> Column(Modifier.verticalScroll(rememberScrollState())) { TextTemplateChooser(onApplyTemplate, userPresets = userPresets, onApplyPreset = onApplyPreset) }
                    }
                }
            }
        }
    }
}

private fun externalFilesTarget(context: Context, onFiles: (List<String>) -> Unit) = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        val androidEvent = event.toAndroidDragEvent()
        val data = androidEvent.clipData ?: return false
        val uris = data.uris()
        if (uris.isEmpty()) return false
        // Access to the dropped files lasts for the lifetime of the activity; keep it for later sessions when offered.
        (context.findActivity())?.requestDragAndDropPermissions(androidEvent)
        uris.forEach { persistReadAccess(context, it) }
        onFiles(uris)
        return true
    }
}

@Composable
private fun TrayHeader(state: TrayState, onState: (TrayState) -> Unit, bottomPanel: Boolean, canExpand: Boolean) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (bottomPanel) {
                    Modifier.pointerInput(state.height) {
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                when {
                                    dragged < -threshold -> onState(state.copy(height = state.height.taller()))
                                    dragged > threshold -> onState(state.copy(height = state.height.shorter()))
                                }
                            },
                            onVerticalDrag = { _, delta -> dragged += delta },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Scrolls when the five tabs do not fit (a narrow window or large text) instead of cutting their names.
        SecondaryScrollableTabRow(
            selectedTabIndex = state.tab.ordinal,
            modifier = Modifier.weight(1f),
            containerColor = Color.Transparent,
            edgePadding = 0.dp,
        ) {
            for (tab in TrayTab.entries) {
                Tab(
                    selected = state.tab == tab,
                    onClick = { onState(state.open(tab)) },
                    text = { Text(stringResource(tab.labelRes), maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.described(stringResource(R.string.ed_s3_tab, stringResource(tab.labelRes))),
                )
            }
        }
        if (bottomPanel && (canExpand || state.height != TrayHeight.COLLAPSED)) {
            val expanded = state.height != TrayHeight.COLLAPSED
            ToolButton(
                if (expanded) EditorIcons.LaneDown else EditorIcons.LaneUp,
                stringResource(if (expanded) R.string.ed_s3_tray_shorter else R.string.ed_s3_tray_taller),
            ) {
                onState(state.copy(height = if (expanded) state.height.shorter() else state.height.taller()))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetBody(
    state: TrayState,
    onState: (TrayState) -> Unit,
    items: List<TrayItem>,
    isImporting: Boolean,
    onImport: () -> Unit,
    onAdd: (String) -> Unit,
    onAddMany: (List<String>) -> Unit,
    drag: TrayDragController,
    onPreview: ((TrayItem) -> Unit)?,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // The controls scroll on their own (at most half of the body) so a short panel or large text never pushes the list out
        // or cuts a control off.
        CappedControls {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onState(state.copy(query = it)) },
                    placeholder = { Text(stringResource(R.string.ed_s3_search), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).widthIn(min = 160.dp).defaultMinSize(minHeight = 52.dp),
                )
                if (state.tab == TrayTab.MEDIA) {
                    TextButton(onClick = { onState(state.withPicking(!state.picking)) }) {
                        Text(if (state.picking) stringResource(R.string.tray_pick_done) else stringResource(R.string.tray_pick))
                    }
                }
                TextButton(onClick = { onState(state.copy(layout = if (state.layout == TrayLayout.GRID) TrayLayout.LIST else TrayLayout.GRID)) }) {
                    Text(if (state.layout == TrayLayout.GRID) stringResource(R.string.ed_s3_list) else stringResource(R.string.ed_s3_grid))
                }
            }
            if (state.tab == TrayTab.MEDIA) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (filter in AssetFilter.entries) {
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onState(state.copy(filter = filter)) },
                            label = { Text(stringResource(filter.labelRes), fontSize = 12.sp) },
                        )
                    }
                }
            }
            if (state.picking) {
                FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        pluralStringResource(R.plurals.tray_picked_count, state.picked.size, state.picked.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).widthIn(min = 96.dp),
                    )
                    TextButton(enabled = state.picked.isNotEmpty(), onClick = { onState(state.copy(picked = emptyList())) }) {
                        Text(stringResource(R.string.tray_pick_clear))
                    }
                    Button(enabled = state.picked.isNotEmpty(), onClick = { onAddMany(state.picked); onState(state.afterAdding()) }) {
                        Text(stringResource(R.string.tray_pick_add, state.picked.size))
                    }
                }
            } else if (state.tab == TrayTab.MEDIA && items.isNotEmpty()) DragHint(drag)
        }
        val importLabel = stringResource(if (isImporting) R.string.ed_s3_importing else R.string.ed_s3_import)
        if (state.layout == TrayLayout.GRID) {
            val tileMin = TraySizing.tileMinWidthDp(LocalConfiguration.current.screenWidthDp.toFloat(), LocalDensity.current.fontScale).dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(tileMin),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "import") { ImportTile(importLabel, !isImporting, onImport) }
                items(items, key = { it.asset.id }) { item ->
                    AssetTile(item, onAdd, drag, grid = true, pickNumber = if (state.picking) state.pickNumber(item.asset.id) else null, onPreview = onPreview) { onState(state.togglePicked(item.asset.id)) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "import") { ImportTile(importLabel, !isImporting, onImport, row = true) }
                items(items, key = { it.asset.id }) { item ->
                    AssetTile(item, onAdd, drag, grid = false, pickNumber = if (state.picking) state.pickNumber(item.asset.id) else null, onPreview = onPreview) { onState(state.togglePicked(item.asset.id)) }
                }
            }
        }
    }
}

@Composable
private fun ImportTile(label: String, enabled: Boolean, onImport: () -> Unit, row: Boolean = false) {
    Box(
        modifier = Modifier
            .then(if (row) Modifier.fillMaxWidth().heightIn(min = 48.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.ed_s3_import_files_into_the_media), onClick = onImport),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.ed_s3_plus_label, label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun AssetTile(
    item: TrayItem,
    onAdd: (String) -> Unit,
    drag: TrayDragController,
    grid: Boolean,
    /** Null when the tray is not choosing; otherwise this tile's number (0 when it is not chosen yet). */
    pickNumber: Int? = null,
    /** When set, a tap on the tile opens a preview of the clip instead of adding it (the "+" still adds at once). */
    onPreview: ((TrayItem) -> Unit)? = null,
    onTogglePick: () -> Unit = {},
) {
    val context = LocalContext.current
    val asset = item.asset
    val thumbnail by produceState<ImageBitmap?>(null, asset.id, asset.uri) {
        value = AssetThumbnails.load(context, asset, THUMBNAIL_PX)?.asImageBitmap()
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(asset.id) { onDispose { drag.unregisterTile(asset.id) } }
    val haptics = LocalHapticFeedback.current
    val dragShape = RoundedCornerShape(8.dp)
    val description = describe(item)
    val addToTimeline = stringResource(R.string.ed_s3_add_to_timeline)
    val previewAction = stringResource(R.string.ed_sp_preview, item.name)
    val body = Modifier
        .graphicsLayer { alpha = if (drag.carry?.assetId == asset.id) 0.35f else 1f }
        .clip(dragShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics {
            contentDescription = description
            customActions = buildList {
                add(CustomAccessibilityAction(addToTimeline) { onAdd(asset.id); true })
                if (onPreview != null) add(CustomAccessibilityAction(previewAction) { onPreview(item); true })
            }
        }
        .onGloballyPositioned {
            coordinates = it
            drag.registerTile(asset.id, it)
        }
        .clickable(
            role = Role.Button,
            onClickLabel = when {
                pickNumber != null -> stringResource(R.string.tray_pick_toggle)
                onPreview != null -> previewAction
                else -> stringResource(R.string.ed_s3_add_at_the_playhead)
            },
        ) {
            when {
                pickNumber != null -> if (!item.missing) onTogglePick()
                // A missing file has nothing to preview; the tile already says so.
                onPreview != null -> if (!item.missing) onPreview(item) else onAdd(asset.id)
                else -> onAdd(asset.id)
            }
        }
        // After clickable, so it is the inner one and sees the finger first: once a tile is picked up it consumes the lift,
        // which keeps the click from also adding the clip. Before the hold ends it consumes nothing.
        .pointerInput(asset.id, item.missing) {
            detectTrayDrag(
                controller = drag,
                enabled = !item.missing && pickNumber == null,
                carryAt = { root ->
                    val tile = coordinates?.takeIf { it.isAttached }
                    val home = tile?.boundsInRoot()?.center ?: root
                    TrayCarry(asset.id, item.name, durationLabel(asset), item.kind, thumbnail, root, home)
                },
                rootOf = { local -> coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local },
                onPickedUp = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
    if (grid) {
        Box(body.aspectRatio(1f)) {
            TileContent(item, thumbnail, showName = true)
            if (pickNumber == null) AddButton(item, onAdd, Modifier.align(Alignment.TopEnd)) else PickBadge(pickNumber, Modifier.align(Alignment.TopEnd))
        }
    } else {
        Row(body.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) { TileContent(item, thumbnail, showName = false) }
            Column(Modifier.padding(horizontal = 8.dp).weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    metaLine(item),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pickNumber == null) AddButton(item, onAdd, Modifier) else PickBadge(pickNumber, Modifier)
        }
    }
}

/** The circle in a tile's corner while choosing: empty until chosen, then its place in the order. */
@Composable
private fun PickBadge(number: Int, modifier: Modifier) {
    val chosen = number > 0
    val description = if (chosen) stringResource(R.string.tray_pick_number, number) else stringResource(R.string.tray_pick_toggle)
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(if (chosen) MaterialTheme.colorScheme.primary else Color(0x66000000))
            .border(2.dp, Color.White, CircleShape)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** The visible way to add a clip for people who do not know the hold-and-drag: puts it at the playhead, like a tap on the tile. */
@Composable
private fun AddButton(item: TrayItem, onAdd: (String) -> Unit, modifier: Modifier) {
    if (item.missing) return
    Box(
        modifier = modifier
            .padding(3.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ed_s3_add_at_the_playhead_2, item.name)) { onAdd(item.asset.id) }
            // The tile already offers the "Add to timeline" action to screen readers; this button is the same thing for the eye.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** A one-line, dismissible hint above the tiles, shown until the first drag (or "Got it") and then never again. */
@Composable
private fun DragHint(drag: TrayDragController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HINT_PREFS, Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean(HINT_KEY, false)) }
    val dismiss = {
        seen = true
        prefs.edit().putBoolean(HINT_KEY, true).apply()
    }
    // Having dragged once, the person knows.
    LaunchedEffect(drag.carry == null) { if (drag.carry != null) dismiss() }
    if (seen) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.ed_s3_hold_a_clip_and_drag),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = dismiss) { Text(stringResource(R.string.ed_s3_got_it), fontSize = 12.sp) }
    }
}

@Composable
private fun TileContent(item: TrayItem, thumbnail: ImageBitmap?, showName: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (thumbnail != null) {
            Image(bitmap = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = when (item.kind) {
                    AssetKind.AUDIO -> "♪"
                    AssetKind.PHOTO -> "▣"
                    AssetKind.VIDEO -> "▶"
                },
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Badge(durationLabel(item.asset), Alignment.BottomEnd)
        Row(Modifier.align(Alignment.TopStart)) {
            colourBadge(item.asset)?.let { InlineBadge(it, MaterialTheme.colorScheme.tertiary) }
            if (item.usage > 0) InlineBadge("×${item.usage}", MaterialTheme.colorScheme.primary)
        }
        proxyStatusOf(item.asset.id).badgeLabel()?.let { Badge(it.asString(), Alignment.CenterEnd, MaterialTheme.colorScheme.secondary) }
        if (showName) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.62f).background(Color(0x99000000)).padding(horizontal = 3.dp),
            )
        }
        if (item.missing) {
            Box(Modifier.fillMaxSize().background(Color(0xAAB00020)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ed_s3_missing), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InlineBadge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier.padding(2.dp).background(color, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
    )
}

@Composable
private fun BoxScope.Badge(
    text: String,
    align: Alignment,
    color: Color = Color(0xCC000000),
) {
    if (text.isEmpty()) return
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier.align(align).padding(2.dp).background(color, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
    )
}

internal fun durationLabel(asset: MediaAssetDto): String =
    if (asset.isImage) "" else formatTimecode(asset.durationFrames, FrameRate(asset.nativeFpsNum, asset.nativeFpsDen))

@Composable
private fun metaLine(item: TrayItem): String {
    val kind = stringResource(item.kind.labelRes())
    val used = if (item.usage > 0) stringResource(R.string.ed_s3_used_times, item.usage) else null
    val missing = if (item.missing) stringResource(R.string.ed_s3_missing_lc) else null
    return listOfNotNull(kind, durationLabel(item.asset).takeIf { it.isNotEmpty() }, colourBadge(item.asset), used, missing).joinToString(" · ")
}

/** What a screen reader says for a tile, including how to use it. */
@Composable
internal fun describe(item: TrayItem): String = stringResource(R.string.ed_s3_tile_description, item.name, metaLine(item))

/**
 * A block of controls above a list. It takes the height it needs, but scrolls inside half of the body, so in a short panel
 * (a landscape window, large text) the list below keeps room and no control is cut off.
 */
@Composable
internal fun CappedControls(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = maxHeight * TraySizing.CONTROLS_MAX_FRACTION).verticalScroll(rememberScrollState()), content = content)
    }
}
