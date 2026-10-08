package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSelectionTest {

    private fun project(name: String, modified: Long = 0) =
        ProjectSummary("id-$name", name, ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"), modified, durationFrames = 30)

    private val a = project("A", 10)
    private val b = project("B", 30)
    private val c = project("C", 20)
    private val state = HubState(isLoading = false, projects = listOf(a, b, c))

    // region selection

    @Test
    fun `a long press enters selection mode with that project ticked`() {
        val next = state.enterSelection(a.id)
        assertTrue(next.selecting)
        assertEquals(setOf(a.id), next.selected)
    }

    @Test
    fun `an unknown id does not start selection`() {
        assertFalse(state.enterSelection("nope").selecting)
    }

    @Test
    fun `toggling adds and removes and the last removal leaves selection mode`() {
        var s = state.enterSelection(a.id).toggled(b.id)
        assertEquals(setOf(a.id, b.id), s.selected)
        s = s.toggled(a.id)
        assertEquals(setOf(b.id), s.selected)
        s = s.toggled(b.id)
        assertFalse(s.selecting)
    }

    @Test
    fun `toggling outside selection mode does nothing`() {
        assertSame(state, state.toggled(a.id))
    }

    @Test
    fun `select all ticks what is listed and a search narrows it`() {
        assertEquals(setOf(a.id, b.id, c.id), state.enterSelection(a.id).selectAllVisible().selected)
        val searching = state.copy(searchOpen = true, query = "b").enterSelection(b.id)
        assertEquals(setOf(b.id), searching.selectAllVisible().selected)
        assertSame(state, state.selectAllVisible())
    }

    @Test
    fun `exiting clears the selection`() {
        assertFalse(state.enterSelection(a.id).exitSelection().selecting)
    }

    @Test
    fun `a refresh drops ticks of projects that are gone`() {
        val ticked = state.enterSelection(a.id).toggled(b.id)
        val after = ticked.copy(projects = listOf(b, c)).prunedSelection()
        assertEquals(setOf(b.id), after.selected)
        assertFalse(ticked.copy(projects = listOf(c)).prunedSelection().selecting)
    }

    @Test
    fun `back leaves selection first and search after`() {
        assertFalse(state.handlesBack)
        assertTrue(state.copy(searchOpen = true).handlesBack)
        val both = state.copy(searchOpen = true).enterSelection(a.id)
        assertTrue(both.handlesBack)
        // After the selection is gone the search is still open, so the next back closes it.
        val afterFirst = both.exitSelection()
        assertTrue(afterFirst.handlesBack)
        assertFalse(afterFirst.toggledSearch().handlesBack)
    }

    @Test
    fun `closing the search forgets the text`() {
        val open = state.toggledSearch()
        assertTrue(open.searchOpen)
        val closed = open.copy(query = "abc").toggledSearch()
        assertFalse(closed.searchOpen)
        assertEquals("", closed.query)
    }

    // endregion

    // region actions

    @Test
    fun `one project can do everything`() {
        val actions = SelectionActions.of(1)
        assertTrue(actions.duplicate && actions.delete && actions.rename && actions.exportFile && actions.exportBundle)
        assertNull(actions.exportDisabledReason)
    }

    @Test
    fun `several projects can be duplicated and deleted but not exported or renamed`() {
        val actions = SelectionActions.of(3)
        assertTrue(actions.duplicate)
        assertTrue(actions.delete)
        assertFalse(actions.exportFile)
        assertFalse(actions.exportBundle)
        assertFalse(actions.rename)
        assertNotNull(actions.exportDisabledReason)
    }

    @Test
    fun `nothing is possible without a selection`() {
        val actions = SelectionActions.of(0)
        assertFalse(actions.duplicate || actions.delete || actions.rename || actions.exportFile || actions.exportBundle)
    }

    // endregion

    // region continue card

    @Test
    fun `the card shows the most recently edited project`() {
        val card = state.continueCard
        assertEquals(b.id, card?.project?.id)
        assertFalse(card!!.resume)
    }

    @Test
    fun `the card is hidden when empty, searching or selecting`() {
        assertNull(HubState(isLoading = false).continueCard)
        assertNull(state.copy(searchOpen = true).continueCard)
        assertNull(state.enterSelection(a.id).continueCard)
    }

    @Test
    fun `an interrupted session takes over the card with the resume wording`() {
        val card = state.copy(resumeProject = a).continueCard
        assertEquals(a.id, card?.project?.id)
        assertTrue(card!!.resume)
    }

    // endregion
}
