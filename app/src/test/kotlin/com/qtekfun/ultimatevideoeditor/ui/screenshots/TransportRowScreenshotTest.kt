package com.qtekfun.ultimatevideoeditor.ui.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorTransportRow
import com.qtekfun.ultimatevideoeditor.ui.theme.UVEditorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sample screenshot test (docs/ARCHITECTURE.md, "Screenshot tests"): the editor's transport row at the narrowest window and the
 * largest font the layout rules promise to handle, and at a normal phone. Runs on the JVM under Robolectric; goldens are
 * PNG files in `src/test/screenshots` (1 px = 1 dp). Verify is the default; record with `-Puveditor.recordScreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h160dp-mdpi")
class TransportRowScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, fontScale: Float) {
        val state = mutableStateOf(EditorState(isLoading = false))
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                UVEditorTheme {
                    EditorTransportRow(
                        holder = state, isPlaying = false, takePeaks = { PeakLevels.SILENT }, detailZoom = true,
                        onPrevious = {}, onTogglePlay = {}, onNext = {}, onSaveFrame = {}, onZoomDetail = {}, onFit = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun narrowWindowLargestFont() = shoot("transport_320dp_font2", 2.0f)

    @Test @Config(qualifiers = "w411dp-h160dp-mdpi") fun phoneNormalFont() = shoot("transport_411dp_font1", 1.0f)
}
