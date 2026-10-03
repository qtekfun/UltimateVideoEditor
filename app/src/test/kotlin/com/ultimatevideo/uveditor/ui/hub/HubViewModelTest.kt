package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.engine.EngineClient
import com.ultimatevideo.uveditor.engine.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val files = mutableMapOf<String, ByteArray>()
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = files[uri] ?: throw IOException("missing $uri")
            override fun write(uri: String, bytes: ByteArray) {
                files[uri] = bytes
            }
        },
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
    )

    private fun viewModel(engine: EngineClient = FakeEngine("1.2.3")) =
        HubViewModel(engine, repository(), dispatcher)

    @Test
    fun `starts empty and shows the engine version`() {
        val vm = viewModel()

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.projects.isEmpty())
        assertEquals("1.2.3", vm.state.value.engineVersion)
    }

    @Test
    fun `engine failure is surfaced as error state`() {
        val vm = viewModel(FakeEngine(failure = EngineException("boom")))

        assertNull(vm.state.value.engineVersion)
        assertEquals("boom", vm.state.value.engineError)
    }

    @Test
    fun `new project dialog edits the draft`() {
        val vm = viewModel()

        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftNameChanged("Trip"))
        vm.onIntent(HubIntent.DraftResolutionSelected(ProjectPresets.resolutions[3]))
        vm.onIntent(HubIntent.DraftFpsSelected(ProjectPresets.fps[6]))
        vm.onIntent(HubIntent.DraftColorSpaceSelected(ProjectPresets.colorSpaces[1]))

        val draft = vm.state.value.newProjectDraft!!
        assertEquals("Trip", draft.name)
        assertEquals(3840, draft.resolution.width)
        assertEquals(60000 to 1001, draft.fps.num to draft.fps.den)
        assertEquals("Rec2020-HLG", draft.colorSpace.id)
    }

    @Test
    fun `blank draft name cannot be created`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftNameChanged("  "))

        vm.onIntent(HubIntent.ConfirmCreate)

        assertNotNull(vm.state.value.newProjectDraft)
        assertTrue(vm.state.value.projects.isEmpty())
    }

    @Test
    fun `create closes the dialog and lists the project`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftNameChanged("Trip"))

        vm.onIntent(HubIntent.ConfirmCreate)

        assertNull(vm.state.value.newProjectDraft)
        val project = vm.state.value.projects.single()
        assertEquals("Trip", project.name)
        assertEquals(1920, project.settings.width)
        assertEquals(30, project.settings.fpsNum)
    }

    @Test
    fun `rename clone and delete update the list`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.ConfirmCreate)
        val created = vm.state.value.projects.single()

        vm.onIntent(HubIntent.RequestRename(created))
        vm.onIntent(HubIntent.RenameNameChanged("Renamed"))
        vm.onIntent(HubIntent.ConfirmRename)
        assertEquals(listOf("Renamed"), vm.state.value.projects.map { it.name })
        assertNull(vm.state.value.renameDraft)

        vm.onIntent(HubIntent.Clone(created.id))
        assertEquals(setOf("Renamed", "Renamed copy"), vm.state.value.projects.map { it.name }.toSet())

        vm.onIntent(HubIntent.RequestDelete(vm.state.value.projects.first()))
        assertNotNull(vm.state.value.deleteTarget)
        vm.onIntent(HubIntent.ConfirmDelete)
        assertEquals(1, vm.state.value.projects.size)
        assertNull(vm.state.value.deleteTarget)
    }

    @Test
    fun `dismiss clears every dialog`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)

        vm.onIntent(HubIntent.DismissDialogs)

        assertNull(vm.state.value.newProjectDraft)
    }

    @Test
    fun `store errors reach the user as a message effect`() = runTest {
        val vm = viewModel()

        vm.onIntent(HubIntent.ImportFrom("mem://missing"))

        val effect = vm.effects.first()
        assertTrue(effect is HubEffect.ShowMessage)
    }

    @Test
    fun `export request asks the screen for a file picker`() = runTest {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.ConfirmCreate)
        val project = vm.state.value.projects.single()

        vm.onIntent(HubIntent.RequestExport(project))

        val effect = vm.effects.first() as HubEffect.LaunchExportPicker
        assertEquals(project.id, effect.projectId)
        assertEquals("New project.json", effect.suggestedFileName)
    }

    @Test
    fun `fps labels come from the rational`() {
        assertEquals("29.97", formatFps(30000, 1001))
        assertEquals("23.976", formatFps(24000, 1001))
        assertEquals("59.94", formatFps(60000, 1001))
        assertEquals("25", formatFps(25, 1))
    }

    private class FakeEngine(
        private val version: String = "0",
        private val failure: EngineException? = null,
    ) : EngineClient {
        override fun version(): String = failure?.let { throw it } ?: version
    }
}
