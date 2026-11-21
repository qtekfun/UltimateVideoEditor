package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.folders.BrowseProblem
import com.qtekfun.ultimatevideoeditor.data.folders.DocumentKey
import com.qtekfun.ultimatevideoeditor.data.folders.FolderEntry
import com.qtekfun.ultimatevideoeditor.data.folders.LinkedFolder
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton

private val FolderTileMinWidth = 96.dp
private const val FOLDER_THUMBNAIL_PX = 256

private fun RelinkKind.assetKind(): AssetKind = when (this) {
    RelinkKind.VIDEO -> AssetKind.VIDEO
    RelinkKind.AUDIO -> AssetKind.AUDIO
    RelinkKind.IMAGE -> AssetKind.PHOTO
}

/**
 * The tray's Folders tab: the folders the user linked and, inside one, its own tree of subfolders and media files. A file is added to
 * the project when it is used (a tap, the "+" or a numbered choice); until then the project knows nothing about it. [inProject] holds the
 * identities of the files that already are in the project, so they can be marked however they were reached.
 */
@Composable
internal fun FoldersTab(
    controller: LinkedFoldersController,
    inProject: Set<DocumentKey>,
    layout: TrayLayout,
    drag: TrayDragController,
    onLinkFolder: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onPreview: ((SourceItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val browse = controller.browse
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (browse.atTop) {
            LinkedList(controller, onLinkFolder)
        } else {
            FolderView(controller, inProject, layout, drag, onLinkFolder, onAdd, onPreview)
        }
    }
}

@Composable
private fun LinkedList(controller: LinkedFoldersController, onLinkFolder: () -> Unit) {
    var renaming by remember { mutableStateOf<LinkedFolder?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_fl_linked_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Button(onClick = onLinkFolder) { Text(stringResource(R.string.ed_fl_link)) }
    }
    if (controller.linkRefused) {
        Text(stringResource(R.string.ed_fl_refused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (controller.folders.isEmpty()) {
        Text(
            stringResource(R.string.ed_fl_none),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Text(
        stringResource(R.string.ed_fl_explain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(controller.folders, key = { it.id }) { folder ->
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ed_fl_open_desc, folder.label)) { controller.open(folder) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.ed_fl_options), maxLines = 1, fontSize = 12.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, onClick = { menu = false; renaming = folder })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ed_fl_unlink)) }, onClick = { menu = false; controller.unlink(folder.id) })
                    }
                }
            }
        }
    }
    renaming?.let { folder ->
        var text by remember(folder.id) { mutableStateOf(folder.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.ed_fl_rename_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { controller.rename(folder.id, text); renaming = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun FolderView(
    controller: LinkedFoldersController,
    inProject: Set<DocumentKey>,
    layout: TrayLayout,
    drag: TrayDragController,
    onLinkFolder: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onPreview: ((SourceItem) -> Unit)?,
) {
    val browse = controller.browse
    // The path: one piece per folder from the top of the linked folder; a piece jumps back to that folder.
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolButton(EditorIcons.ChevronLeft, stringResource(R.string.ed_fl_up)) { controller.up() }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            browse.trail.forEachIndexed { index, crumb ->
                if (index > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = index == browse.trail.lastIndex
                TextButton(enabled = !last, onClick = { controller.jumpTo(index) }) {
                    Text(
                        crumb.name,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        TextButton(onClick = controller::refresh) { Text(stringResource(R.string.ed_fl_refresh), fontSize = 12.sp) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = browse.query,
            onValueChange = controller::setQuery,
            placeholder = { Text(stringResource(R.string.ed_fl_search), fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(52.dp),
        )
        TextButton(onClick = { controller.setSort(browse.sort.next()) }) {
            Text(stringResource(browse.sort.labelRes()), fontSize = 12.sp)
        }
        TextButton(onClick = { controller.setPicking(!browse.picking) }) {
            Text(stringResource(if (browse.picking) R.string.tray_pick_done else R.string.tray_pick))
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (filter in FolderFilter.entries) {
            FilterChip(
                selected = browse.filter == filter,
                onClick = { controller.setFilter(filter) },
                label = { Text(stringResource(filter.labelRes()), fontSize = 12.sp) },
            )
        }
    }
    if (browse.picking) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(R.plurals.tray_picked_count, browse.picked.size, browse.picked.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = browse.picked.isNotEmpty(), onClick = controller::clearPicked) {
                Text(stringResource(R.string.tray_pick_clear))
            }
            Button(enabled = browse.picked.isNotEmpty(), onClick = { onAdd(browse.picked); controller.finishPicking() }) {
                Text(stringResource(R.string.tray_pick_add, browse.picked.size))
            }
        }
    }
    when (val load = controller.load) {
        FolderLoad.Idle, FolderLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is FolderLoad.Failed -> Problem(load.problem, controller, onLinkFolder)
        is FolderLoad.Loaded -> {
            val page = controller.visible ?: return
            if (page.isEmpty) {
                val filtered = browse.query.isNotBlank() || browse.filter != FolderFilter.ALL
                Text(
                    stringResource(if (filtered) R.string.ed_fl_nothing_matches else R.string.ed_fl_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (layout == TrayLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(FolderTileMinWidth),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(page.folders, key = { "d:" + it.documentId }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                        FolderRow(entry) { controller.enter(entry) }
                    }
                    items(page.files, key = { "f:" + it.documentId }) { entry ->
                        FileTile(entry, inProject, drag, browse.picking, browse.pickNumber(entry.uri), grid = true, onAdd = { onAdd(listOf(entry.uri)) }, onPreview = onPreview?.let { open -> { open(SourceItem(entry.uri, entry.name, entry.kind?.assetKind() ?: AssetKind.VIDEO, entry.sizeBytes)) } }) { controller.togglePicked(entry.uri) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(page.folders, key = { "d:" + it.documentId }) { entry -> FolderRow(entry) { controller.enter(entry) } }
                    items(page.files, key = { "f:" + it.documentId }) { entry ->
                        FileTile(entry, inProject, drag, browse.picking, browse.pickNumber(entry.uri), grid = false, onAdd = { onAdd(listOf(entry.uri)) }, onPreview = onPreview?.let { open -> { open(SourceItem(entry.uri, entry.name, entry.kind?.assetKind() ?: AssetKind.VIDEO, entry.sizeBytes)) } }) { controller.togglePicked(entry.uri) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Problem(problem: BrowseProblem, controller: LinkedFoldersController, onLinkFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (problem == BrowseProblem.ACCESS_LOST) R.string.ed_fl_lost else R.string.ed_fl_unavailable),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::refresh) { Text(stringResource(R.string.ed_fl_retry)) }
            if (problem == BrowseProblem.ACCESS_LOST) OutlinedButton(onClick = onLinkFolder) { Text(stringResource(R.string.ed_fl_grant)) }
            controller.current?.let { folder ->
                TextButton(onClick = { controller.unlink(folder.id) }) { Text(stringResource(R.string.ed_fl_unlink)) }
            }
        }
    }
}

private fun FolderSort.labelRes(): Int = when (this) {
    FolderSort.NAME -> R.string.ed_fl_sort_name
    FolderSort.NEWEST -> R.string.ed_fl_sort_newest
    FolderSort.LARGEST -> R.string.ed_fl_sort_largest
}

private fun FolderFilter.labelRes(): Int = when (this) {
    FolderFilter.ALL -> R.string.ed_fl_f_all
    FolderFilter.VIDEO -> R.string.ed_fl_f_video
    FolderFilter.PHOTO -> R.string.ed_fl_f_photo
    FolderFilter.AUDIO -> R.string.ed_fl_f_audio
}

@Composable
private fun FolderRow(entry: FolderEntry, onOpen: () -> Unit) {
    val description = stringResource(R.string.ed_fl_folder_desc, entry.name)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun FileTile(
    entry: FolderEntry,
    inProject: Set<DocumentKey>,
    drag: TrayDragController,
    picking: Boolean,
    pickNumber: Int,
    grid: Boolean,
    onAdd: () -> Unit,
    onPreview: (() -> Unit)?,
    onTogglePick: () -> Unit,
) {
    val context = LocalContext.current
    val kind = entry.kind?.assetKind() ?: return
    val thumbnail by produceState<ImageBitmap?>(null, entry.uri) {
        value = AssetThumbnails.load(context, entry.uri, kind, FOLDER_THUMBNAIL_PX)?.asImageBitmap()
    }
    // The length is read when the tile comes on screen, never for a whole folder.
    val length by produceState<String?>(null, entry.uri) {
        value = FolderFacts.durationMillis(context, entry.uri, kind)?.let(::clockText)
    }
    val used = DocumentKey.of(entry.uri)?.let { it in inProject } == true
    val size = entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    val description = listOfNotNull(entry.name, length, size, stringResource(R.string.ed_fl_in_project).takeIf { used }).joinToString(", ")
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptics = LocalHapticFeedback.current
    val body = Modifier
        .graphicsLayer { alpha = if (drag.carry?.assetId == entry.uri) 0.35f else 1f }
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = description }
        .onGloballyPositioned { coordinates = it }
        .clickable(
            role = Role.Button,
            onClickLabel = when {
                picking -> stringResource(R.string.tray_pick_toggle)
                onPreview != null -> stringResource(R.string.ed_sp_preview, entry.name)
                else -> stringResource(R.string.ed_s3_add_at_the_playhead)
            },
        ) { if (picking) onTogglePick() else if (onPreview != null) onPreview() else onAdd() }
        // Held for a third of a second, the tile lifts and follows the finger onto the timeline, like a tile of the library.
        .pointerInput(entry.uri) {
            detectTrayDrag(
                controller = drag,
                enabled = !picking,
                carryAt = { root ->
                    val tile = coordinates?.takeIf { it.isAttached }
                    val home = tile?.boundsInRoot()?.center ?: root
                    TrayCarry(entry.uri, entry.name, length.orEmpty(), kind, thumbnail, root, home, fileUri = entry.uri)
                },
                rootOf = { local -> coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local },
                onPickedUp = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
    if (grid) {
        Box(body.aspectRatio(1f)) {
            Thumb(thumbnail, kind)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 11.sp)
                length?.let { Text(it, maxLines = 1, color = Color.White, fontSize = 10.sp) }
                if (used) Text(stringResource(R.string.ed_fl_in_project), maxLines = 1, color = Color.White, fontSize = 10.sp)
            }
            if (picking) PickCircle(pickNumber, Modifier.align(Alignment.TopEnd)) else PlusCircle(Modifier.align(Alignment.TopEnd), onAdd)
        }
    } else {
        Row(body.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) { Thumb(thumbnail, kind) }
            Column(Modifier.padding(horizontal = 8.dp).weight(1f)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(length, size, stringResource(R.string.ed_fl_in_project).takeIf { used }).joinToString(" · "),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picking) PickCircle(pickNumber, Modifier) else PlusCircle(Modifier, onAdd)
        }
    }
}

@Composable
private fun Thumb(thumbnail: ImageBitmap?, kind: AssetKind) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (thumbnail != null) {
            Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                stringResource(
                    when (kind) {
                        AssetKind.VIDEO -> R.string.ed_fl_f_video
                        AssetKind.PHOTO -> R.string.ed_fl_f_photo
                        AssetKind.AUDIO -> R.string.ed_fl_f_audio
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlusCircle(modifier: Modifier, onAdd: () -> Unit) {
    Box(
        modifier = modifier
            .padding(3.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ed_s3_add_at_the_playhead), onClick = onAdd)
            // The tile already says the same to a screen reader; this button is the same thing for the eye.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PickCircle(number: Int, modifier: Modifier) {
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
