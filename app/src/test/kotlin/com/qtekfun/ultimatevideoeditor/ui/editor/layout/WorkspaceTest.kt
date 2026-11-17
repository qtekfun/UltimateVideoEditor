package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    private val phone = WindowMetrics(412f, 892f)
    private val phoneSideways = WindowMetrics(892f, 412f)
    private val tablet = WindowMetrics(1280f, 800f)
    private val tabletPortrait = WindowMetrics(800f, 1280f)
    private val foldOpen = WindowMetrics(800f, 740f)
    private val foldPortrait = WindowMetrics(700f, 900f)
    private val foldClosed = WindowMetrics(360f, 780f)
    private val tabletop = WindowMetrics(700f, 800f, FoldPosture.TABLETOP)
    private val book = WindowMetrics(900f, 700f, FoldPosture.BOOK)

    @Test
    fun `workspaces are only offered where they have room`() {
        assertTrue(tablet.allows(Workspace.WORKBENCH))
        assertFalse(phoneSideways.allows(Workspace.WORKBENCH)) // 412 dp tall
        assertTrue(foldOpen.allows(Workspace.WORKBENCH))
        assertFalse(foldPortrait.allows(Workspace.WORKBENCH)) // 700 dp wide
        assertFalse(tabletPortrait.allows(Workspace.WORKBENCH)) // tall and narrow
        assertTrue(phoneSideways.allows(Workspace.SIDE_BY_SIDE))
        assertTrue(foldOpen.allows(Workspace.SIDE_BY_SIDE))
        assertFalse(foldPortrait.allows(Workspace.SIDE_BY_SIDE))
        assertFalse(phone.allows(Workspace.SIDE_BY_SIDE))
        assertFalse(tabletPortrait.allows(Workspace.SIDE_BY_SIDE)) // taller than wide
        for (window in listOf(phone, phoneSideways, tablet, tabletPortrait, foldOpen, foldClosed)) assertTrue(window.allows(Workspace.STACKED))
    }

    @Test
    fun `a window picks its workspace by shape`() {
        assertEquals(Workspace.STACKED, phone.defaultWorkspace)
        assertEquals(Workspace.STACKED, foldClosed.defaultWorkspace)
        assertEquals(Workspace.STACKED, foldPortrait.defaultWorkspace)
        assertEquals(Workspace.STACKED, tabletPortrait.defaultWorkspace)
        assertEquals(Workspace.WORKBENCH, tablet.defaultWorkspace)
        assertEquals(Workspace.SIDE_BY_SIDE, phoneSideways.defaultWorkspace)
        assertEquals(Workspace.WORKBENCH, foldOpen.defaultWorkspace)
    }

    @Test
    fun `a half open foldable splits at the hinge`() {
        assertEquals(Workspace.STACKED, tabletop.defaultWorkspace)
        assertEquals(LayoutState.TABLETOP_PREVIEW_FRACTION, LayoutState.defaultFor(tabletop).previewFraction, 0.0001f)
        assertEquals(Workspace.SIDE_BY_SIDE, book.defaultWorkspace)
        assertEquals(0.5f, LayoutState.defaultFor(book).previewWidthFraction, 0.0001f)
        // Book posture in a window too narrow for side by side stays stacked.
        assertEquals(Workspace.STACKED, WindowMetrics(500f, 800f, FoldPosture.BOOK).defaultWorkspace)
    }

    @Test
    fun `posture comes from the fold state and the hinge direction`() {
        assertEquals(FoldPosture.NONE, FoldPosture.of(halfOpen = false, horizontal = true))
        assertEquals(FoldPosture.NONE, FoldPosture.of(halfOpen = false, horizontal = false))
        assertEquals(FoldPosture.TABLETOP, FoldPosture.of(halfOpen = true, horizontal = true))
        assertEquals(FoldPosture.BOOK, FoldPosture.of(halfOpen = true, horizontal = false))
    }

    @Test
    fun `layout keys keep their old ids and add the posture only when folded`() {
        assertEquals("expanded-landscape", LayoutKey.of(tablet).id)
        assertEquals("medium-portrait", LayoutKey.of(WindowMetrics(700f, 900f)).id)
        assertEquals("medium-portrait-tabletop", LayoutKey.of(tabletop).id)
        assertEquals("expanded-landscape-book", LayoutKey.of(WindowMetrics(900f, 700f, FoldPosture.BOOK)).id)
        assertNotEquals(LayoutKey.of(tabletop), LayoutKey.of(WindowMetrics(700f, 800f)))
    }

    @Test
    fun `defaults give the tray a place that suits the workspace`() {
        val workbench = LayoutState.defaultFor(tablet)
        assertEquals(Dock.LEFT, workbench.tray.dock)
        assertFalse(workbench.tray.collapsed)
        assertEquals(LayoutState.WORKBENCH_PREVIEW_FRACTION, workbench.previewFraction, 0.0001f)
        // Short and wide: height is what is missing, so the tray is folded at the bottom until it is needed.
        val side = LayoutState.defaultFor(phoneSideways)
        assertEquals(Dock.BOTTOM, side.tray.dock)
        assertTrue(side.tray.collapsed)
        // The stacked layouts are what they always were.
        assertEquals(PanelState(Dock.BOTTOM), LayoutState.defaultFor(phone).tray)
    }

    @Test
    fun `every preset is valid in every window and keeps the workspace in range`() {
        for (window in listOf(phone, phoneSideways, tablet, tabletPortrait, foldOpen, tabletop, book)) {
            for (preset in LayoutPreset.entries) {
                val state = LayoutState.preset(preset, window)
                assertTrue("$preset in $window", window.allows(state.workspace))
                assertEquals(state, state.clamped(window))
            }
        }
    }

    @Test
    fun `two panels takes the workbench where there is room and keeps its columns otherwise`() {
        val wide = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
        assertEquals(Workspace.WORKBENCH, wide.workspace)
        assertEquals(Dock.RIGHT, wide.inspector.dock)
        val short = LayoutState.preset(LayoutPreset.TWO_PANELS, phoneSideways)
        assertEquals(Workspace.SIDE_BY_SIDE, short.workspace)
        assertEquals(Dock.LEFT, short.tray.dock)
    }

    @Test
    fun `choosing a workspace the window cannot hold changes nothing`() {
        val start = LayoutState.defaultFor(phone)
        assertEquals(start, start.reduce(LayoutAction.SetWorkspace(Workspace.WORKBENCH), phone))
        assertEquals(start, start.reduce(LayoutAction.SetWorkspace(Workspace.SIDE_BY_SIDE), phone))
    }

    @Test
    fun `choosing a workspace forgets the preset and starts with its own divider`() {
        val start = LayoutState.defaultFor(tablet).reduce(LayoutAction.SetPreviewFraction(0.7f), tablet)
        val stacked = start.reduce(LayoutAction.SetWorkspace(Workspace.STACKED), tablet)
        assertEquals(Workspace.STACKED, stacked.workspace)
        assertEquals(LayoutState.DEFAULT_PREVIEW_FRACTION, stacked.previewFraction, 0.0001f)
        assertEquals(null, stacked.preset)
        val back = stacked.reduce(LayoutAction.SetWorkspace(Workspace.WORKBENCH), tablet)
        assertEquals(LayoutState.WORKBENCH_PREVIEW_FRACTION, back.previewFraction, 0.0001f)
        // The same workspace again is not a change.
        assertEquals(back, back.reduce(LayoutAction.SetWorkspace(Workspace.WORKBENCH), tablet))
    }

    @Test
    fun `the preview divider resets to the default of the workspace`() {
        val workbench = LayoutState.defaultFor(tablet).reduce(LayoutAction.SetPreviewFraction(0.8f), tablet)
        assertEquals(LayoutState.WORKBENCH_PREVIEW_FRACTION, workbench.reduce(LayoutAction.ResetPreviewDivider, tablet).previewFraction, 0.0001f)
        val stacked = LayoutState.defaultFor(phone).reduce(LayoutAction.SetPreviewFraction(0.8f), phone)
        assertEquals(LayoutState.DEFAULT_PREVIEW_FRACTION, stacked.reduce(LayoutAction.ResetPreviewDivider, phone).previewFraction, 0.0001f)
    }

    @Test
    fun `the width divider is clamped and resets`() {
        val start = LayoutState.defaultFor(phoneSideways)
        assertEquals(LayoutState.MIN_PREVIEW_WIDTH_FRACTION, start.reduce(LayoutAction.SetPreviewWidthFraction(-1f), phoneSideways).previewWidthFraction, 0.0001f)
        assertEquals(LayoutState.MAX_PREVIEW_WIDTH_FRACTION, start.reduce(LayoutAction.SetPreviewWidthFraction(3f), phoneSideways).previewWidthFraction, 0.0001f)
        val moved = start.reduce(LayoutAction.SetPreviewWidthFraction(0.6f), phoneSideways)
        assertEquals(0.6f, moved.previewWidthFraction, 0.0001f)
        assertEquals(null, moved.preset)
        assertEquals(LayoutState.DEFAULT_PREVIEW_WIDTH_FRACTION, moved.reduce(LayoutAction.ResetPreviewWidthDivider, phoneSideways).previewWidthFraction, 0.0001f)
    }

    @Test
    fun `a workspace falls back to stacked when the window shrinks or turns`() {
        val workbench = LayoutState.defaultFor(tablet)
        val squeezed = workbench.clamped(phone)
        assertEquals(Workspace.STACKED, squeezed.workspace)
        assertEquals(LayoutState.DEFAULT_PREVIEW_FRACTION, squeezed.previewFraction, 0.0001f)
        assertEquals(Workspace.STACKED, LayoutState.defaultFor(phoneSideways).clamped(phone).workspace)
        assertEquals(Workspace.WORKBENCH, workbench.clamped(tablet).workspace)
    }

    @Test
    fun `reset returns the workspace of the window`() {
        val messy = LayoutState.defaultFor(tablet).reduce(LayoutAction.SetWorkspace(Workspace.SIDE_BY_SIDE), tablet)
        assertEquals(LayoutState.defaultFor(tablet), messy.reduce(LayoutAction.Reset, tablet))
    }

    @Test
    fun `a layout survives a round trip with its workspace and widths`() {
        val state = LayoutState.defaultFor(tablet)
            .reduce(LayoutAction.SetWorkspace(Workspace.SIDE_BY_SIDE), tablet)
            .reduce(LayoutAction.SetPreviewWidthFraction(0.55f), tablet)
        assertEquals(state, LayoutCodec.decode(LayoutCodec.encode(state)))
    }

    @Test
    fun `a layout saved before workspaces existed reads as stacked`() {
        val old = LayoutCodec.decode("pf=0.45;lw=320.0;rw=320.0;tray=LEFT,0;insp=OVERLAY,0;lane=MEDIUM;preset=DEFAULT")!!
        assertEquals(Workspace.STACKED, old.workspace)
        assertEquals(0.45f, old.previewFraction, 0.0001f)
        assertEquals(Dock.LEFT, old.tray.dock)
        // An unknown workspace name from a later version is skipped.
        assertEquals(Workspace.STACKED, LayoutCodec.decode("ws=HOLOGRAM;pf=0.4")!!.workspace)
    }
}

class ColumnsTest {
    @Test
    fun `no columns leaves the whole width to the content`() {
        assertEquals(ColumnsPx(0, 0, 0, 0, 1000), resolveColumns(1000, 0, 0, false, false, 20, fullscreen = false))
    }

    @Test
    fun `open columns get a divider and the content keeps the rest`() {
        val cols = resolveColumns(1000, 300, 250, true, true, 20, fullscreen = false)
        assertEquals(ColumnsPx(300, 250, 20, 20, 410), cols)
        assertEquals(1000, cols.left + cols.right + cols.leftHandle + cols.rightHandle + cols.main)
    }

    @Test
    fun `a collapsed column is a strip with no divider`() {
        val cols = resolveColumns(1000, 40, 0, leftOpen = false, rightOpen = false, handlePx = 20, fullscreen = false)
        assertEquals(ColumnsPx(40, 0, 0, 0, 960), cols)
    }

    @Test
    fun `the content never gets less than 30 percent and open columns shrink together`() {
        val cols = resolveColumns(1000, 600, 600, true, true, 20, fullscreen = false)
        assertTrue(cols.main >= 300)
        assertEquals(cols.left, cols.right)
        assertEquals(1000, cols.left + cols.right + cols.leftHandle + cols.rightHandle + cols.main)
    }

    @Test
    fun `fullscreen takes every column away`() {
        assertEquals(ColumnsPx(0, 0, 0, 0, 1000), resolveColumns(1000, 300, 300, true, true, 20, fullscreen = true))
    }

    @Test
    fun `side by side splits around its divider and loses no pixel`() {
        val split = resolveSideBySide(1001, 21, 0.4f)
        assertEquals(1001 - 21, split.preview + split.rest)
        assertEquals(392, split.preview)
        assertEquals(0, resolveSideBySide(10, 20, 0.5f).preview + resolveSideBySide(10, 20, 0.5f).rest)
    }
}
