package com.qtekfun.ultimatevideoeditor.ui.editor.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarOrderTest {
    private val all = ToolbarItem.entries

    @Test
    fun `the default is the current order of the bar, every item once`() {
        assertEquals(all, ToolbarOrder.DEFAULT.order)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(ToolbarOrder.DEFAULT.overflow.isEmpty())
        // The owner asked for the marker right after Delete.
        val order = ToolbarOrder.DEFAULT.order
        assertEquals(ToolbarItem.MARKER, order[order.indexOf(ToolbarItem.DELETE) + 1])
        assertEquals(listOf(ToolbarItem.SPLIT, ToolbarItem.DELETE), all.filter { it.mandatory })
    }

    @Test
    fun `encode and parse round trip`() {
        val o = ToolbarOrder.DEFAULT.move(ToolbarItem.MIXER, -5).withHidden(ToolbarItem.PROXY, true).withHidden(ToolbarItem.CANVAS, true)
        assertEquals(o, ToolbarOrder.parse(o.encode()))
        assertEquals(ToolbarOrder.DEFAULT, ToolbarOrder.parse(ToolbarOrder.DEFAULT.encode()))
    }

    @Test
    fun `null, blank and garbage give the default`() {
        assertEquals(ToolbarOrder.DEFAULT, ToolbarOrder.parse(null))
        assertEquals(ToolbarOrder.DEFAULT, ToolbarOrder.parse("  "))
        assertEquals(ToolbarOrder.DEFAULT, ToolbarOrder.parse("nope,,-also-nope"))
    }

    @Test
    fun `unknown ids are ignored`() {
        val o = ToolbarOrder.parse("import,mixer,future-thing,split,-also-unknown")
        assertEquals(ToolbarItem.IMPORT, o.order[0])
        assertEquals(ToolbarItem.MIXER, o.order[1])
        assertTrue(o.order.indexOf(ToolbarItem.MIXER) < o.order.indexOf(ToolbarItem.SPLIT))
        assertEquals(all.size, o.order.size)
    }

    @Test
    fun `duplicates count once and the first wins`() {
        val o = ToolbarOrder.parse("import,mixer,split,mixer,-split,-mixer")
        assertEquals(all.size, o.order.size)
        assertEquals(all.size, o.order.toSet().size)
        assertEquals(ToolbarItem.IMPORT, o.order[0])
        assertEquals(ToolbarItem.MIXER, o.order[1])
        assertTrue(o.order.indexOf(ToolbarItem.MIXER) < o.order.indexOf(ToolbarItem.SPLIT))
        assertFalse(o.isHidden(ToolbarItem.SPLIT))
        assertFalse(o.isHidden(ToolbarItem.MIXER))
    }

    @Test
    fun `an old order missing new items puts them back after their default predecessor, visible`() {
        // A version that had no Marker, Captions or Safe zones button.
        val old = all.filter { it !in setOf(ToolbarItem.MARKER, ToolbarItem.CAPTIONS, ToolbarItem.SAFE_ZONE) }
            .joinToString(",") { it.id }
        val o = ToolbarOrder.parse(old)
        val order = o.order
        assertEquals(all, order)
        assertTrue(o.overflow.isEmpty())

        // The same after the user shuffled the old items.
        val shuffled = ToolbarOrder.parse(old.split(',').reversed().joinToString(","))
        val s = shuffled.order
        assertEquals(all.size, s.size)
        assertEquals(s.indexOf(ToolbarItem.DELETE) + 1, s.indexOf(ToolbarItem.MARKER))
        assertEquals(s.indexOf(ToolbarItem.TITLE) + 1, s.indexOf(ToolbarItem.CAPTIONS))
        assertEquals(s.indexOf(ToolbarItem.CANVAS) + 1, s.indexOf(ToolbarItem.SAFE_ZONE))
        assertTrue(shuffled.overflow.isEmpty())
    }

    @Test
    fun `a new first item goes to the front and consecutive new items keep their relative order`() {
        val o = ToolbarOrder.parse("delete,split")
        assertEquals(all.size, o.order.size)
        assertEquals(ToolbarItem.IMPORT, o.order[0])
        assertEquals(o.order.indexOf(ToolbarItem.SPLIT) + 1, o.order.indexOf(ToolbarItem.DETACH_AUDIO))
        val placed = setOf(ToolbarItem.IMPORT, ToolbarItem.DELETE, ToolbarItem.SPLIT, ToolbarItem.DETACH_AUDIO)
        val rest = o.order.filter { it !in placed }
        assertEquals(all.filter { it !in placed }, rest)
    }

    @Test
    fun `a new item next to a hidden neighbour is visible`() {
        val saved = all.filter { it != ToolbarItem.MARKER }.joinToString(",") { if (it == ToolbarItem.PROXY) "-proxy" else it.id }
        val o = ToolbarOrder.parse(saved)
        assertFalse(o.isHidden(ToolbarItem.MARKER))
        assertTrue(o.isHidden(ToolbarItem.PROXY))
    }

    @Test
    fun `mandatory items cannot be hidden, from the code or from a saved text`() {
        var o = ToolbarOrder.DEFAULT
        for (item in all) o = o.withHidden(item, true)
        assertEquals(all.filter { it.mandatory }, o.visible)
        assertEquals(all.size - 2, o.overflow.size)
        assertSame(o, o.withHidden(ToolbarItem.SPLIT, true))

        val fromText = ToolbarOrder.parse(all.joinToString(",") { "-${it.id}" })
        assertEquals(all.filter { it.mandatory }, fromText.visible)
    }

    @Test
    fun `all hidden except the mandatory ones still lists every item once`() {
        val o = ToolbarOrder.parse(all.joinToString(",") { "-${it.id}" })
        assertEquals(all.size, (o.visible + o.overflow).size)
        assertEquals(all.toSet(), (o.visible + o.overflow).toSet())
    }

    @Test
    fun `move shifts within the whole order and stops at the ends`() {
        val up = ToolbarOrder.DEFAULT.move(ToolbarItem.DELETE, -1)
        assertEquals(ToolbarItem.DELETE, up.order[ToolbarItem.DELETE.ordinal - 1])
        assertEquals(ToolbarItem.DETACH_AUDIO, up.order[ToolbarItem.DELETE.ordinal])
        assertSame(ToolbarOrder.DEFAULT, ToolbarOrder.DEFAULT.move(ToolbarItem.IMPORT, -1))
        assertSame(ToolbarOrder.DEFAULT, ToolbarOrder.DEFAULT.move(all.last(), 1))
        assertEquals(ToolbarItem.IMPORT, ToolbarOrder.DEFAULT.move(ToolbarItem.IMPORT, 100).order.last())
        assertEquals(all.size, up.order.toSet().size)
    }

    @Test
    fun `hidden items move with the list and show again where they are`() {
        val o = ToolbarOrder.DEFAULT.withHidden(ToolbarItem.MIXER, true).move(ToolbarItem.MIXER, -2).withHidden(ToolbarItem.MIXER, false)
        assertFalse(o.isHidden(ToolbarItem.MIXER))
        assertEquals(ToolbarItem.MIXER.ordinal - 2, o.order.indexOf(ToolbarItem.MIXER))
    }

    @Test
    fun `a changed order is not the default`() {
        val o = ToolbarOrder.DEFAULT.move(ToolbarItem.TITLE, -3).withHidden(ToolbarItem.PROXY, true)
        assertFalse(o.isDefault)
        assertTrue(ToolbarOrder.DEFAULT.isDefault)
        assertTrue(ToolbarOrder.DEFAULT.withHidden(ToolbarItem.PROXY, true).withHidden(ToolbarItem.PROXY, false).isDefault)
    }
}
