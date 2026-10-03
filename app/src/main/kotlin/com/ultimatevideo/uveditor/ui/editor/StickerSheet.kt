package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.engine.still.StickerArt
import com.ultimatevideo.uveditor.engine.still.StickerIds
import com.ultimatevideo.uveditor.engine.still.StickerInfo

/**
 * Lists the built-in stickers. Each tile is drawn with the same [StickerArt] the compositor uses, so what
 * is listed is what lands on the timeline. The picked sticker goes to an overlay lane at the playhead
 * (see `EditorViewModel`). Shown as the Stickers tab of the media tray.
 */
@Composable
internal fun StickerChooser(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StickerGroup("Shapes", StickerIds.shapes, onPick)
        StickerGroup("Emoji", StickerIds.emoji, onPick)
    }
}

@Composable
private fun StickerGroup(title: String, stickers: List<StickerInfo>, onPick: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TILE_SIZE),
        modifier = Modifier.fillMaxWidth().heightIn(max = GROUP_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(stickers, key = { it.id }) { sticker ->
            Box(
                modifier = Modifier
                    .size(TILE_SIZE)
                    .semantics { contentDescription = "Add sticker ${sticker.label}" }
                    .clickable(role = Role.Button) { onPick(sticker.id) },
                contentAlignment = Alignment.Center,
            ) {
                val art = StickerArt.find(sticker.id)
                if (art != null) {
                    Canvas(Modifier.size(TILE_SIZE - 8.dp)) {
                        drawIntoCanvas { art.draw(it.nativeCanvas, size.minDimension) }
                    }
                }
            }
        }
    }
}

private val TILE_SIZE = 64.dp
private val GROUP_MAX_HEIGHT = 160.dp
