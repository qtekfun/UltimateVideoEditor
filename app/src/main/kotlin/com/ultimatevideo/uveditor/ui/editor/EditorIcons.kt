package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Small icon set for the editor toolbar, drawn from Material path data so the app does not need the
 * material-icons dependency for a handful of glyphs.
 */
internal object EditorIcons {
    val Play = icon("Play", "M8,5v14l11,-7z")

    val Export = icon("Export", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")

    val Add = icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")

    val Delete = icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")

    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")

    val Layers = icon(
        "Layers",
        "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.25L12,16z",
    )

    /** Four arrows pointing outward: show everything. */
    val Fit = icon(
        "Fit",
        "M15,3l2.3,2.3 -2.89,2.87 1.42,1.42L18.7,6.7 21,9V3zM3,9l2.3,-2.3 2.87,2.89 1.42,-1.42L6.7,5.3 9,3H3z" +
            "M9,21l-2.3,-2.3 2.89,-2.87 -1.42,-1.42L5.3,17.3 3,15v6zM21,15l-2.3,2.3 -2.87,-2.89 -1.42,1.42 " +
            "2.89,2.87L15,21h6z",
    )

    val Minus = icon("Minus", "M19,13H5v-2h14v2z")

    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")

    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")

    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")

    val Undo = icon(
        "Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
            "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )

    val Redo = icon(
        "Redo",
        "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 " +
            "7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
    )

    val Split = icon(
        "Split",
        "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 " +
            "1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 " +
            "4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 " +
            "-2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 " +
            "-0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 " +
            "-0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7L22,3z",
    )

    /** Two arrows meeting in the middle: closes the gap before the selected clip. */
    val CloseGap = icon("CloseGap", "M2,11h6V8l4,4 -4,4v-3H2zM22,11h-6V8l-4,4 4,4v-3h6z")

    /** A capital T: add a title. */
    val Title = icon("Title", "M5,4v3h5.5v12h3V7H19V4z")

    /** Two opposed arrows: a transition across the cut between two clips. */
    val Transition = icon("Transition", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")

    /** Three sliders: the appearance inspector (transform and gain of the selected clip). */
    val Tune = icon(
        "Tune",
        "M3,17v2h6v-2H3zM3,5v2h4V5H3zM9,21v-2h12v-2H9v-2H7v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10z" +
            "M15,9h2V7h4V5h-4V3h-2v6z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
