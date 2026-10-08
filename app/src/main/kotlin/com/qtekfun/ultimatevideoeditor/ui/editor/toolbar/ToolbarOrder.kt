package com.qtekfun.ultimatevideoeditor.ui.editor.toolbar

/** Where the toolbar order is remembered. One value for every layout and project. Local only. */
interface ToolbarOrderStore {
    fun loadOrder(): ToolbarOrder

    fun saveOrder(order: ToolbarOrder)
}

/** Remembers nothing; the order is the default. Used by tests and as the fallback when preferences are unavailable. */
object NoToolbarOrderStore : ToolbarOrderStore {
    override fun loadOrder(): ToolbarOrder = ToolbarOrder.DEFAULT

    override fun saveOrder(order: ToolbarOrder) = Unit
}

/**
 * The user's arrangement of the main tool row: [order] holds every [ToolbarItem] exactly once, [hidden] says which of them
 * are tucked into the "More" menu at the end of the row. Nothing is ever dropped, so a hidden tool stays reachable.
 * Pure data; every change returns a valid value (mandatory items are never hidden).
 */
class ToolbarOrder private constructor(val order: List<ToolbarItem>, val hidden: Set<ToolbarItem>) {
    /** The items drawn in the row itself, in order. */
    val visible: List<ToolbarItem> get() = order.filter { it !in hidden }

    /** The items in the "More" menu, in order. */
    val overflow: List<ToolbarItem> get() = order.filter { it in hidden }

    fun isHidden(item: ToolbarItem): Boolean = item in hidden

    /** Moves [item] by [delta] places in the full order (hidden ones included), stopping at the ends. */
    fun move(item: ToolbarItem, delta: Int): ToolbarOrder {
        val from = order.indexOf(item)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from == to) return this
        val next = order.toMutableList()
        next.removeAt(from)
        next.add(to, item)
        return ToolbarOrder(next, hidden)
    }

    /** Hides or shows [item]; a mandatory item cannot be hidden, so this leaves the order as it was. */
    fun withHidden(item: ToolbarItem, hide: Boolean): ToolbarOrder {
        if (hide && item.mandatory) return this
        val next = if (hide) hidden + item else hidden - item
        return if (next == hidden) this else ToolbarOrder(order, next)
    }

    val isDefault: Boolean get() = this == DEFAULT

    /** Compact text for preferences: ids separated by commas in order, a hidden one prefixed with `-`. */
    fun encode(): String = order.joinToString(",") { if (it in hidden) "-${it.id}" else it.id }

    override fun equals(other: Any?): Boolean = other is ToolbarOrder && other.order == order && other.hidden == hidden

    override fun hashCode(): Int = 31 * order.hashCode() + hidden.hashCode()

    override fun toString(): String = "ToolbarOrder(${encode()})"

    companion object {
        val DEFAULT = ToolbarOrder(ToolbarItem.entries.toList(), emptySet())

        /**
         * Reads a saved order. Forgiving, so a value from another version never breaks the editor: unknown ids are ignored, a
         * repeated id counts once (the first), and an item missing from the text (a button added in a later version) is put
         * back right after the item that precedes it in the default order, visible, so an update never hides a new button.
         * Null, blank or unreadable text gives the default.
         */
        fun parse(text: String?): ToolbarOrder {
            if (text.isNullOrBlank()) return DEFAULT
            val order = ArrayList<ToolbarItem>()
            val hidden = HashSet<ToolbarItem>()
            for (raw in text.split(',')) {
                val token = raw.trim()
                val item = ToolbarItem.byId(token.removePrefix("-")) ?: continue
                if (item in order) continue
                order.add(item)
                if (token.startsWith("-") && !item.mandatory) hidden.add(item)
            }
            if (order.isEmpty()) return DEFAULT
            for (item in ToolbarItem.entries) {
                if (item in order) continue
                val before = ToolbarItem.entries.subList(0, item.ordinal).lastOrNull { it in order }
                order.add(if (before == null) 0 else order.indexOf(before) + 1, item)
            }
            return ToolbarOrder(order, hidden)
        }
    }
}
