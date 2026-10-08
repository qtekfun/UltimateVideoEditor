package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.SessionStore
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.engine.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Multi-select, the Continue card, layout persistence and the storage scan, through the real view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubLibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private var counter = 0
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val engine = object : EngineClient {
        override fun version() = "1.0"
    }

    private fun repository() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
        },
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
    )

    private class MemoryViewStore(var saved: HubViewPrefs = HubViewPrefs()) : HubViewStore {
        var saves = 0

        override fun load() = saved

        override fun save(prefs: HubViewPrefs) {
            saved = prefs
            saves++
        }
    }

    private class FakeSession(private val unfinished: String?) : SessionStore {
        override fun markOpen(projectId: String) = Unit

        override fun markClosed() = Unit

        override fun unfinishedProjectId(): String? = unfinished
    }

    private fun viewModel(
        repo: ProjectRepository,
        store: HubViewStore = NoHubViewStore,
        scanner: StorageScanner? = null,
        session: SessionStore? = null,
    ) = HubViewModel(engine, repo, dispatcher, session, viewStore = store, storageScanner = scanner)

    private fun make(repo: ProjectRepository, vararg names: String): List<ProjectDto> =
        runBlocking { names.map { repo.create(it, settings) } }

    // region multi-select

    @Test
    fun `deleting several asks once, removes them all and leaves selection mode`() {
        val repo = repository()
        val made = make(repo, "A", "B", "C")
        val vm = viewModel(repo)

        vm.onIntent(HubIntent.EnterSelection(made[0].id))
        vm.onIntent(HubIntent.ToggleSelected(made[2].id))
        vm.onIntent(HubIntent.DeleteSelected)
        assertEquals(2, vm.state.value.deleteTargets.size)

        vm.onIntent(HubIntent.ConfirmDelete)

        assertEquals(listOf("B"), vm.state.value.projects.map { it.name })
        assertTrue(vm.state.value.deleteTargets.isEmpty())
        assertFalse(vm.state.value.selecting)
    }

    @Test
    fun `cancelling the delete keeps the projects and the selection`() {
        val repo = repository()
        val made = make(repo, "A", "B")
        val vm = viewModel(repo)
        vm.onIntent(HubIntent.EnterSelection(made[0].id))
        vm.onIntent(HubIntent.DeleteSelected)

        vm.onIntent(HubIntent.DismissDialogs)

        assertEquals(2, vm.state.value.projects.size)
        assertTrue(vm.state.value.deleteTargets.isEmpty())
        assertTrue(vm.state.value.selecting)
    }

    @Test
    fun `duplicating several copies each one and leaves selection mode`() {
        val repo = repository()
        val made = make(repo, "A", "B", "C")
        val vm = viewModel(repo)
        vm.onIntent(HubIntent.EnterSelection(made[0].id))
        vm.onIntent(HubIntent.ToggleSelected(made[1].id))

        vm.onIntent(HubIntent.DuplicateSelected)

        assertEquals(setOf("A", "B", "C", "A copy", "B copy"), vm.state.value.projects.map { it.name }.toSet())
        assertFalse(vm.state.value.selecting)
    }

    @Test
    fun `exporting or renaming needs exactly one project`() {
        val repo = repository()
        val made = make(repo, "A", "B")
        val vm = viewModel(repo)
        vm.onIntent(HubIntent.EnterSelection(made[0].id))
        vm.onIntent(HubIntent.ToggleSelected(made[1].id))

        vm.onIntent(HubIntent.ExportSelectedFile)
        vm.onIntent(HubIntent.ExportSelectedBundle)
        vm.onIntent(HubIntent.RenameSelected)

        assertNull(vm.state.value.bundleExport)
        assertNull(vm.state.value.renameDraft)
        assertEquals(2, vm.state.value.selected.size)
    }

    @Test
    fun `renaming the single selection opens the dialog and leaves selection mode`() {
        val repo = repository()
        val made = make(repo, "A", "B")
        val vm = viewModel(repo)
        vm.onIntent(HubIntent.EnterSelection(made[1].id))

        vm.onIntent(HubIntent.RenameSelected)

        assertEquals(made[1].id, vm.state.value.renameDraft?.projectId)
        assertFalse(vm.state.value.selecting)
    }

    @Test
    fun `the single selection can export a bundle`() {
        val repo = repository()
        val made = make(repo, "A")
        val vm = viewModel(repo)
        vm.onIntent(HubIntent.EnterSelection(made[0].id))

        vm.onIntent(HubIntent.ExportSelectedBundle)

        assertNotNull(vm.state.value.bundleExport)
        assertFalse(vm.state.value.selecting)
    }

    @Test
    fun `a delete from the menu of one project does not need a selection`() {
        val repo = repository()
        val made = make(repo, "A", "B")
        val vm = viewModel(repo)

        vm.onIntent(HubIntent.RequestDelete(vm.state.value.projects.first { it.id == made[0].id }))
        vm.onIntent(HubIntent.ConfirmDelete)

        assertEquals(listOf("B"), vm.state.value.projects.map { it.name })
    }

    // endregion

    // region continue card

    @Test
    fun `the card follows the newest project and goes away while searching or selecting`() {
        val repo = repository()
        val made = make(repo, "Old", "New")
        File(tmp.root, "projects/${made[0].id}/project.json").setLastModified(1_000_000)
        File(tmp.root, "projects/${made[1].id}/project.json").setLastModified(2_000_000)
        val vm = viewModel(repo)

        assertEquals("New", vm.state.value.continueCard?.project?.name)
        vm.onIntent(HubIntent.ToggleSearch)
        assertNull(vm.state.value.continueCard)
        vm.onIntent(HubIntent.ToggleSearch)
        vm.onIntent(HubIntent.EnterSelection(made[0].id))
        assertNull(vm.state.value.continueCard)
        vm.onIntent(HubIntent.ExitSelection)
        assertNotNull(vm.state.value.continueCard)
    }

    @Test
    fun `an interrupted session shows the resume text on the card until it is handled`() {
        val repo = repository()
        val made = make(repo, "Film", "Other")
        val vm = viewModel(repo, session = FakeSession(unfinished = made[0].id))

        assertEquals(true, vm.state.value.continueCard?.resume)
        assertEquals(made[0].id, vm.state.value.continueCard?.project?.id)

        vm.onIntent(HubIntent.DismissResume)
        assertEquals(false, vm.state.value.continueCard?.resume)
    }

    @Test
    fun `deleting the project of the offer drops the offer`() {
        val repo = repository()
        val made = make(repo, "Film", "Other")
        val vm = viewModel(repo, session = FakeSession(unfinished = made[0].id))

        vm.onIntent(HubIntent.RequestDelete(vm.state.value.projects.first { it.id == made[0].id }))
        vm.onIntent(HubIntent.ConfirmDelete)

        assertNull(vm.state.value.resumeProject)
    }

    @Test
    fun `no card without projects`() {
        assertNull(viewModel(repository()).state.value.continueCard)
    }

    // endregion

    // region persistence

    @Test
    fun `the saved layout and order are applied at start`() {
        val store = MemoryViewStore(HubViewPrefs(HubViewMode.GRID, ProjectSort.SIZE, ascending = true))

        val state = viewModel(repository(), store).state.value

        assertEquals(HubViewMode.GRID, state.viewMode)
        assertEquals(ProjectSort.SIZE, state.sort)
        assertTrue(state.sortAscending)
    }

    @Test
    fun `changing layout, key or direction is remembered`() {
        val store = MemoryViewStore()
        val vm = viewModel(repository(), store)

        vm.onIntent(HubIntent.ViewModeSelected(HubViewMode.GRID))
        assertEquals(HubViewMode.GRID, store.saved.mode)
        vm.onIntent(HubIntent.SortSelected(ProjectSort.NAME))
        assertEquals(HubViewPrefs(HubViewMode.GRID, ProjectSort.NAME, ascending = true), store.saved)
        vm.onIntent(HubIntent.ToggleSortDirection)
        assertEquals(HubViewPrefs(HubViewMode.GRID, ProjectSort.NAME, ascending = false), store.saved)

        // A new view model reads it back.
        val again = viewModel(repository(), store).state.value
        assertEquals(HubViewMode.GRID, again.viewMode)
        assertEquals(ProjectSort.NAME, again.sort)
        assertFalse(again.sortAscending)
    }

    @Test
    fun `unknown stored names fall back to the defaults`() {
        assertEquals(HubViewMode.LIST, hubViewModeOf("poster-wall"))
        assertEquals(HubViewMode.LIST, hubViewModeOf(null))
        assertEquals(ProjectSort.LAST_EDITED, projectSortOf("by-colour"))
        assertEquals(HubViewMode.GRID, hubViewModeOf("GRID"))
        assertEquals(ProjectSort.LENGTH, projectSortOf("LENGTH"))
    }

    // endregion

    // region storage

    @Test
    fun `the storage is measured at start and again after a delete or duplicate`() {
        val repo = repository()
        val made = make(repo, "A", "B")
        var scans = 0
        val lister = object : DiskLister {
            override fun list(path: String): List<DiskEntry> {
                if (path == "/projects") scans++
                return emptyList()
            }
        }
        val vm = viewModel(repo, scanner = StorageScanner(lister, "/projects", "/cache", { 42L }))
        assertEquals(1, scans)
        assertEquals(42L, vm.state.value.storage?.freeBytes)

        vm.onIntent(HubIntent.Clone(made[0].id))
        assertEquals(2, scans)
        vm.onIntent(HubIntent.RequestDelete(vm.state.value.projects.first()))
        vm.onIntent(HubIntent.ConfirmDelete)
        assertEquals(3, scans)
        vm.onIntent(HubIntent.RefreshStorage)
        assertEquals(4, scans)
    }

    @Test
    fun `without a scanner the card has nothing to show and nothing breaks`() {
        val vm = viewModel(repository())
        vm.onIntent(HubIntent.RefreshStorage)
        assertNull(vm.state.value.storage)
    }

    // endregion
}
