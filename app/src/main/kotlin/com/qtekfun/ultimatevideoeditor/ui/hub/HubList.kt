package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.ProjectThumbnails
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import java.text.DateFormat
import java.util.Date

/** "Sorted by Last edited" with its menu, the direction toggle and the List / Grid switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortBar(state: HubState, onIntent: (HubIntent) -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val sortedByDescription = stringResource(R.string.hub_sorted_by_description, stringResource(state.sort.labelRes))
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(
                onClick = { menuOpen = true },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = sortedByDescription },
            ) {
                Text(stringResource(R.string.hub_sorted_by, stringResource(state.sort.labelRes)), style = MaterialTheme.typography.labelLarge)
                Icon(HubIcons.DropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (sort in ProjectSort.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(sort.labelRes)) },
                        leadingIcon = { if (sort == state.sort) Icon(HubIcons.Check, contentDescription = stringResource(R.string.hub_current_order)) else Box(Modifier.size(24.dp)) },
                        onClick = { menuOpen = false; onIntent(HubIntent.SortSelected(sort)) },
                    )
                }
            }
        }
        IconButton(onClick = { onIntent(HubIntent.ToggleSortDirection) }) {
            Icon(
                if (state.sortAscending) EditorIcons.LaneUp else EditorIcons.LaneDown,
                contentDescription = stringResource(if (state.sortAscending) R.string.hub_ascending else R.string.hub_descending),
            )
        }
        Box(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            val modes = HubViewMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.viewMode == mode,
                    onClick = { onIntent(HubIntent.ViewModeSelected(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                    label = {
                        Icon(
                            if (mode == HubViewMode.LIST) HubIcons.ViewList else HubIcons.GridView,
                            contentDescription = stringResource(mode.descriptionRes),
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).width(56.dp),
                )
            }
        }
    }
}

/** The tick on a thumbnail in selection mode. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
    Box(
        modifier = modifier.size(22.dp).clip(CircleShape).background(fill)
            .border(BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(HubIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

private fun dateText(project: ProjectSummary, now: Long): UiText =
    HubFormat.relativeDate(project.lastModifiedMillis, now) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

/** The per-project menu (the row's trailing button): rename, duplicate, export, delete. */
@Composable
internal fun ProjectMenuButton(project: ProjectSummary, onIntent: (HubIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(HubIcons.MoreVert, contentDescription = stringResource(R.string.hub_actions_for, project.name)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, onClick = { open = false; onIntent(HubIntent.RequestRename(project)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_duplicate)) }, onClick = { open = false; onIntent(HubIntent.Clone(project.id)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.hub_export_project_file)) }, onClick = { open = false; onIntent(HubIntent.RequestExport(project)) })
            // One entry: the dialog it opens asks what the bundle should hold (media files, LUTs, fonts).
            DropdownMenuItem(text = { Text(stringResource(R.string.hub_export_bundle_menu)) }, onClick = { open = false; onIntent(HubIntent.RequestExportBundle(project)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { open = false; onIntent(HubIntent.RequestDelete(project)) })
        }
    }
}

/** A dense list row: small thumbnail with the length, name, format, size on disk and age, and a status chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectRow(
    project: ProjectSummary,
    bytes: Long?,
    now: Long,
    selecting: Boolean,
    selected: Boolean,
    thumbnails: ProjectThumbnails?,
    onIntent: (HubIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Card(
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected }
            .combinedClickable(
                onClickLabel = stringResource(if (selecting) R.string.hub_toggle_selection else R.string.hub_open_project),
                onLongClickLabel = stringResource(R.string.hub_select),
                onClick = { onIntent(if (selecting) HubIntent.ToggleSelected(project.id) else HubIntent.OpenProject(project.id)) },
                onLongClick = { onIntent(if (selecting) HubIntent.ToggleSelected(project.id) else HubIntent.EnterSelection(project.id)) },
            ),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (selecting) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ProjectThumbnail(project, thumbnails, Modifier.width(64.dp).height(40.dp).clip(RoundedCornerShape(6.dp)))
                DurationBadge(HubFormat.length(project).asString(), Modifier.align(Alignment.BottomEnd).padding(2.dp))
                if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(2.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    HubFormat.formatLine(project).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HubFormat.missing(project)?.let { StatusChip(it.asString(), error = true, modifier = Modifier.padding(top = 2.dp)) }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp, end = if (selecting) 0.dp else 4.dp)) {
                HubFormat.bytes(bytes)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                Text(dateText(project, now).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) ProjectMenuButton(project, onIntent)
        }
    }
}

/** A poster of the grid: a 4:3 picture with rounded corners and the length, then the name and the date. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectPoster(
    project: ProjectSummary,
    now: Long,
    selecting: Boolean,
    selected: Boolean,
    thumbnails: ProjectThumbnails?,
    onIntent: (HubIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).semantics { this.selected = selected }
            .combinedClickable(
                onClickLabel = stringResource(if (selecting) R.string.hub_toggle_selection else R.string.hub_open_project),
                onLongClickLabel = stringResource(R.string.hub_select),
                onClick = { onIntent(if (selecting) HubIntent.ToggleSelected(project.id) else HubIntent.OpenProject(project.id)) },
                onLongClick = { onIntent(if (selecting) HubIntent.ToggleSelected(project.id) else HubIntent.EnterSelection(project.id)) },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            val shape = RoundedCornerShape(14.dp)
            ProjectThumbnail(
                project,
                thumbnails,
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape)
                    .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape) else Modifier),
            )
            DurationBadge(HubFormat.length(project).asString(), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (HubFormat.isHdr(project)) StatusChip("HDR", modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            HubFormat.missing(project)?.let { StatusChip(it.asString(), error = true, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateText(project, now).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) ProjectMenuButton(project, onIntent)
        }
    }
}

/**
 * The bar at the bottom in selection mode: Duplicate, Export bundle, Export project file, Delete, and an overflow with
 * Rename. Exports and rename work on one project; a disabled action explains why when tapped or long-pressed
 * ([onExplain] shows the reason).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectionBar(actions: SelectionActions, onIntent: (HubIntent) -> Unit, onExplain: (UiText) -> Unit) {
    Surface(tonalElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val exportReason = actions.exportDisabledReason ?: SelectionActions.ONE_AT_A_TIME
            BarAction(HubIcons.Copy, stringResource(R.string.common_duplicate), actions.duplicate, UiText.res(R.string.hub_select_first), { onIntent(HubIntent.DuplicateSelected) }, onExplain, Modifier.weight(1f))
            BarAction(EditorIcons.Layers, stringResource(R.string.hub_bar_export_bundle), actions.exportBundle, exportReason, { onIntent(HubIntent.ExportSelectedBundle) }, onExplain, Modifier.weight(1f))
            BarAction(EditorIcons.Export, stringResource(R.string.hub_bar_export_file), actions.exportFile, exportReason, { onIntent(HubIntent.ExportSelectedFile) }, onExplain, Modifier.weight(1f))
            BarAction(EditorIcons.Delete, stringResource(R.string.common_delete), actions.delete, UiText.res(R.string.hub_select_first), { onIntent(HubIntent.DeleteSelected) }, onExplain, Modifier.weight(1f))
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }) { Icon(HubIcons.MoreVert, contentDescription = stringResource(R.string.hub_more_actions)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_rename)) },
                        leadingIcon = { Icon(HubIcons.Edit, contentDescription = null) },
                        enabled = actions.rename,
                        onClick = { more = false; onIntent(HubIntent.RenameSelected) },
                    )
                    if (!actions.rename) {
                        Text(
                            stringResource(R.string.hub_rename_one_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    disabledReason: UiText,
    onClick: () -> Unit,
    onExplain: (UiText) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                role = Role.Button,
                onClick = { if (enabled) onClick() else onExplain(disabledReason) },
                onLongClick = { if (!enabled) onExplain(disabledReason) },
            )
            .semantics { if (!enabled) disabled() }
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Placeholder so the empty list of a search keeps the layout's width. */
@Composable
internal fun NoMatch(query: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.hub_no_match, query.trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
