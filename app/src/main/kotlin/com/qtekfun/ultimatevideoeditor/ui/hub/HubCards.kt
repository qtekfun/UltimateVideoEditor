package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.ProjectThumbnails
import com.qtekfun.ultimatevideoeditor.ui.about.AboutController
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons

/** The cached first frame of the project, made off the main thread; a plain tile while it loads or when there is none. */
@Composable
internal fun ProjectThumbnail(project: ProjectSummary, thumbnails: ProjectThumbnails?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, project.id, project.thumbnail) {
        value = thumbnails?.load(project.id, project.thumbnail)
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.hub_first_frame_of, project.name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(EditorIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A small rounded label over a picture: the length of a project. */
@Composable
internal fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** HDR and "N missing" labels; [error] uses the palette's error container. */
@Composable
internal fun StatusChip(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (error) Icon(HubIcons.Warning, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * The most recently edited project as a wide card: its first frame, a scrim for the text, the name, the format and a
 * "Continue editing" button. When the app closed while the project was open ([ContinueModel.resume]) the card says so
 * and offers Reopen and Dismiss instead.
 */
@Composable
internal fun ContinueCard(model: ContinueModel, thumbnails: ProjectThumbnails?, onIntent: (HubIntent) -> Unit, modifier: Modifier = Modifier) {
    val project = model.project
    Card(
        onClick = { onIntent(HubIntent.OpenProject(project.id)) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val height: Dp = (maxWidth * 9f / 16f).coerceIn(CONTINUE_MIN_HEIGHT, CONTINUE_MAX_HEIGHT)
            ProjectThumbnail(project, thumbnails, Modifier.fillMaxWidth().height(height))
            Box(
                Modifier.fillMaxWidth().height(height).background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (model.resume) R.string.hub_pick_up else R.string.hub_continue),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.resume) {
                    Text(
                        stringResource(R.string.hub_app_closed_while_open, project.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onIntent(HubIntent.ResumeSession) }) { Text(stringResource(R.string.hub_reopen)) }
                        TextButton(onClick = { onIntent(HubIntent.DismissResume) }) { Text(stringResource(R.string.common_dismiss)) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            UiText.join(" · ", HubFormat.length(project), HubFormat.sizeLine(project)).asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (HubFormat.isHdr(project)) StatusChip("HDR")
                        HubFormat.missing(project)?.let { StatusChip(it.asString(), error = true) }
                    }
                    Button(onClick = { onIntent(HubIntent.OpenProject(project.id)) }) { Text(stringResource(R.string.hub_continue_editing)) }
                }
            }
        }
    }
}

private val CONTINUE_MIN_HEIGHT = 150.dp
private val CONTINUE_MAX_HEIGHT = 220.dp

/**
 * What the app keeps on this device: number of projects, free space and a segmented meter (projects, cache, proxies).
 * Footage is not part of it. Tapping opens About, where the caches can be cleared.
 */
@Composable
internal fun StorageCard(projectCount: Int, storage: StorageSnapshot?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colours = mapOf(
        StorageKind.PROJECTS to MaterialTheme.colorScheme.primary,
        StorageKind.CACHE to MaterialTheme.colorScheme.secondary,
        StorageKind.PROXIES to MaterialTheme.colorScheme.tertiary,
    )
    val countText = HubFormat.projectCount(projectCount).asString()
    val summary = storage?.let { s ->
        stringResource(
            R.string.hub_storage_summary,
            countText,
            AboutController.formatBytes(s.freeBytes),
            s.segments.map { stringResource(R.string.hub_storage_legend, stringResource(it.kind.labelRes), AboutController.formatBytes(it.bytes)) }.joinToString(". "),
        )
    } ?: stringResource(R.string.hub_storage_summary_measuring, countText)
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.hub_storage_open_about), role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = summary },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HubIcons.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    countText,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(
                    storage?.let { stringResource(R.string.hub_storage_free, AboutController.formatBytes(it.freeBytes)) } ?: stringResource(R.string.common_measuring),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val segments = storage?.segments.orEmpty().filter { it.bytes > 0 }
            Row(
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (segment in segments) {
                    // A sliver is still visible: a segment never gets less than 2% of the bar.
                    Box(Modifier.weight(segment.fraction.coerceAtLeast(0.02f)).fillMaxSize().background(colours.getValue(segment.kind)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (kind in StorageKind.entries) {
                    val bytes = storage?.segments?.firstOrNull { it.kind == kind }?.bytes
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colours.getValue(kind)))
                        Text(
                            stringResource(R.string.hub_storage_legend, stringResource(kind.labelRes), bytes?.let { AboutController.formatBytes(it) } ?: stringResource(R.string.hub_unknown_size)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(stringResource(R.string.hub_footage_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
