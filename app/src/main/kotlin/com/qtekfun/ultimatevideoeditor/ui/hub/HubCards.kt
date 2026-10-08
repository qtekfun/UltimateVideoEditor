package com.qtekfun.ultimatevideoeditor.ui.hub

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
                contentDescription = "First frame of ${project.name}",
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
                    if (model.resume) "Pick up where you left off" else "Continue",
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
                        "The app closed while \"${project.name}\" was open. Your edits were saved as you made them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onIntent(HubIntent.ResumeSession) }) { Text("Reopen") }
                        TextButton(onClick = { onIntent(HubIntent.DismissResume) }) { Text("Dismiss") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${HubFormat.length(project)} · ${HubFormat.sizeLine(project)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (HubFormat.isHdr(project)) StatusChip("HDR")
                        HubFormat.missing(project)?.let { StatusChip(it, error = true) }
                    }
                    Button(onClick = { onIntent(HubIntent.OpenProject(project.id)) }) { Text("Continue editing") }
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
    val summary = storage?.let { s ->
        "Storage. ${HubFormat.projectCount(projectCount)}. ${AboutController.formatBytes(s.freeBytes)} free. " +
            s.segments.joinToString(". ") { "${it.kind.label} ${AboutController.formatBytes(it.bytes)}" }
    } ?: "Storage. ${HubFormat.projectCount(projectCount)}. Measuring"
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = "Open About to manage storage", role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = summary },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HubIcons.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    HubFormat.projectCount(projectCount),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(
                    storage?.let { "${AboutController.formatBytes(it.freeBytes)} free" } ?: "Measuring…",
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
                            "${kind.label} ${bytes?.let { AboutController.formatBytes(it) } ?: "…"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(HubFormat.FOOTAGE_HINT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
