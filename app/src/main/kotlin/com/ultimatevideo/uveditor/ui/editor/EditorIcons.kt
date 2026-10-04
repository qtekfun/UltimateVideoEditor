package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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

    /** A stack of clips with a play triangle: the media library. */
    val Library = icon(
        "Library",
        "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM12,14.5v-9l6,4.5 -6,4.5z",
    )

    val Export = icon("Export", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")

    val Add = icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")

    val Delete = icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")

    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")

    /** A lightning bolt: fast, light copies of heavy video (proxy media). */
    val Proxy = icon(
        "Proxy",
        "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z",
    )

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

    val ChevronLeft = icon("ChevronLeft", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z")

    val ChevronUp = icon("ChevronUp", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")

    val ChevronRight = icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")

    /** A window split into panes: the layout controls. */
    val LayoutPanes = icon("LayoutPanes", "M3,3h18v18H3V3zM5,5v4h14V5H5zM5,11v8h5v-8H5zM12,11v8h7v-8h-7z")

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

    /** A smiling face: opens the sticker picker. */
    val Sticker = icon(
        "Sticker",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 " +
            "-8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM15.5,11c0.83,0 1.5,-0.67 1.5,-1.5S16.33,8 " +
            "15.5,8 14,8.67 14,9.5s0.67,1.5 1.5,1.5zM8.5,11c0.83,0 1.5,-0.67 1.5,-1.5S9.33,8 8.5,8 7,8.67 7,9.5 " +
            "7.67,11 8.5,11zM12,17.5c2.33,0 4.31,-1.46 5.11,-3.5H6.89c0.8,2.04 2.78,3.5 5.11,3.5z",
    )

    /** An arrow pointing up: moves the selected lane up. */
    val LaneUp = icon("LaneUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8z")

    /** An arrow pointing down: moves the selected lane down. */
    val LaneDown = icon("LaneDown", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8z")

    /** A capital T: add a title. */
    val Title = icon("Title", "M5,4v3h5.5v12h3V7H19V4z")

    /** A caption box with "CC": automatic captions. */
    val Captions = icon(
        "Captions",
        "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,11H9.5v-0.5h-2v3h2V13H11v1c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z" +
            "M18,11h-1.5v-0.5h-2v3h2V13H18v1c0,0.55 -0.45,1 -1,1h-3c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z",
    )

    /** Two opposed arrows: a transition across the cut between two clips. */
    val Transition = icon("Transition", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")

    /** Three sliders: the appearance inspector (transform and gain of the selected clip). */
    val Tune = icon(
        "Tune",
        "M3,17v2h6v-2H3zM3,5v2h4V5H3zM9,21v-2h12v-2H9v-2H7v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10z" +
            "M15,9h2V7h4V5h-4V3h-2v6z",
    )

    /** A filled diamond: a keyframe exists at the playhead. */
    val KeyframeOn = icon("KeyframeOn", "M12,2l10,10 -10,10 -10,-10z")

    /** An outlined diamond: add a keyframe at the playhead. */
    val KeyframeOff = icon("KeyframeOff", "M12,2l10,10 -10,10 -10,-10zM12,6.8l-5.2,5.2 5.2,5.2 5.2,-5.2z", evenOdd = true)

    /** A frame inside a frame: the safe-zone overlay. */
    val SafeZone = icon("SafeZone", "M3,3h18v18H3zM6,6v12h12V6z", evenOdd = true)

    /** A flag: markers and beats on the ruler. */
    val Flag = icon("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")

    /** Three bars of different heights: a level meter, for the quick edits that read the clip's audio. */
    val Silence = icon("Silence", "M10,20h4V4h-4v16zM4,20h4v-8H4v8zM16,9v11h4V9h-4z")

    /** Two vertical faders: the track mixer. */
    val Mixer = icon("Mixer", "M7,4v7H5v2h2v7h2v-7h2v-2H9V4H7zM15,4v3h-2v2h2v11h2V9h2V7h-2V4h-2z")
    /** Four bars of different heights: the video scopes. */
    val Scopes = icon("Scopes", "M4,14h3v6H4zM9,8h3v12H9zM14,11h3v9h-3zM19,4h3v16h-3z")

    /** Large and small letters: the text templates. */
    val TextTemplate = icon("TextTemplate", "M2.5,4v3h5v12h3V7h5V4h-13zM21.5,9h-9v3h3v7h3v-7h3V9z")

    /** A tall frame: change the canvas format. */
    val CanvasFormat = icon("CanvasFormat", "M7,2h10v20H7zM9,4v16h6V4z", evenOdd = true)

    private fun icon(name: String, pathData: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                PathParser().parsePathString(pathData).toNodes(),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
            .build()
}
