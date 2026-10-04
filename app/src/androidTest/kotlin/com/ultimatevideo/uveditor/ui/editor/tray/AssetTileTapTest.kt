package com.ultimatevideo.uveditor.ui.editor.tray

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap on a media tray tile must add the clip. The tile also is a drag source, and that gesture detector consumes the
 * release of a short press, so a click placed outside it never fired (Huawei MatePad: only a long-press drag worked).
 */
@RunWith(AndroidJUnit4::class)
class AssetTileTapTest {
    @get:Rule val compose = createComposeRule()

    private val asset = MediaAssetDto(
        id = "a1", uri = "content://none/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1,
        colorSpace = "sdr", displayName = "clip.mp4",
    )

    private fun show(onAdd: (String) -> Unit, onDragStart: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(160.dp)) {
                    AssetTile(
                        item = TrayItem(asset, AssetKind.VIDEO, "clip.mp4", usage = 0, missing = false),
                        assets = listOf(asset), onAdd = onAdd, onAssetDragStart = onDragStart,
                        onReorder = { _, _ -> }, grid = true,
                    )
                }
            }
        }
    }

    @Test fun tapAddsTheClip() {
        val added = mutableListOf<String>()
        show(onAdd = { added += it })
        compose.onNodeWithContentDescription("Tap to add", substring = true).performClick()
        assertEquals(listOf("a1"), added)
    }

    @Test fun shortTouchPressAddsTheClip() {
        val added = mutableListOf<String>()
        show(onAdd = { added += it })
        compose.onNodeWithContentDescription("Tap to add", substring = true).performTouchInput {
            down(center)
            up()
        }
        assertEquals(listOf("a1"), added)
    }
}
