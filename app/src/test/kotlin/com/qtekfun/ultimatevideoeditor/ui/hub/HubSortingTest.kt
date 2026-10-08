package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Test

class HubSortingTest {

    private fun project(
        name: String,
        modified: Long = 0,
        frames: Long = 0,
        fpsNum: Int = 30,
        id: String = "id-$name",
    ) = ProjectSummary(id, name, ProjectSettingsDto(1920, 1080, fpsNum, 1, "Rec709-SDR"), modified, durationFrames = frames)

    private fun names(list: List<ProjectSummary>) = list.map { it.name }

    private val sample = listOf(
        project("Beta", modified = 20, frames = 300),
        project("alpha", modified = 50, frames = 30),
        project("Charlie", modified = 10, frames = 900),
        project("delta", modified = 40, frames = 90),
    )
    private val sizes = mapOf("id-Beta" to 500L, "id-alpha" to 100L, "id-Charlie" to 900L, "id-delta" to 300L)

    @Test
    fun `last edited sorts newest first by default and oldest first when ascending`() {
        assertEquals(listOf("alpha", "delta", "Beta", "Charlie"), names(ProjectSorting.sort(sample, ProjectSort.LAST_EDITED, false, sizes)))
        assertEquals(listOf("Charlie", "Beta", "delta", "alpha"), names(ProjectSorting.sort(sample, ProjectSort.LAST_EDITED, true, sizes)))
    }

    @Test
    fun `name ignores case in both directions`() {
        assertEquals(listOf("alpha", "Beta", "Charlie", "delta"), names(ProjectSorting.sort(sample, ProjectSort.NAME, true, sizes)))
        assertEquals(listOf("delta", "Charlie", "Beta", "alpha"), names(ProjectSorting.sort(sample, ProjectSort.NAME, false, sizes)))
    }

    @Test
    fun `size uses the bytes on disk and treats an unmeasured project as empty`() {
        assertEquals(listOf("Charlie", "Beta", "delta", "alpha"), names(ProjectSorting.sort(sample, ProjectSort.SIZE, false, sizes)))
        assertEquals(listOf("alpha", "delta", "Beta", "Charlie"), names(ProjectSorting.sort(sample, ProjectSort.SIZE, true, sizes)))
        val partial = ProjectSorting.sort(sample, ProjectSort.SIZE, true, mapOf("id-Beta" to 5L))
        assertEquals("Beta", partial.last().name)
    }

    @Test
    fun `length compares time, not frames, across frame rates`() {
        // 600 frames at 60 fps is 10 s, shorter than 450 frames at 30 fps (15 s).
        val fast = project("Fast", frames = 600, fpsNum = 60)
        val slow = project("Slow", frames = 450, fpsNum = 30)
        assertEquals(listOf("Slow", "Fast"), names(ProjectSorting.sort(listOf(fast, slow), ProjectSort.LENGTH, false, emptyMap())))
        assertEquals(listOf("alpha", "delta", "Beta", "Charlie"), names(ProjectSorting.sort(sample, ProjectSort.LENGTH, true, sizes)))
        assertEquals(listOf("Charlie", "Beta", "delta", "alpha"), names(ProjectSorting.sort(sample, ProjectSort.LENGTH, false, sizes)))
    }

    @Test
    fun `ties keep their incoming order in both directions`() {
        val tied = listOf(project("One", modified = 5), project("Two", modified = 5), project("Three", modified = 5))
        for (ascending in listOf(true, false)) {
            assertEquals("last edited ascending=$ascending", listOf("One", "Two", "Three"), names(ProjectSorting.sort(tied, ProjectSort.LAST_EDITED, ascending, emptyMap())))
            assertEquals("size ascending=$ascending", listOf("One", "Two", "Three"), names(ProjectSorting.sort(tied, ProjectSort.SIZE, ascending, emptyMap())))
            assertEquals("length ascending=$ascending", listOf("One", "Two", "Three"), names(ProjectSorting.sort(tied, ProjectSort.LENGTH, ascending, emptyMap())))
        }
        val sameName = listOf(project("same", id = "a"), project("SAME", id = "b"), project("Same", id = "c"))
        assertEquals(listOf("a", "b", "c"), ProjectSorting.sort(sameName, ProjectSort.NAME, true, emptyMap()).map { it.id })
        assertEquals(listOf("a", "b", "c"), ProjectSorting.sort(sameName, ProjectSort.NAME, false, emptyMap()).map { it.id })
    }

    @Test
    fun `every key has a natural direction`() {
        assertEquals(false, ProjectSort.LAST_EDITED.defaultAscending)
        assertEquals(true, ProjectSort.NAME.defaultAscending)
        assertEquals(false, ProjectSort.SIZE.defaultAscending)
        assertEquals(false, ProjectSort.LENGTH.defaultAscending)
    }

    @Test
    fun `the state sorts with its own key direction and measured sizes`() {
        val state = HubState(projects = sample, sort = ProjectSort.SIZE, sortAscending = false)
        assertEquals(listOf("Beta", "alpha", "Charlie", "delta"), names(state.visibleProjects))
        val measured = state.copy(storage = StorageSnapshot(0, 0, 0, 0, sizes))
        assertEquals(listOf("Charlie", "Beta", "delta", "alpha"), names(measured.visibleProjects))
    }

    @Test
    fun `choosing a new key resets the direction and the same key changes nothing`() {
        val state = HubState(projects = sample, sortAscending = true)
        assertEquals(state, state.withSort(ProjectSort.LAST_EDITED))
        val byName = state.copy(sortAscending = false).withSort(ProjectSort.NAME)
        assertEquals(ProjectSort.NAME, byName.sort)
        assertEquals(true, byName.sortAscending)
    }
}
