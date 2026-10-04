package com.ultimatevideo.uveditor.ui.editor.tray

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
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
import com.ultimatevideo.uveditor.ui.editor.proxy.badgeLabel
import com.ultimatevideo.uveditor.ui.editor.proxy.proxyStatusOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.ui.editor.EditorIcons
import com.ultimatevideo.uveditor.ui.editor.StickerChooser
import com.ultimatevideo.uveditor.ui.editor.TextTemplateChooser
import com.ultimatevideo.uveditor.ui.editor.ToolButton
import com.ultimatevideo.uveditor.ui.editor.formatTimecode

private val BottomHalfHeight = 250.dp
private val BottomFullHeight = 420.dp
private val TileMinWidth = 96.dp
private const val THUMBNAIL_PX = 256

/**
 * The media tray: the project's media, stickers, text templates and audio in one place. It is a bottom
 * panel on phones ([bottomPanel], with snap heights) and a side panel on wide windows. Tapping an asset adds
 * it at the playhead; long-pressing it drags it onto the timeline (see `TimelineSurfaceView`) or, within
 * the tray, to a new place in the order. Files dragged in from other apps land in the tray.
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
    onAssetDragStart: (String) -> Unit,
    onReorder: (assetId: String, toIndex: Int) -> Unit,
    onExternalFiles: (List<String>) -> Unit,
    onPickSticker: (String) -> Unit,
    onApplyTemplate: (templateId: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val externalTarget = remember(onExternalFiles) { externalFilesTarget(context, onExternalFiles) }
    Surface(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event -> kindsOfMimes(event.mimeTypes().toList()).isNotEmpty() && event.isExternal() },
            target = externalTarget,
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // A bottom panel is mounted with wrapContentHeight: filling the height here would make it take
        // everything the editor column has left and squeeze the preview and the timeline to nothing.
        Column(if (bottomPanel) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            TrayHeader(state, onState, bottomPanel)
            val collapsed = bottomPanel && state.height == TrayHeight.COLLAPSED
            if (!collapsed) {
                val bodyModifier = when {
                    !bottomPanel -> Modifier.weight(1f)
                    state.height == TrayHeight.FULL -> Modifier.height(BottomFullHeight)
                    else -> Modifier.height(BottomHalfHeight)
                }
                Box(bodyModifier.fillMaxWidth()) {
                    when (state.tab) {
                        TrayTab.MEDIA, TrayTab.AUDIO -> AssetBody(
                            state, onState, assets, items, isImporting, onImport, onAdd, onAssetDragStart, onReorder,
                        )
                        TrayTab.STICKERS -> Column(Modifier.verticalScroll(rememberScrollState())) { StickerChooser(onPickSticker) }
                        TrayTab.TEMPLATES -> Column(Modifier.verticalScroll(rememberScrollState())) { TextTemplateChooser(onApplyTemplate) }
                    }
                }
            }
        }
    }
}

/** True for a drag that comes from another app (the tray's own drags carry the asset label). */
private fun DragAndDropEvent.isExternal(): Boolean = toAndroidDragEvent().clipDescription?.isTrayAsset() != true

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
private fun TrayHeader(state: TrayState, onState: (TrayState) -> Unit, bottomPanel: Boolean) {
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
        SecondaryTabRow(
            selectedTabIndex = state.tab.ordinal,
            modifier = Modifier.weight(1f),
            containerColor = Color.Transparent,
        ) {
            for (tab in TrayTab.entries) {
                Tab(
                    selected = state.tab == tab,
                    onClick = { onState(state.open(tab)) },
                    text = { Text(tab.label, maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.semantics { contentDescription = "${tab.label} tab" },
                )
            }
        }
        if (bottomPanel) {
            val expanded = state.height != TrayHeight.COLLAPSED
            ToolButton(
                if (expanded) EditorIcons.LaneDown else EditorIcons.LaneUp,
                if (expanded) "Make the media tray shorter" else "Make the media tray taller",
            ) {
                onState(state.copy(height = if (expanded) state.height.shorter() else state.height.taller()))
            }
        }
    }
}

@Composable
private fun AssetBody(
    state: TrayState,
    onState: (TrayState) -> Unit,
    assets: List<MediaAssetDto>,
    items: List<TrayItem>,
    isImporting: Boolean,
    onImport: () -> Unit,
    onAdd: (String) -> Unit,
    onAssetDragStart: (String) -> Unit,
    onReorder: (assetId: String, toIndex: Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onState(state.copy(query = it)) },
                placeholder = { Text("Search", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(52.dp),
            )
            TextButton(onClick = { onState(state.copy(layout = if (state.layout == TrayLayout.GRID) TrayLayout.LIST else TrayLayout.GRID)) }) {
                Text(if (state.layout == TrayLayout.GRID) "List" else "Grid")
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
                        label = { Text(filter.label, fontSize = 12.sp) },
                    )
                }
            }
        }
        val importLabel = if (isImporting) "Importing…" else "Import"
        if (state.layout == TrayLayout.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TileMinWidth),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "import") { ImportTile(importLabel, !isImporting, onImport) }
                items(items, key = { it.asset.id }) { item ->
                    AssetTile(item, assets, onAdd, onAssetDragStart, onReorder, grid = true)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "import") { ImportTile(importLabel, !isImporting, onImport, row = true) }
                items(items, key = { it.asset.id }) { item ->
                    AssetTile(item, assets, onAdd, onAssetDragStart, onReorder, grid = false)
                }
            }
        }
    }
}

@Composable
private fun ImportTile(label: String, enabled: Boolean, onImport: () -> Unit, row: Boolean = false) {
    Box(
        modifier = Modifier
            .then(if (row) Modifier.fillMaxWidth().height(48.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Import files into the media tray", onClick = onImport),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ $label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetTile(
    item: TrayItem,
    assets: List<MediaAssetDto>,
    onAdd: (String) -> Unit,
    onAssetDragStart: (String) -> Unit,
    onReorder: (assetId: String, toIndex: Int) -> Unit,
    grid: Boolean,
) {
    val context = LocalContext.current
    val asset = item.asset
    val thumbnail by produceState<ImageBitmap?>(null, asset.id, asset.uri) {
        value = AssetThumbnails.load(context, asset, THUMBNAIL_PX)?.asImageBitmap()
    }
    val reorderTarget = remember(asset.id, assets) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val draggedId = event.toAndroidDragEvent().clipData?.trayAssetId() ?: return false
                if (draggedId == asset.id) return false
                onReorder(draggedId, assets.indexOfFirst { it.id == asset.id })
                return true
            }
        }
    }
    val dragShape = RoundedCornerShape(8.dp)
    val description = describe(item)
    val body = Modifier
        .clip(dragShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = description }
        .clickable(role = Role.Button, onClickLabel = "Add at the playhead") { onAdd(asset.id) }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription?.isTrayAsset() == true },
            target = reorderTarget,
        )
        .dragAndDropSource(drawDragDecoration = { drawDragGhost(thumbnail) }) { _ ->
            // Called when the long press turns into a drag: the editor prepares a clip for the asset.
            onAssetDragStart(asset.id)
            DragAndDropTransferData(ClipData.newPlainText(ASSET_DRAG_LABEL, asset.id))
        }
    if (grid) {
        Box(body.aspectRatio(1f)) { TileContent(item, thumbnail, showName = true) }
    } else {
        Row(body.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) { TileContent(item, thumbnail, showName = false) }
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    metaLine(item),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        colourBadge(item.asset)?.let { Badge(it, Alignment.TopStart, MaterialTheme.colorScheme.tertiary) }
        if (item.usage > 0) Badge("×${item.usage}", Alignment.TopEnd, MaterialTheme.colorScheme.primary)
        proxyStatusOf(item.asset.id).badgeLabel()?.let { Badge(it, Alignment.CenterEnd, MaterialTheme.colorScheme.secondary) }
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
                Text("Missing", color = Color.White, fontSize = 11.sp)
            }
        }
    }
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

private fun DrawScope.drawDragGhost(thumbnail: ImageBitmap?) {
    drawRoundRect(Color(0xCC1B1F2A), size = Size(size.width, size.height), cornerRadius = CornerRadius(12f, 12f))
    if (thumbnail != null) {
        drawImage(thumbnail, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = 0.85f)
    }
}

internal fun durationLabel(asset: MediaAssetDto): String =
    if (asset.isImage) "" else formatTimecode(asset.durationFrames, FrameRate(asset.nativeFpsNum, asset.nativeFpsDen))

private fun metaLine(item: TrayItem): String = buildList {
    add(when (item.kind) { AssetKind.VIDEO -> "Video"; AssetKind.PHOTO -> "Photo"; AssetKind.AUDIO -> "Audio" })
    durationLabel(item.asset).takeIf { it.isNotEmpty() }?.let(::add)
    colourBadge(item.asset)?.let(::add)
    if (item.usage > 0) add("used ${item.usage}×")
    if (item.missing) add("missing")
}.joinToString(" · ")

/** What a screen reader says for a tile, including how to use it. */
internal fun describe(item: TrayItem): String =
    "${item.name}. ${metaLine(item)}. Tap to add at the playhead, long press to drag onto the timeline."
