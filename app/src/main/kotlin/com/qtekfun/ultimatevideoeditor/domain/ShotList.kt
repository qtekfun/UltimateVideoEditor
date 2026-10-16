package com.qtekfun.ultimatevideoeditor.domain

/**
 * The flag list as a shot list: the manual markers of a project, in time order, optionally only the ones not yet shot and
 * only some colours, grouped by colour. Beat markers are not shots and never appear. Pure, so the text a person pastes
 * into a notes or tasks app is unit tested. The words in it (title, group names) come from the caller, because they are localised.
 */
object ShotList {

    /** What goes into the list. A null [colors] means every colour, including flags with none. */
    data class Filter(val onlyPending: Boolean = true, val colors: Set<MarkerColor?>? = null)

    /** One colour's flags; [color] is null for the flags that have none. */
    data class Group(val color: MarkerColor?, val flags: List<Marker>)

    /** The shots of [markers] that pass [filter]. */
    fun select(markers: List<Marker>, filter: Filter): List<Marker> =
        markers.filter { marker ->
            marker.kind == MarkerKind.MANUAL &&
                (!filter.onlyPending || !marker.done) &&
                (filter.colors == null || marker.color in filter.colors)
        }.sortedWith(compareBy({ it.frame }, { it.id }))

    /** Groups [flags] by colour in the colour order of [MarkerColor] (no colour last); each group keeps time order. */
    fun grouped(flags: List<Marker>): List<Group> {
        val order = MarkerColor.entries + listOf<MarkerColor?>(null)
        return order.mapNotNull { color ->
            flags.filter { it.color == color }.takeIf { it.isNotEmpty() }?.let { Group(color, it) }
        }
    }

    /**
     * Plain text with a checkbox per flag, one group per colour:
     *
     * ```
     * Title
     *
     * Red (2)
     * [ ] 00:00:12:05 · Lighthouse at sunset: wide shot
     * [x] 00:01:03:00 · Hands cooking
     * ```
     *
     * [timecode] formats a frame, [groupName] names a colour (null for flags without one); a flag without a name shows its note
     * alone, and one with neither shows only its time. Empty selection gives just the title.
     */
    fun text(title: String, flags: List<Marker>, timecode: (Long) -> String, groupName: (MarkerColor?) -> String): String = buildString {
        append(title)
        for (group in grouped(flags)) {
            append("\n\n").append(groupName(group.color)).append(" (").append(group.flags.size).append(')')
            for (flag in group.flags) append('\n').append(line(flag, timecode))
        }
    }

    private fun line(flag: Marker, timecode: (Long) -> String): String {
        val check = if (flag.done) "[x]" else "[ ]"
        val name = flag.name?.oneLine()
        val note = flag.note?.oneLine()
        val label = when {
            name != null && note != null -> "$name: $note"
            else -> name ?: note
        }
        return if (label == null) "$check ${timecode(flag.frame.value)}" else "$check ${timecode(flag.frame.value)} · $label"
    }

    private fun String.oneLine() = replace(Regex("\\s*\\R\\s*"), " ").trim().takeIf { it.isNotEmpty() }

    /**
     * Comma-separated values for a spreadsheet: `done,colour,time,name,note` (done is `x` or empty) with [header] as the first row. Fields with a comma,
     * quote or line break are quoted, quotes doubled (RFC 4180); [colorName] gives the colour's column text ("" for none).
     */
    fun csv(header: List<String>, flags: List<Marker>, timecode: (Long) -> String, colorName: (MarkerColor?) -> String): String {
        val rows = mutableListOf(header)
        for (flag in flags) {
            rows += listOf(if (flag.done) "x" else "", colorName(flag.color), timecode(flag.frame.value), flag.name.orEmpty(), flag.note.orEmpty())
        }
        return rows.joinToString("\r\n") { row -> row.joinToString(",") { field(it) } } + "\r\n"
    }

    private fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
