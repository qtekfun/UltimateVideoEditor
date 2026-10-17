package com.qtekfun.ultimatevideoeditor.ui.library

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.ui.editor.labelRes
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.MarkerColor
import com.qtekfun.ultimatevideoeditor.ui.editor.AssetEditDraft
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState
import com.qtekfun.ultimatevideoeditor.ui.editor.InterchangeKind
import com.qtekfun.ultimatevideoeditor.ui.editor.LibraryIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton
import com.qtekfun.ultimatevideoeditor.ui.editor.formatTimecode
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.badgeLabel
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.proxyStatusOf
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetThumbnails
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.usageCounts

/** The toolbar button that opens the library; with a clip selected it opens on that clip's file ("find in library"). */
@Composable
internal fun LibraryButton(onIntent: (EditorIntent) -> Unit) {
    ToolButton(EditorIcons.Library, stringResource(R.string.ed_2a_library_button)) {
        onIntent(LibraryIntent.RevealSelectedInLibrary)
    }
}

/** Everything the library adds on top of the editor: the sheet, its dialogs, and the marker note dialog. */
@Composable
internal fun LibraryOverlays(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    if (state.library.open) LibrarySheet(state, onIntent)
    state.library.editing?.let { AssetEditDialog(it, onIntent) }
    state.library.confirmDeleteUnused?.let { count -> DeleteUnusedDialog(count, onIntent) }
    state.library.bundleDraft?.let { draft ->
        BundleExportDialog(
            draft = draft,
            onChoice = { onIntent(LibraryIntent.BundleChoiceChanged(it)) },
            onConfirm = { onIntent(LibraryIntent.ConfirmBundleExport) },
            onDismiss = { onIntent(LibraryIntent.DismissBundleExport) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySheet(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val library = state.library
    val usage = remember(state.timeline) { usageCounts(state.timeline) }
    val items = remember(state.assets, usage, state.missingMedia, library.query) {
        Library.items(state.assets, usage, state.missingMedia.keys, library.query)
    }
    val tags = remember(state.assets) { Library.allTags(state.assets) }
    val unusedCount = remember(state.assets, usage) { Library.unused(state.assets, usage).size }
    ModalBottomSheet(
        onDismissRequest = { onIntent(LibraryIntent.Close) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_2a_tool_library), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(pluralStringResource(R.plurals.ed_s3_files, state.assets.size, state.assets.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = library.query.text,
                onValueChange = { onIntent(LibraryIntent.QueryChanged(it)) },
                label = { Text(stringResource(R.string.ed_s3_search_names_tags_and_notes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (filter in LibraryFilter.entries) {
                    FilterChip(
                        selected = library.query.filter == filter,
                        onClick = { onIntent(LibraryIntent.FilterSelected(filter)) },
                        label = { Text(if (filter == LibraryFilter.UNUSED) stringResource(R.string.ed_s3_unused, unusedCount) else stringResource(filter.labelRes)) },
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((tag, count) in tags) {
                        val selected = library.query.tag?.equals(tag, ignoreCase = true) == true
                        FilterChip(
                            selected = selected,
                            onClick = { onIntent(LibraryIntent.TagSelected(if (selected) null else tag)) },
                            label = { Text("#$tag $count") },
                        )
                    }
                }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(library.highlightAssetId, items) {
                val index = items.indexOfFirst { it.asset.id == library.highlightAssetId }
                if (index >= 0) listState.scrollToItem(index)
            }
            if (items.isEmpty()) {
                Text(
                    if (state.assets.isEmpty()) stringResource(R.string.ed_s3_no_media_yet_use_in) else stringResource(R.string.ed_s3_nothing_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items, key = { _, item -> item.asset.id }) { _, item ->
                    LibraryRow(item, highlighted = item.asset.id == library.highlightAssetId, onIntent)
                }
            }
            LibraryFooter(unusedCount, library.busy, onIntent)
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, highlighted: Boolean, onIntent: (EditorIntent) -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, item.asset.id, item.asset.uri) {
        value = AssetThumbnails.load(context, item.asset, THUMBNAIL_PX)?.asImageBitmap()
    }
    val background = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fps = FrameRate(item.asset.nativeFpsNum.coerceAtLeast(1), item.asset.nativeFpsDen.coerceAtLeast(1))
    val kindText = stringResource(item.kind.labelRes())
    val usedText = pluralStringResource(R.plurals.ed_s3_used_times_long, item.usage, item.usage)
    val missingText = stringResource(R.string.ed_s3_file_missing)
    val description = listOfNotNull(item.name, kindText, usedText, missingText.takeIf { item.missing }).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(background).padding(8.dp).described(description),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else if (!item.missing) {
                Text(kindText.take(1), style = MaterialTheme.typography.titleMedium)
            }
            if (item.missing) {
                Box(Modifier.size(56.dp).background(Color(0xAAB00020)), contentAlignment = Alignment.Center) { Text(stringResource(R.string.ed_s3_missing), style = MaterialTheme.typography.labelSmall, color = Color.White) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val proxyLabel = proxyStatusOf(item.asset.id).badgeLabel()?.asString()
            val unusedText = stringResource(R.string.ed_s3_unused_lc)
            val meta = buildList {
                add(kindText)
                if (item.kind != AssetKind.PHOTO) add(formatTimecode(item.durationFrames, fps))
                item.badge?.let { add(it) }
                proxyLabel?.let { add(it) }
                add(if (item.usage == 0) unusedText else stringResource(R.string.ed_s3_used_times, item.usage))
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.tags.isNotEmpty()) {
                Text(item.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onIntent(LibraryIntent.FindInTimeline(item.asset.id)) }, enabled = item.usage > 0) { Text(stringResource(R.string.ed_s3_find_in_timeline)) }
                TextButton(onClick = { onIntent(LibraryIntent.EditAsset(item.asset.id)) }) { Text(stringResource(R.string.ed_s3_tags_note)) }
            }
        }
    }
}

@Composable
private fun LibraryFooter(unusedCount: Int, busy: UiText?, onIntent: (EditorIntent) -> Unit) {
    var exportOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (busy != null) Text(busy.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onIntent(LibraryIntent.AskDeleteUnused) }, enabled = unusedCount > 0) { Text(stringResource(R.string.ed_s3_remove_unused, unusedCount), maxLines = 1) }
            Box {
                TextButton(onClick = { exportOpen = true }, enabled = busy == null) { Text(stringResource(R.string.export_dlg_export_button), maxLines = 1) }
                DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                    for (kind in InterchangeKind.entries) {
                        DropdownMenuItem(text = { Text(stringResource(kind.labelRes)) }, onClick = { exportOpen = false; onIntent(LibraryIntent.RequestExport(kind)) })
                    }
                }
            }
            TextButton(onClick = { onIntent(LibraryIntent.Close) }) { Text(stringResource(R.string.common_close), maxLines = 1) }
        }
    }
}

@Composable
private fun AssetEditDialog(draft: AssetEditDraft, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(LibraryIntent.DismissAssetEdit) },
        title = { Text(draft.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.tags,
                    onValueChange = { onIntent(LibraryIntent.TagsChanged(it)) },
                    label = { Text(stringResource(R.string.ed_s3_tags_separated_by_commas)) },
                    supportingText = { Text(stringResource(R.string.ed_s3_up_to_tags_of_characters, Library.MAX_TAGS, Library.MAX_TAG_LENGTH)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { onIntent(LibraryIntent.NoteChanged(it)) },
                    label = { Text(stringResource(R.string.ed_2a_note)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(LibraryIntent.ConfirmAssetEdit) }) { Text(stringResource(R.string.ed_2b_save)) } },
        dismissButton = { TextButton(onClick = { onIntent(LibraryIntent.DismissAssetEdit) }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DeleteUnusedDialog(count: Int, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(LibraryIntent.DismissDeleteUnused) },
        title = { Text(pluralStringResource(R.plurals.ed_s3_remove_unused_title, count, count)) },
        text = {
            Text(stringResource(R.string.ed_s3_delete_unused_text))
        },
        confirmButton = { TextButton(onClick = { onIntent(LibraryIntent.ConfirmDeleteUnused) }) { Text(stringResource(R.string.ed_2b_remove)) } },
        dismissButton = { TextButton(onClick = { onIntent(LibraryIntent.DismissDeleteUnused) }) { Text(stringResource(R.string.ed_s3_keep)) } },
    )
}

/** The colour a marker colour is shown in. */
internal fun markerColour(color: MarkerColor): Color = when (color) {
    MarkerColor.RED -> Color(0xFFE53935)
    MarkerColor.ORANGE -> Color(0xFFFB8C00)
    MarkerColor.YELLOW -> Color(0xFFFDD835)
    MarkerColor.GREEN -> Color(0xFF43A047)
    MarkerColor.BLUE -> Color(0xFF1E88E5)
    MarkerColor.PURPLE -> Color(0xFF8E24AA)
}

private const val THUMBNAIL_PX = 112
