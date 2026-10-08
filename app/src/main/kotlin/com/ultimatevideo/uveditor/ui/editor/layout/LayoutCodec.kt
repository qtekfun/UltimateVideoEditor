package com.ultimatevideo.uveditor.ui.editor.layout

/** Where layouts are remembered, one per [LayoutKey]. Local only. */
interface LayoutStore {
    fun load(key: LayoutKey): LayoutState?

    fun save(key: LayoutKey, state: LayoutState)
}

/** A store that remembers nothing; used by tests and as the fallback when preferences are unavailable. */
object NoLayoutStore : LayoutStore {
    override fun load(key: LayoutKey): LayoutState? = null

    override fun save(key: LayoutKey, state: LayoutState) = Unit
}

/**
 * A layout as one line of `key=value` pairs. Reading is forgiving: unknown keys and bad values are
 * skipped, so a file from another version never breaks the editor; the result is clamped by the caller.
 */
object LayoutCodec {
    fun encode(state: LayoutState): String = listOf(
        "pf=${state.previewFraction}",
        "lw=${state.leftWidthDp}",
        "rw=${state.rightWidthDp}",
        "tray=${state.tray.dock.name},${if (state.tray.collapsed) 1 else 0}",
        "insp=${state.inspector.dock.name},${if (state.inspector.collapsed) 1 else 0}",
        "lane=${state.laneHeight.name}",
        "audiolane=${state.audioLaneHeight.name}",
        "preset=${state.preset?.name ?: "-"}",
    ).joinToString(";")

    fun decode(text: String): LayoutState? {
        val pairs = text.split(';').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
        }.toMap()
        if (pairs.isEmpty()) return null
        var state = LayoutState()
        pairs["pf"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { state = state.copy(previewFraction = it) }
        pairs["lw"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { state = state.copy(leftWidthDp = it) }
        pairs["rw"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { state = state.copy(rightWidthDp = it) }
        panel(pairs["tray"], Panel.TRAY)?.let { state = state.copy(tray = it) }
        panel(pairs["insp"], Panel.INSPECTOR)?.let { state = state.copy(inspector = it) }
        pairs["lane"]?.let { name -> LaneHeight.entries.firstOrNull { it.name == name } }?.let { state = state.copy(laneHeight = it) }
        pairs["audiolane"]?.let { name -> AudioLaneHeight.entries.firstOrNull { it.name == name } }?.let { state = state.copy(audioLaneHeight = it) }
        state = state.copy(preset = pairs["preset"]?.let { name -> LayoutPreset.entries.firstOrNull { it.name == name } })
        return state
    }

    private fun panel(text: String?, panel: Panel): PanelState? {
        val parts = text?.split(',') ?: return null
        val dock = Dock.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
        if (dock !in panel.allowedDocks()) return null
        return PanelState(dock, collapsed = parts.getOrNull(1) == "1")
    }
}
